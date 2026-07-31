package com.scamshield.analyzer.pattern

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.PatternRule
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PatternAnalyzerTest {

    private fun rule(
        id: String = "otp_solicit_en",
        languages: Set<Language> = setOf(Language.EN),
        pattern: String = "share.*otp",
        suppressIf: String? = null,
        evidence: EvidenceType = EvidenceType.OTP_SOLICITATION,
        severity: Severity = Severity.CRITICAL,
        weight: Float = 0.35f,
        categoryHints: Map<ScamCategory, Float> = mapOf(ScamCategory.KYC_PHISHING to 0.4f),
    ) = PatternRule(
        id = id,
        languages = languages,
        regex = Regex(pattern),
        suppressIf = suppressIf?.let { Regex(it) },
        evidence = evidence,
        severity = severity,
        weight = weight,
        categoryHints = categoryHints,
    )

    private fun message(normalized: String, language: Language = Language.EN) = NormalizedMessage(
        id = MessageId("t"),
        original = normalized,
        normalized = normalized,
        urls = emptyList(),
        senderHint = null,
        detectedLanguage = language,
        brandClaims = emptyList(),
    )

    @Test
    fun `analyzer id is PATTERN`() {
        assertThat(PatternAnalyzer(emptyList()).id).isEqualTo(AnalyzerId.PATTERN)
    }

    @Test
    fun `a matching pattern produces evidence and never forces a verdict`() = runTest {
        val signal = PatternAnalyzer(listOf(rule())).analyze(
            message("please share your otp now"),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).containsExactly(EvidenceType.OTP_SOLICITATION)
        assertThat(signal.evidence[0].severity).isEqualTo(Severity.CRITICAL)
        assertThat(signal.forceVerdict).isNull()
    }

    @Test
    fun `a non-matching message produces no evidence`() = runTest {
        val signal = PatternAnalyzer(listOf(rule())).analyze(
            message("your package has been delivered"),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.scamWeight).isEqualTo(0.0f)
    }

    @Test
    fun `a rule outside the message's detected language does not fire`() = runTest {
        val signal = PatternAnalyzer(listOf(rule(languages = setOf(Language.HI_LATN)))).analyze(
            message("please share your otp now", language = Language.EN),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
    }

    @Test
    fun `suppressIf blocks an otherwise-matching rule -- the genuine 'do not share OTP' case`() = runTest {
        val r = rule(suppressIf = "do not share")
        val signal = PatternAnalyzer(listOf(r)).analyze(
            message("your otp is 4821, do not share it with anyone"),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
    }

    @Test
    fun `weight is capped at 0-5 regardless of how many patterns fire`() = runTest {
        val rules = (1..5).map { i ->
            rule(id = "r$i", pattern = "keyword$i", weight = 0.35f)
        }
        val text = (1..5).joinToString(" ") { "keyword$it" }
        val signal = PatternAnalyzer(rules).analyze(message(text)) as Signal.Scored
        assertThat(signal.evidence).hasSize(5)
        assertThat(signal.scamWeight).isEqualTo(0.5f)
    }

    @Test
    fun `a single matched rule's weight is not capped away`() = runTest {
        val signal = PatternAnalyzer(listOf(rule(weight = 0.2f))).analyze(
            message("please share your otp now"),
        ) as Signal.Scored
        assertThat(signal.scamWeight).isEqualTo(0.2f)
    }

    @Test
    fun `category hints from matched rules are summed`() = runTest {
        val a = rule(
            id = "a",
            pattern = "urgent",
            evidence = EvidenceType.URGENCY_DEADLINE,
            categoryHints = mapOf(ScamCategory.KYC_PHISHING to 0.3f, ScamCategory.LOAN_APP to 0.1f),
        )
        val b = rule(
            id = "b",
            pattern = "otp",
            categoryHints = mapOf(ScamCategory.KYC_PHISHING to 0.4f),
        )
        val signal = PatternAnalyzer(listOf(a, b)).analyze(
            message("urgent: share your otp"),
        ) as Signal.Scored
        assertThat(signal.categoryHints[ScamCategory.KYC_PHISHING]).isWithin(0.001f).of(0.7f)
        assertThat(signal.categoryHints[ScamCategory.LOAN_APP]).isWithin(0.001f).of(0.1f)
    }

    @Test
    fun `matching runs against normalized text so zero-width evasion inside a keyword still fires`() = runTest {
        // The normalizer is responsible for stripping zero-width characters (design.md
        // section 2.1) before this analyzer ever sees the text; this test locks in that the
        // analyzer itself reads `normalized`, not `original`, so that stripping is honored.
        val evadedOriginal = "please share your o" + "\u200B" + "tp now"
        val strippedNormalized = "please share your otp now"
        val msg = NormalizedMessage(
            id = MessageId("t"),
            original = evadedOriginal,
            normalized = strippedNormalized,
            urls = emptyList(),
            senderHint = null,
            detectedLanguage = Language.EN,
            brandClaims = emptyList(),
        )
        val signal = PatternAnalyzer(listOf(rule())).analyze(msg) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).containsExactly(EvidenceType.OTP_SOLICITATION)
    }
}
