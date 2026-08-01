package com.scamshield.app

import com.google.common.truth.Truth.assertThat
import com.scamshield.analyzer.pattern.PatternAnalyzer
import com.scamshield.analyzer.sender.SenderAnalyzer
import com.scamshield.analyzer.url.UrlAnalyzer
import com.scamshield.core.analysis.ingest.MessageNormalizer
import com.scamshield.core.analysis.orchestrator.AnalysisPipeline
import com.scamshield.core.analysis.orchestrator.Orchestrator
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.data.rulepack.AndroidAssetRulePackSource
import com.scamshield.core.data.rulepack.RulePackLoader
import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.MessageSource
import com.scamshield.core.model.RawMessage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@Serializable
data class VerdictFixture(
    val id: String,
    val category: String? = null,
    val language: String? = null,
    val text: String,
    val senderHint: String? = null,
    val expectedVerdict: String,
    val expectedEvidenceTypes: List<String> = emptyList(),
    val expectedAbsentEvidenceTypes: List<String> = emptyList(),
)

@Serializable
private data class VerdictsFile(
    val categoryExamples: List<VerdictFixture>,
    val genuineBankSms: List<VerdictFixture>,
    val urlCases: List<VerdictFixture>,
    val languageCoverage: List<VerdictFixture>,
    val degenerate: List<VerdictFixture>,
) {
    fun all(): List<VerdictFixture> = categoryExamples + genuineBankSms + urlCases + languageCoverage + degenerate
}

/**
 * design.md section 12's fixture corpus, run against the real Phase 1 pipeline -- the same
 * `MessageNormalizer` -> three rule analyzers -> `FusionPolicy` stack `:app`'s Hilt modules
 * wire up, with the classifier permanently absent exactly as this phase ships (see
 * `AnalysisModule`'s own doc comment). `implementation.md` Phase 1's acceptance criterion "the
 * fixture suite passes with the classifier stubbed" is satisfied here by testing the actual
 * classifier-absent configuration this app runs today, rather than fabricating a synthetic
 * classifier signal Phase 1 does not ship -- design.md section 7's own documented consequence
 * of that absence (rules alone are capped at SUSPICIOUS, never a confident SCAM on wording
 * alone) is exactly what most of the wording-only category examples below assert.
 */
@RunWith(RobolectricTestRunner::class)
class VerdictFixtureTest {

    private lateinit var normalizer: MessageNormalizer
    private lateinit var pipeline: AnalysisPipeline

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val loaded = RulePackLoader(AndroidAssetRulePackSource(context)).load()
        check(!loaded.isBundledFallback) {
            "test setup loaded the last-resort DefaultRulePack, not the real bundled pack -- " +
                "is app/src/main/assets/rulepack/v1 present? Run './gradlew :app:preBuild' first."
        }

        val urlAnalyzer = UrlAnalyzer(
            confusables = loaded.rulePack.confusables,
            banks = loaded.rulePack.banks,
            shorteners = loaded.rulePack.shorteners,
            shortenerBrandOperated = loaded.rulePack.shortenerBrandOperated,
            suspiciousTlds = loaded.rulePack.suspiciousTlds,
            reputationIndex = loaded.reputationIndex,
        )
        val senderAnalyzer = SenderAnalyzer(loaded.rulePack.banks)
        val patternAnalyzer = PatternAnalyzer(loaded.rulePack.patterns)

        normalizer = MessageNormalizer(UrlExtractor(loaded.publicSuffixList), loaded.rulePack.banks)
        pipeline = AnalysisPipeline(
            orchestrator = Orchestrator(listOf(urlAnalyzer, senderAnalyzer, patternAnalyzer)),
            rulepackVersion = loaded.rulePack.meta.version,
        )
    }

    private fun loadFixtures(): VerdictsFile {
        val json = javaClass.classLoader!!.getResourceAsStream("fixtures/verdicts.json")!!
            .bufferedReader()
            .use { it.readText() }
        return LENIENT_JSON.decodeFromString(json)
    }

    private companion object {
        val LENIENT_JSON = Json { ignoreUnknownKeys = true }
    }

    private suspend fun analyze(fixture: VerdictFixture): AnalysisResult {
        val raw = RawMessage(
            id = MessageId(UUID.randomUUID().toString()),
            text = fixture.text,
            senderHint = fixture.senderHint,
            source = MessageSource.MANUAL,
            receivedAt = Instant.now(),
        )
        return pipeline.analyze(normalizer.normalize(raw))
    }

    private fun describe(fixture: VerdictFixture, result: AnalysisResult): String =
        "${fixture.id}: expected verdict=${fixture.expectedVerdict} evidence-includes=" +
            "${fixture.expectedEvidenceTypes} evidence-excludes=${fixture.expectedAbsentEvidenceTypes}; " +
            "got verdict=${result.verdict} evidence=${result.evidence.map { it.type }}"

    private suspend fun failuresFor(fixtures: List<VerdictFixture>): List<String> {
        val failures = mutableListOf<String>()
        for (fixture in fixtures) {
            val result = analyze(fixture)
            val actualTypes = result.evidence.map { it.type.name }

            val verdictOk = result.verdict.name == fixture.expectedVerdict
            val presentOk = fixture.expectedEvidenceTypes.all { it in actualTypes }
            val absentOk = fixture.expectedAbsentEvidenceTypes.none { it in actualTypes }

            if (!verdictOk || !presentOk || !absentOk) {
                failures += describe(fixture, result)
            }
        }
        return failures
    }

    @Test
    fun `full fixture corpus matches expected verdicts and evidence`() = runTest {
        val fixtures = loadFixtures()
        assertThat(failuresFor(fixtures.all())).isEmpty()
    }

    @Test
    fun `every genuine bank SMS fixture returns SAFE -- zero tolerance`() = runTest {
        val fixtures = loadFixtures()
        assertThat(fixtures.genuineBankSms).hasSize(15)
        assertThat(failuresFor(fixtures.genuineBankSms)).isEmpty()
    }

    @Test
    fun `fixture corpus covers all 12 scam categories, 10 url cases, 7 languages, and 6 degenerate cases`() {
        val fixtures = loadFixtures()
        assertThat(fixtures.categoryExamples).hasSize(12)
        assertThat(fixtures.urlCases).hasSize(10)
        assertThat(fixtures.languageCoverage).hasSize(7)
        // 5 authored here plus the 10,000-character case below, which is generated rather
        // than authored as a JSON literal -- see that test's own doc comment.
        assertThat(fixtures.degenerate).hasSize(5)
    }

    @Test
    fun `a 10,000-character message completes without crashing`() = runTest {
        // The sixth design.md section 12 degenerate case. Generated here rather than stored
        // as a giant JSON string literal, which would make the fixture file unreviewable.
        val longText = "Please share your OTP to verify your account. ".repeat(218).take(10_000)
        assertThat(longText).hasLength(10_000)

        // Pattern-only evidence stays SAFE in Phase 1 regardless of message length -- the
        // fusion policy weighs the pattern analyzer's contribution the same way whether one
        // copy of the sentence matched or two hundred (design.md section 5's own weight cap),
        // so repetition cannot inflate a wording-only message past the SAFE band. See this
        // file's own "_comment_verdicts" note in verdicts.json.
        val fixture = VerdictFixture(
            id = "degenerate_10000_chars",
            text = longText,
            expectedVerdict = "SAFE",
            expectedEvidenceTypes = listOf("OTP_SOLICITATION"),
        )
        val result = analyze(fixture)
        assertThat(result.verdict.name).isEqualTo(fixture.expectedVerdict)
    }
}
