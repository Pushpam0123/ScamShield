package com.scamshield.analyzer.classifier

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import org.junit.Test

/**
 * JVM tests for the score-mapping math (§12 day 2 acceptance). These pin down the numbers a
 * reviewer would otherwise have to re-derive against design.md §6.1/§6.2 — the temperature
 * scaling, the evidence cut-offs, the category floor, and the truncate-from-the-end packing.
 */
class ClassifierScoringTest {

    /** A 13-way category vector (design.md §6.1 head order) that peaks on one index. */
    private fun categoryLogits(peakIndex: Int, peak: Float, rest: Float = 0f) =
        FloatArray(ScamCategory.entries.size) { if (it == peakIndex) peak else rest }

    @Test
    fun `equal logits give p_scam of one half regardless of temperature`() {
        assertThat(ClassifierScoring.scamProbability(floatArrayOf(3f, 3f), temperature = 0.25f))
            .isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `temperature sharpens the calibrated probability`() {
        val logits = floatArrayOf(-1f, 1f)
        // T = 0.5 => logits/T = [-2, 2] => softmax[1] = 1 / (1 + e^-4).
        assertThat(ClassifierScoring.scamProbability(logits, temperature = 0.5f))
            .isWithin(1e-5f).of(0.98201376f)
        // A temperature above 1 would instead flatten it back toward 0.5.
        assertThat(ClassifierScoring.scamProbability(logits, temperature = 2f))
            .isWithin(1e-5f).of(0.7310586f)
    }

    @Test
    fun `high p_scam emits MODEL_HIGH_SCAM_SCORE at WARN`() {
        val signal = ClassifierScoring.buildSignal(
            binaryLogits = floatArrayOf(0f, 10f),
            categoryLogits = categoryLogits(ScamCategory.KYC_PHISHING.ordinal, 10f),
            temperature = 1f,
        )
        assertThat(signal.scamWeight).isGreaterThan(ClassifierScoring.HIGH_SCAM_THRESHOLD)
        assertThat(signal.evidence).hasSize(1)
        assertThat(signal.evidence.single().type).isEqualTo(EvidenceType.MODEL_HIGH_SCAM_SCORE)
        assertThat(signal.evidence.single().severity).isEqualTo(Severity.WARN)
    }

    @Test
    fun `low p_scam emits MODEL_LOW_SCAM_SCORE at INFO`() {
        val signal = ClassifierScoring.buildSignal(
            binaryLogits = floatArrayOf(10f, 0f),
            categoryLogits = categoryLogits(ScamCategory.NOT_SCAM.ordinal, 10f),
            temperature = 1f,
        )
        assertThat(signal.scamWeight).isLessThan(ClassifierScoring.LOW_SCAM_THRESHOLD)
        assertThat(signal.evidence.single().type).isEqualTo(EvidenceType.MODEL_LOW_SCAM_SCORE)
        assertThat(signal.evidence.single().severity).isEqualTo(Severity.INFO)
    }

    @Test
    fun `the uncertain middle band emits no model evidence`() {
        val signal = ClassifierScoring.buildSignal(
            binaryLogits = floatArrayOf(0f, 0f), // p_scam = 0.5, between the two cut-offs
            categoryLogits = categoryLogits(ScamCategory.LOAN_APP.ordinal, 10f),
            temperature = 1f,
        )
        assertThat(signal.scamWeight).isWithin(1e-6f).of(0.5f)
        assertThat(signal.evidence).isEmpty()
    }

    @Test
    fun `a confident category head is asserted by argmax`() {
        val signal = ClassifierScoring.buildSignal(
            binaryLogits = floatArrayOf(0f, 5f),
            categoryLogits = categoryLogits(ScamCategory.DIGITAL_ARREST.ordinal, 8f),
            temperature = 1f,
        )
        assertThat(signal.categoryHints.keys).containsExactly(ScamCategory.DIGITAL_ARREST)
        assertThat(signal.categoryHints.values.single()).isAtLeast(ClassifierScoring.CATEGORY_MIN_PROB)
    }

    @Test
    fun `a diffuse category head falls back to OTHER_SCAM`() {
        // All 13 logits equal => top prob = 1/13 ≈ 0.077, well under the 0.45 floor.
        val signal = ClassifierScoring.buildSignal(
            binaryLogits = floatArrayOf(0f, 5f),
            categoryLogits = FloatArray(ScamCategory.entries.size),
            temperature = 1f,
        )
        assertThat(signal.categoryHints.keys).containsExactly(ScamCategory.OTHER_SCAM)
    }

    @Test
    fun `pack right-pads a short sequence and masks the padding`() {
        val encoded = ClassifierScoring.pack(longArrayOf(2, 40, 41, 3))
        assertThat(encoded.truncated).isFalse()
        assertThat(encoded.inputIds).hasLength(ClassifierScoring.SEQ_LEN)
        assertThat(encoded.inputIds.take(4)).containsExactly(2L, 40L, 41L, 3L).inOrder()
        assertThat(encoded.inputIds[4]).isEqualTo(ClassifierScoring.PAD_ID)
        assertThat(encoded.inputIds.last()).isEqualTo(ClassifierScoring.PAD_ID)
        // Mask is 1 over the four real tokens, 0 over the padding.
        assertThat(encoded.attentionMask.count { it == 1L }).isEqualTo(4)
        assertThat(encoded.attentionMask.take(4)).containsExactly(1L, 1L, 1L, 1L)
    }

    @Test
    fun `pack truncates from the end and flags it`() {
        // 200 ascending ids: the front (the front-loaded hook/link) must survive, the tail is cut.
        val ids = LongArray(200) { it.toLong() }
        val encoded = ClassifierScoring.pack(ids)
        assertThat(encoded.truncated).isTrue()
        assertThat(encoded.inputIds).hasLength(ClassifierScoring.SEQ_LEN)
        assertThat(encoded.inputIds.first()).isEqualTo(0L)
        assertThat(encoded.inputIds.last()).isEqualTo((ClassifierScoring.SEQ_LEN - 1).toLong())
        assertThat(encoded.attentionMask.all { it == 1L }).isTrue()
    }
}
