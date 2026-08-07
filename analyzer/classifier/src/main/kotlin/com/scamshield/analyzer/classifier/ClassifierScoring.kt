package com.scamshield.analyzer.classifier

import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import kotlin.math.exp

/**
 * The pure math that turns raw ONNX outputs into a [Signal.Scored] (design.md §6.1/§6.2), kept
 * free of ONNX Runtime / DJL so it runs as a plain JVM unit test — the native inference path
 * around it is only reachable in an instrumented test (§12 day 3, the parity gate).
 *
 * Everything here is deterministic and side-effect free: give it logits and a temperature, get a
 * signal back. That's deliberate — the numbers in here (the 0.75/0.25 evidence cut-offs, the 0.45
 * category floor) are the ones a reviewer will want to check against design.md line by line.
 */
internal object ClassifierScoring {

    /** The fixed sequence length the model was exported at (design.md §6.1). */
    const val SEQ_LEN = 128

    /** `[PAD]` id in the bundled `tokenizer.json`; also the right-pad fill. */
    const val PAD_ID = 0L

    /** `p_scam` at or above this earns [EvidenceType.MODEL_HIGH_SCAM_SCORE] (design.md §6.2). */
    const val HIGH_SCAM_THRESHOLD = 0.75f

    /** `p_scam` at or below this earns [EvidenceType.MODEL_LOW_SCAM_SCORE]. */
    const val LOW_SCAM_THRESHOLD = 0.25f

    /** A category is only asserted when the head is at least this confident, else `OTHER_SCAM`. */
    const val CATEGORY_MIN_PROB = 0.45f

    /** Token ids packed to the model's fixed shape, plus whether anything was dropped. */
    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        /** design.md §6.1: logged, never surfaced — a truncated verdict is still a real verdict. */
        val truncated: Boolean,
    )

    /**
     * Pack tokenizer output into the `[SEQ_LEN]` `input_ids` / `attention_mask` the ONNX graph
     * expects. Over-length input is **truncated from the end** — the hook and link a scam leads
     * with are front-loaded (design.md §6.1), so the tail is the safest thing to lose. Shorter
     * input is right-padded with [PAD_ID], with the mask zeroed over the padding.
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
     * Calibrated `p_scam` = `softmax(binary_logits / T)[1]` (design.md §6.2). The temperature `T`
     * is fitted on the validation set and read from `meta.json`; the raw logits are systematically
     * over-confident without it, and every downstream threshold assumes a calibrated probability.
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
     * Assemble the classifier's [Signal.Scored] from the two logit heads (design.md §6.1/§6.2):
     *  - `scamWeight` is the calibrated `p_scam`; fusion (design.md §7) does the rest.
     *  - evidence fires only at the confident tails, high **or** low — a low score is real evidence
     *    of safety and closes architecture.md G2 (every verdict carries ≥1 Evidence).
     *  - the category head is asserted only when `argmax ≥ CATEGORY_MIN_PROB`; below that the model
     *    isn't sure enough to name a scam *type*, so it falls back to `OTHER_SCAM` at low weight.
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
