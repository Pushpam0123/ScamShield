package com.scamshield.core.analysis.orchestrator

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Confidence
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import com.scamshield.core.model.Verdict
import org.junit.jupiter.api.Test

/**
 * design.md section 7's own fixture-table framing: the classifier is stubbed to a fixed
 * `Signal.Scored`/`Signal.Unavailable`/absence in every case here, so rule-analyzer behavior is
 * verified independently of model quality.
 */
class FusionPolicyTest {

    private fun scored(
        id: AnalyzerId,
        weight: Float = 0f,
        evidence: List<Evidence> = emptyList(),
        categoryHints: Map<ScamCategory, Float> = emptyMap(),
        forceVerdict: Verdict? = null,
    ) = Signal.Scored(id, weight, categoryHints, evidence, forceVerdict)

    private fun fuse(signals: List<Signal>) = FusionPolicy.fuse(
        messageId = MessageId("t"),
        signals = signals,
        rulepackVersion = "v1-test",
        modelVersion = null,
        latencyMs = 12,
    )

    // --- step 2-3: weighted score + thresholds, classifier available ---

    @Test
    fun `zero score is SAFE with HIGH confidence`() {
        val result = fuse(listOf(scored(AnalyzerId.CLASSIFIER, 0f)))
        assertThat(result.verdict).isEqualTo(Verdict.SAFE)
        assertThat(result.confidence).isEqualTo(Confidence.HIGH)
    }

    @Test
    fun `a low but nonzero score is SAFE with LOW confidence`() {
        // 0.55 * 0.5 = 0.275, inside (0.15, 0.35) -- SAFE, but not confidently so.
        val result = fuse(listOf(scored(AnalyzerId.CLASSIFIER, 0.5f)))
        assertThat(result.verdict).isEqualTo(Verdict.SAFE)
        assertThat(result.confidence).isEqualTo(Confidence.LOW)
    }

    @Test
    fun `exactly the suspicious floor is SUSPICIOUS with LOW confidence`() {
        // url 0.6*0.25=0.15, sender 1*0.12=0.12, pattern 1*0.08=0.08 -> 0.35 exactly.
        val result = fuse(
            listOf(
                scored(AnalyzerId.CLASSIFIER, 0f),
                scored(AnalyzerId.URL, 0.6f),
                scored(AnalyzerId.SENDER, 1f),
                scored(AnalyzerId.PATTERN, 1f),
            ),
        )
        assertThat(result.verdict).isEqualTo(Verdict.SUSPICIOUS)
        assertThat(result.confidence).isEqualTo(Confidence.LOW)
    }

    @Test
    fun `a mid-range suspicious score gets MEDIUM confidence`() {
        // 0.55 * 1.0 = 0.55, inside [0.35, 0.70), and >= 0.50.
        val result = fuse(listOf(scored(AnalyzerId.CLASSIFIER, 1f)))
        assertThat(result.verdict).isEqualTo(Verdict.SUSPICIOUS)
        assertThat(result.confidence).isEqualTo(Confidence.MEDIUM)
    }

    @Test
    fun `exactly the scam floor is SCAM with MEDIUM confidence`() {
        // classifier 1*0.55=0.55, url 0.6*0.25=0.15 -> 0.70 exactly, below the 0.85 HIGH floor.
        val result = fuse(listOf(scored(AnalyzerId.CLASSIFIER, 1f), scored(AnalyzerId.URL, 0.6f)))
        assertThat(result.verdict).isEqualTo(Verdict.SCAM)
        assertThat(result.confidence).isEqualTo(Confidence.MEDIUM)
    }

    @Test
    fun `exactly the scam high floor is SCAM with HIGH confidence`() {
        // classifier 1*0.55=0.55, url 1*0.25=0.25, pattern 0.625*0.08=0.05 -> 0.85 exactly.
        val result = fuse(
            listOf(
                scored(AnalyzerId.CLASSIFIER, 1f),
                scored(AnalyzerId.URL, 1f),
                scored(AnalyzerId.PATTERN, 0.625f),
            ),
        )
        assertThat(result.verdict).isEqualTo(Verdict.SCAM)
        assertThat(result.confidence).isEqualTo(Confidence.HIGH)
    }

