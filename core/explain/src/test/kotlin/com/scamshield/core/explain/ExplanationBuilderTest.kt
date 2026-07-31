package com.scamshield.core.explain

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Confidence
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Verdict
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExplanationBuilderTest {

    private val builder = ExplanationBuilder(RuntimeEnvironment.getApplication())

    private fun result(
        verdict: Verdict = Verdict.SCAM,
        category: ScamCategory = ScamCategory.KYC_PHISHING,
        evidence: List<Evidence> = emptyList(),
    ) = AnalysisResult(
        messageId = MessageId("t"),
        verdict = verdict,
        confidence = Confidence.HIGH,
        category = category,
        categoryConfidence = Confidence.HIGH,
        evidence = evidence,
        analyzersRun = setOf(AnalyzerId.URL),
        latencyMs = 5,
        modelVersion = null,
        rulepackVersion = "v1",
    )

    // Every EvidenceType must render to non-blank text without throwing -- Evidence.kt's own
    // doc comment promise that a missing template is a build failure, exercised here as the
    // runtime half (the compile-time half is ExplanationBuilder's exhaustive `when`, which
    // simply would not compile if a case were missing).
    @Test
    fun `every EvidenceType has a working template`() {
        for (type in EvidenceType.entries) {
            val evidence = Evidence(
                type = type,
                severity = Severity.WARN,
                slots = mapOf(
                    "actual" to "x", "brand" to "y", "real_domain" to "z", "host" to "h",
                    "displayed" to "d1", "tld" to "xyz", "claimed_brand" to "cb",
                    "header_brands" to "hb", "sender" to "s",
                ),
            )
            val text = builder.evidenceText(evidence)
            assertThat(text).isNotEmpty()
        }
    }

    @Test
    fun `domain very new with an age_days slot uses the aged template`() {
        val evidence = Evidence(EvidenceType.DOMAIN_VERY_NEW, Severity.CRITICAL, mapOf("age_days" to "5"))
        assertThat(builder.evidenceText(evidence)).contains("5")
    }

    @Test
    fun `domain very new without an age_days slot uses the unknown-age template`() {
        val evidence = Evidence(EvidenceType.DOMAIN_VERY_NEW, Severity.INFO, mapOf("domain" to "x.com"))
        assertThat(builder.evidenceText(evidence)).doesNotContain("null")
    }

    @Test
    fun `typosquat template substitutes all three slots`() {
        val evidence = Evidence(
            EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND,
            Severity.CRITICAL,
            mapOf("actual" to "sbi-kyc-verify.xyz", "brand" to "State Bank of India", "real_domain" to "sbi.co.in"),
        )
        val text = builder.evidenceText(evidence)
        assertThat(text).contains("sbi-kyc-verify.xyz")
        assertThat(text).contains("State Bank of India")
        assertThat(text).contains("sbi.co.in")
    }

    @Test
    fun `a SAFE verdict gets no action items`() {
        assertThat(builder.actionItems(Verdict.SAFE, ScamCategory.NOT_SCAM)).isEmpty()
    }

    @Test
    fun `a SCAM verdict gets a category lead-in plus the four universal items`() {
        val items = builder.actionItems(Verdict.SCAM, ScamCategory.KYC_PHISHING)
        assertThat(items).hasSize(5)
        assertThat(items[0]).contains("KYC")
    }

    @Test
    fun `a category with no lead-in still gets the four universal items`() {
        val items = builder.actionItems(Verdict.SUSPICIOUS, ScamCategory.NOT_SCAM)
        assertThat(items).hasSize(4)
    }

    @Test
    fun `explain caps top evidence at 3 and reports the remainder`() {
        val evidence = (1..5).map { Evidence(EvidenceType.OTP_SOLICITATION, Severity.CRITICAL) }
        val explanation = builder.explain(result(evidence = evidence))
        assertThat(explanation.topEvidence).hasSize(3)
        assertThat(explanation.remainingEvidenceCount).isEqualTo(2)
    }

    @Test
    fun `explain with fewer than 3 evidence items reports zero remaining`() {
        val evidence = listOf(Evidence(EvidenceType.OTP_SOLICITATION, Severity.CRITICAL))
        val explanation = builder.explain(result(evidence = evidence))
        assertThat(explanation.topEvidence).hasSize(1)
        assertThat(explanation.remainingEvidenceCount).isEqualTo(0)
    }

    @Test
    fun `allEvidence carries every rendered item, not just the top 3 -- needed for 'show all'`() {
        val evidence = (1..5).map { Evidence(EvidenceType.OTP_SOLICITATION, Severity.CRITICAL) }
        val explanation = builder.explain(result(evidence = evidence))
        assertThat(explanation.allEvidence).hasSize(5)
    }
}
