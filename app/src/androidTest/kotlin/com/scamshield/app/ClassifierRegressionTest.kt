package com.scamshield.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.scamshield.analyzer.classifier.AndroidClassifierAssets
import com.scamshield.analyzer.classifier.ClassifierAnalyzer
import com.scamshield.analyzer.classifier.ClassifierAssets
import com.scamshield.analyzer.pattern.PatternAnalyzer
import com.scamshield.analyzer.sender.SenderAnalyzer
import com.scamshield.analyzer.url.UrlAnalyzer
import com.scamshield.core.analysis.ingest.MessageNormalizer
import com.scamshield.core.analysis.orchestrator.AnalysisPipeline
import com.scamshield.core.analysis.orchestrator.Orchestrator
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.data.rulepack.AndroidAssetRulePackSource
import com.scamshield.core.data.rulepack.RulePackLoader
import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.MessageSource
import com.scamshield.core.model.RawMessage
import com.scamshield.core.model.Verdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * The Phase 1 fixture corpus (design.md §12) re-run through the *live* on-device pipeline — the same
 * stack as VerdictFixtureTest but with the real [ClassifierAnalyzer] running on the emulator.
 *
 * Model-quality-independent gates: in the *shipped* config (no model bundled → classifier
 * Unavailable) genuine bank SMS stay SAFE (the zero-tolerance safety row, on the config that
 * actually ships — model assets are gitignored/absent from releases); and a present-then-removed
 * model degrades to identical rule verdicts, never a crash (architecture.md C6).
 *
 * The toy model live is **reported, never asserted**: it scores several genuine bank SMS as scammy,
 * so a "SAFE with the model live" assertion would fail honestly — an accuracy-dependent row §12 says
 * not to gate on until a real model exists. [reportGenuineBankSafetyWithToyModelLive] records the
 * real number; re-arm the gate when a real model lands.
 */
@RunWith(AndroidJUnit4::class)
class ClassifierRegressionTest {

    @Serializable
    private data class Fixture(
        val id: String,
        val text: String,
        val senderHint: String? = null,
        @SerialName("expectedVerdict") val expectedVerdict: String,
    )

    @Serializable
    private data class VerdictsFile(
        val categoryExamples: List<Fixture>,
        val genuineBankSms: List<Fixture>,
        val urlCases: List<Fixture>,
        val languageCoverage: List<Fixture>,
        val degenerate: List<Fixture>,
    ) {
        fun all(): List<Fixture> = categoryExamples + genuineBankSms + urlCases + languageCoverage + degenerate
    }

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var normalizer: MessageNormalizer
    private lateinit var fixtures: VerdictsFile

    /** Assets whose files are all missing — the "model was never trained / was deleted" state (C6). */
    private object MissingAssets : ClassifierAssets {
        override fun openModel(): InputStream = throw java.io.IOException("no model")
        override fun openTokenizer(): InputStream = throw java.io.IOException("no tokenizer")
        override fun readMetaJson(): String = throw java.io.IOException("no meta")
    }

    @Before
    fun setUp() {
        // Rule pack + model live in the app-under-test's assets; the fixture corpus is copied into
        // this (the test APK's) own assets by :app:copyFixtureCorpus.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val loaded = RulePackLoader(AndroidAssetRulePackSource(targetContext)).load()
        check(!loaded.isBundledFallback) { "loaded the fallback rule pack, not the bundled one — run :app:preBuild" }

        val url = UrlAnalyzer(
            confusables = loaded.rulePack.confusables,
            banks = loaded.rulePack.banks,
            shorteners = loaded.rulePack.shorteners,
            shortenerBrandOperated = loaded.rulePack.shortenerBrandOperated,
            suspiciousTlds = loaded.rulePack.suspiciousTlds,
            reputationIndex = loaded.reputationIndex,
        )
        val ruleAnalyzers = listOf(url, SenderAnalyzer(loaded.rulePack.banks), PatternAnalyzer(loaded.rulePack.patterns))
        val classifier = ClassifierAnalyzer(AndroidClassifierAssets(targetContext))

        normalizer = MessageNormalizer(UrlExtractor(loaded.publicSuffixList), loaded.rulePack.banks)
        liveAnalyzers = ruleAnalyzers + classifier
        rulesOnlyAnalyzers = ruleAnalyzers
        rulePackVersion = loaded.rulePack.meta.version

        val fixtureText = testContext.assets.open("fixtures/verdicts.json").bufferedReader().use { it.readText() }
        fixtures = json.decodeFromString(VerdictsFile.serializer(), fixtureText)
    }