    // --- classifier unavailable: renormalization + SUSPICIOUS cap ---

    @Test
    fun `classifier absent renormalizes the rule weights and caps SCAM down to SUSPICIOUS`() {
        // Rule-only weighted sum: 0.25+0.12+0.08=0.45, renormalized by /0.45 = 1.0 -- which
        // would threshold as SCAM/HIGH, but "rules alone must not produce a confident SCAM."
        val result = fuse(
            listOf(scored(AnalyzerId.URL, 1f), scored(AnalyzerId.SENDER, 1f), scored(AnalyzerId.PATTERN, 1f)),
        )
        assertThat(result.verdict).isEqualTo(Verdict.SUSPICIOUS)
        assertThat(result.confidence).isEqualTo(Confidence.MEDIUM)
    }

    @Test
    fun `an explicit Unavailable classifier signal is treated the same as an absent one`() {
        val result = fuse(
            listOf(
                Signal.Unavailable(AnalyzerId.CLASSIFIER, "no model"),
                scored(AnalyzerId.URL, 1f),
                scored(AnalyzerId.SENDER, 1f),
                scored(AnalyzerId.PATTERN, 1f),
            ),
        )
        assertThat(result.verdict).isEqualTo(Verdict.SUSPICIOUS)
    }

    @Test
    fun `classifier absent still allows a SAFE or SUSPICIOUS verdict uncapped`() {
        val safe = fuse(listOf(scored(AnalyzerId.URL, 0f)))
        assertThat(safe.verdict).isEqualTo(Verdict.SAFE)

        val suspicious = fuse(listOf(scored(AnalyzerId.URL, 1f))) // 0.25/0.45 = 0.5556
        assertThat(suspicious.verdict).isEqualTo(Verdict.SUSPICIOUS)
    }

    // --- step 1: force verdict ---

    @Test
    fun `a force verdict short-circuits straight to SCAM at HIGH confidence`() {
        val result = fuse(
            listOf(
                scored(AnalyzerId.URL, 0.9f, forceVerdict = Verdict.SCAM),
                scored(AnalyzerId.CLASSIFIER, 0f), // would otherwise be a confident SAFE
            ),
        )
        assertThat(result.verdict).isEqualTo(Verdict.SCAM)
        assertThat(result.confidence).isEqualTo(Confidence.HIGH)
    }

    // --- step 4: category ---

    @Test
    fun `a SAFE verdict is always categorized NOT_SCAM regardless of stray hints`() {
        val result = fuse(
            listOf(scored(AnalyzerId.CLASSIFIER, 0f, categoryHints = mapOf(ScamCategory.LOTTERY_PRIZE to 0.9f))),
        )
        assertThat(result.category).isEqualTo(ScamCategory.NOT_SCAM)
        assertThat(result.categoryConfidence).isEqualTo(Confidence.HIGH)
    }

    @Test
    fun `category is the argmax of combined hints, classifier weighted 0-6`() {
        val result = fuse(
            listOf(
                scored(AnalyzerId.URL, 1f, categoryHints = mapOf(ScamCategory.KYC_PHISHING to 0.3f)),
                // 0.5 * 0.6 = 0.3 -- ties KYC_PHISHING exactly, but LOAN_APP is untouched
                // elsewhere, so bump the classifier's own hint higher to make it the clear winner.
                scored(AnalyzerId.CLASSIFIER, 1f, categoryHints = mapOf(ScamCategory.LOAN_APP to 0.8f)),
            ),
        )
        // LOAN_APP: 0.8 * 0.6 = 0.48; KYC_PHISHING: 0.3 * 1.0 = 0.3 -- LOAN_APP wins.
        assertThat(result.category).isEqualTo(ScamCategory.LOAN_APP)
        assertThat(result.categoryConfidence).isEqualTo(Confidence.MEDIUM) // 0.48 is in [0.4, 0.7)
    }

