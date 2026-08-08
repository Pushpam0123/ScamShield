package com.scamshield.analyzer.classifier

import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import kotlin.math.exp

/**
 * The pure math turning raw ONNX outputs into a [Signal.Scored] (design.md §6.1/§6.2), kept free
 * of ONNX Runtime / DJL so it unit-tests on the JVM; the native path around it is only reachable
 * in the instrumented parity gate (§12 day 3).
 */
internal object ClassifierScoring {

    /** Fixed sequence length the model was exported at (design.md §6.1). */
    const val SEQ_LEN = 128

    /** `[PAD]` id in the bundled `tokenizer.json`; also the right-pad fill. */
    const val PAD_ID = 0L

    /** `p_scam` at/above this earns [EvidenceType.MODEL_HIGH_SCAM_SCORE] (design.md §6.2). */
    const val HIGH_SCAM_THRESHOLD = 0.75f

    /** `p_scam` at/below this earns [EvidenceType.MODEL_LOW_SCAM_SCORE]. */
    const val LOW_SCAM_THRESHOLD = 0.25f

    /** A category is asserted only when the head is at least this confident, else `OTHER_SCAM`. */
    const val CATEGORY_MIN_PROB = 0.45f

    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        /** Logged, never surfaced (design.md §6.1) — a truncated verdict is still a real verdict. */
        val truncated: Boolean,
    )

    /**
     * Pack tokenizer output into the `[SEQ_LEN]` tensors the graph expects. Over-length input is
     * **truncated from the end** — the hook/link is front-loaded (design.md §6.1), so the tail is
     * the safest thing to drop; shorter input is right-padded with [PAD_ID], mask zeroed over it.
     */
    fun pack(tokenIds: LongArray): Encoded {
        val truncated = tokenIds.size > SEQ_LEN
        val inputIds = LongArray(SEQ_LEN) { PAD_ID }
        val attentionMask = LongArray(SEQ_LEN)
        val kept = minOf(tokenIds.size, SEQ_LEN)
        for (i in 0 until kept) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1L
        }
        return Encoded(inputIds, attentionMask, truncated)
    }

    /**
     * Calibrated `p_scam` = `softmax(binary_logits / T)[1]` (design.md §6.2). `T` is fitted on the
     * validation set and read from `meta.json`; raw logits are over-confident without it, and every
     * downstream threshold assumes a calibrated probability.
     */
    fun scamProbability(binaryLogits: FloatArray, temperature: Float): Float {
        require(binaryLogits.size == 2) { "binary head must be 2-way, got ${binaryLogits.size}" }
        val scaled = FloatArray(2) { binaryLogits[it] / temperature }
        return softmax(scaled)[1]
    }

    /** Numerically-stable softmax (subtract the max before exponentiating). */
    fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }

    /**
     * Assemble the [Signal.Scored] from the two logit heads (design.md §6.1/§6.2): `scamWeight` is
     * the calibrated `p_scam` (fusion does the rest); evidence fires only at the confident tails
     * (a low score is real evidence of safety, closing architecture.md G2); the category head is
     * asserted only at `argmax ≥ CATEGORY_MIN_PROB`, else `OTHER_SCAM`.
     */
    fun buildSignal(
        binaryLogits: FloatArray,
        categoryLogits: FloatArray,
        temperature: Float,
    ): Signal.Scored {
        val pScam = scamProbability(binaryLogits, temperature)

        val evidence = when {
            pScam >= HIGH_SCAM_THRESHOLD ->
                listOf(Evidence(EvidenceType.MODEL_HIGH_SCAM_SCORE, Severity.WARN))
            pScam <= LOW_SCAM_THRESHOLD ->
                listOf(Evidence(EvidenceType.MODEL_LOW_SCAM_SCORE, Severity.INFO))
            else -> emptyList()
        }

        val categoryProbs = softmax(categoryLogits)
        val argmax = categoryProbs.indices.maxBy { categoryProbs[it] }
        val topProb = categoryProbs[argmax]
        val category =
            if (topProb >= CATEGORY_MIN_PROB) ScamCategory.entries[argmax] else ScamCategory.OTHER_SCAM

        return Signal.Scored(
            analyzerId = AnalyzerId.CLASSIFIER,
            scamWeight = pScam,
            categoryHints = mapOf(category to topProb),
            evidence = evidence,
        )
    }
}