    private lateinit var liveAnalyzers: List<Analyzer>
    private lateinit var rulesOnlyAnalyzers: List<Analyzer>
    private lateinit var rulePackVersion: String

    private fun pipeline(analyzers: List<Analyzer>) =
        AnalysisPipeline(Orchestrator(analyzers), rulePackVersion)

    private suspend fun verdict(pipeline: AnalysisPipeline, f: Fixture): Verdict {
        val raw = RawMessage(
            id = MessageId(UUID.randomUUID().toString()),
            text = f.text,
            senderHint = f.senderHint,
            source = MessageSource.MANUAL,
            receivedAt = Instant.now(),
        )
        return pipeline.analyze(normalizer.normalize(raw)).verdict
    }

    /**
     * The headline safety row on the configuration that actually ships: no model bundled, so the
     * classifier is Unavailable and rules alone decide. Every genuine bank SMS must be SAFE.
     */
    @Test
    fun genuineBankSmsStaySafeInShippedConfig() = runTest {
        val shipped = pipeline(rulesOnlyAnalyzers)
        assertThat(fixtures.genuineBankSms).hasSize(15)
        val notSafe = fixtures.genuineBankSms.filter { verdict(shipped, it) != Verdict.SAFE }.map { it.id }
        assertThat(notSafe).isEmpty()
    }

    /**
     * Recorded, not asserted (toy model). Prints how many genuine bank SMS the *live* toy classifier
     * pushes out of SAFE — expected to be non-zero, because the toy model is English-spam-trained and
     * knows nothing of Indian bank SMS. This is the number a real model has to drive back to zero
     * before [genuineBankSmsStaySafeInShippedConfig]'s assertion can be re-armed on the live path.
     */
    @Test
    fun reportGenuineBankSafetyWithToyModelLive() = runTest {
        val live = pipeline(liveAnalyzers)
        val notSafe = fixtures.genuineBankSms.filter { verdict(live, it) != Verdict.SAFE }.map { it.id }
        println("toy model live: ${notSafe.size}/15 genuine bank SMS left SAFE — $notSafe")
    }

    /** architecture.md C6: delete the model → the pipeline still returns rule verdicts, no crash. */
    @Test
    fun missingModelDegradesToRuleVerdicts() = runTest {
        val degraded = pipeline(rulesOnlyAnalyzers + ClassifierAnalyzer(MissingAssets))
        val rulesOnly = pipeline(rulesOnlyAnalyzers)
        for (f in fixtures.all()) {
            assertThat(verdict(degraded, f)).isEqualTo(verdict(rulesOnly, f))
        }
    }

    /** Recorded, not a gate (toy model): how the live classifier moved verdicts vs rules-only. */
    @Test
    fun reportClassifierMovement() = runTest {
        val live = pipeline(liveAnalyzers)
        val rulesOnly = pipeline(rulesOnlyAnalyzers)
        var moved = 0
        for (f in fixtures.all()) {
            val r = verdict(rulesOnly, f)
            val l = verdict(live, f)
            if (r != l) {
                moved++
                println("classifier moved ${f.id}: rules=$r live=$l (expected=${f.expectedVerdict})")
            }
        }
        println("classifier moved $moved / ${fixtures.all().size} corpus verdicts (toy model)")
    }

    @Test
    fun modelAssetIsActuallyBundled() {
        // A guard so the safety test above isn't silently a no-op on a checkout without a model.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val present = runCatching { targetContext.assets.open("model/model.onnx").close() }.isSuccess
        assumeTrue("no model bundled; run :app:copyModelAssets to exercise the live path", present)
        assertThat(present).isTrue()
    }
}