    @Test
    fun `hints from multiple non-classifier signals for the same category are summed`() {
        val result = fuse(
            listOf(
                scored(AnalyzerId.URL, 1f, categoryHints = mapOf(ScamCategory.KYC_PHISHING to 0.4f)),
                scored(AnalyzerId.SENDER, 1f, categoryHints = mapOf(ScamCategory.KYC_PHISHING to 0.4f)),
            ),
        )
        // 0.4 + 0.4 = 0.8 >= 0.7 -- HIGH.
        assertThat(result.category).isEqualTo(ScamCategory.KYC_PHISHING)
        assertThat(result.categoryConfidence).isEqualTo(Confidence.HIGH)
    }

    @Test
    fun `a non-SAFE verdict with no category hints at all falls back to OTHER_SCAM at LOW confidence`() {
        val result = fuse(listOf(scored(AnalyzerId.CLASSIFIER, 1f))) // SUSPICIOUS, no hints
        assertThat(result.verdict).isEqualTo(Verdict.SUSPICIOUS)
        assertThat(result.category).isEqualTo(ScamCategory.OTHER_SCAM)
        assertThat(result.categoryConfidence).isEqualTo(Confidence.LOW)
    }

    // --- step 5: evidence ordering ---

    @Test
    fun `evidence is sorted by severity descending, then by the producing signal's weight descending`() {
        val infoEvidence = Evidence(EvidenceType.DOMAIN_VERY_NEW, Severity.INFO)
        val warnEvidence = Evidence(EvidenceType.URL_SHORTENER, Severity.WARN)
        val criticalLowWeight = Evidence(EvidenceType.IP_ADDRESS_HOST, Severity.CRITICAL)
        val criticalHighWeight = Evidence(EvidenceType.HOMOGRAPH_CHARACTERS, Severity.CRITICAL)

        val result = fuse(
            listOf(
                scored(AnalyzerId.PATTERN, weight = 0.1f, evidence = listOf(infoEvidence)),
                scored(AnalyzerId.SENDER, weight = 0.4f, evidence = listOf(warnEvidence)),
                scored(AnalyzerId.URL, weight = 0.3f, evidence = listOf(criticalLowWeight)),
                scored(AnalyzerId.CLASSIFIER, weight = 0.9f, evidence = listOf(criticalHighWeight)),
            ),
        )

        assertThat(result.evidence).containsExactly(
            criticalHighWeight, criticalLowWeight, warnEvidence, infoEvidence,
        ).inOrder()
    }

    // --- passthrough fields ---

    @Test
    fun `analyzersRun reflects every analyzer id present regardless of availability`() {
        val result = fuse(
            listOf(scored(AnalyzerId.URL, 0f), Signal.Unavailable(AnalyzerId.CLASSIFIER, "absent")),
        )
        assertThat(result.analyzersRun).containsExactly(AnalyzerId.URL, AnalyzerId.CLASSIFIER)
    }

    @Test
    fun `messageId, rulepackVersion, modelVersion, and latency pass through unchanged`() {
        val result = FusionPolicy.fuse(
            messageId = MessageId("abc-123"),
            signals = listOf(scored(AnalyzerId.URL, 0f)),
            rulepackVersion = "v7",
            modelVersion = "muril-student-v2",
            latencyMs = 42,
        )
        assertThat(result.messageId).isEqualTo(MessageId("abc-123"))
        assertThat(result.rulepackVersion).isEqualTo("v7")
        assertThat(result.modelVersion).isEqualTo("muril-student-v2")
        assertThat(result.latencyMs).isEqualTo(42)
    }

    @Test
    fun `no signals at all is a safe, empty-evidence, NOT_SCAM result rather than a crash`() {
        val result = fuse(emptyList())
        assertThat(result.verdict).isEqualTo(Verdict.SAFE)
        assertThat(result.category).isEqualTo(ScamCategory.NOT_SCAM)
        assertThat(result.evidence).isEmpty()
        assertThat(result.analyzersRun).isEmpty()
    }
}
