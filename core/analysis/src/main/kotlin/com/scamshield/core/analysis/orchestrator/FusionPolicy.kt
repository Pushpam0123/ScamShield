package com.scamshield.core.analysis.orchestrator

import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Confidence
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Signal
import com.scamshield.core.model.Verdict

/**
 * design.md section 7. Deterministic and side-effect-free -- every value it produces is a
 * pure function of its arguments, so it is unit-testable against a fixture table with the
 * classifier stubbed, independently of model quality or timing.
 */
object FusionPolicy {

    // Step 2's weights. Rule-analyzer weights sum to 0.45; when the classifier is unavailable,
    // dividing by RULE_COEFFICIENT_TOTAL is mathematically identical to "redistribute 0.55
    // proportionally across the rule analyzers" -- both reduce to old_coefficient / 0.45.
    private const val CLASSIFIER_COEFFICIENT = 0.55f
    private const val URL_COEFFICIENT = 0.25f
    private const val SENDER_COEFFICIENT = 0.12f
    private const val PATTERN_COEFFICIENT = 0.08f
    private const val RULE_COEFFICIENT_TOTAL = URL_COEFFICIENT + SENDER_COEFFICIENT + PATTERN_COEFFICIENT

    // Step 4's classifier category weight; every other analyzer's categoryHints count at 1.0.
    private const val CLASSIFIER_CATEGORY_WEIGHT = 0.6f

    // Step 4's category-confidence bands. design.md gives no explicit numbers for this part
    // (only for the overall verdict's confidence in step 3); these are the simplest defensible
    // choice, mirroring step 3's own granularity, pending Phase 2's labelled corpus.
    private const val CATEGORY_CONFIDENCE_HIGH = 0.7f
    private const val CATEGORY_CONFIDENCE_MEDIUM = 0.4f

    fun fuse(
        messageId: MessageId,
        signals: List<Signal>,
        rulepackVersion: String,
        modelVersion: String?,
        latencyMs: Long,
    ): AnalysisResult {
        val categoryHints = combinedCategoryHints(signals)
        val evidence = orderedEvidence(signals)
        val analyzersRun = signals.map { it.analyzerId }.toSet()

        val (verdict, confidence) = verdictAndConfidence(signals)
        val (category, categoryConfidence) = categoryFor(verdict, categoryHints)

        return AnalysisResult(
            messageId = messageId,
            verdict = verdict,
            confidence = confidence,
            category = category,
            categoryConfidence = categoryConfidence,
            evidence = evidence,
            analyzersRun = analyzersRun,
            latencyMs = latencyMs,
            modelVersion = modelVersion,
            rulepackVersion = rulepackVersion,
        )
    }

    /** Step 1 (force check) and steps 2-3 (weighted score + thresholds). */
    private fun verdictAndConfidence(signals: List<Signal>): Pair<Verdict, Confidence> {
        // Step 1: only the URL and sender analyzers may set this, and design.md section 4.2 /
        // 3.5 only ever set it to SCAM -- `forceVerdict ?: Verdict.SCAM` is defensive, not a
        // second code path.
        val forced = signals.filterIsInstance<Signal.Scored>().firstOrNull { it.forceVerdict != null }
        if (forced != null) return (forced.forceVerdict ?: Verdict.SCAM) to Confidence.HIGH

        val classifierAvailable = signals.any { it.analyzerId == AnalyzerId.CLASSIFIER && it is Signal.Scored }
        val score = weightedScore(signals, classifierAvailable)
        val (verdict, confidence) = thresholdVerdict(score)

        // "Cap the achievable verdict at SUSPICIOUS unless step 1 fired. Rules alone must not
        // produce a confident SCAM on wording evidence."
        return if (!classifierAvailable && verdict == Verdict.SCAM) {
            Verdict.SUSPICIOUS to (if (score >= SUSPICIOUS_MEDIUM_FLOOR) Confidence.MEDIUM else Confidence.LOW)
        } else {
            verdict to confidence
        }
    }

    private fun weightedScore(signals: List<Signal>, classifierAvailable: Boolean): Float {
        val urlWeight = scamWeightOf(signals, AnalyzerId.URL)
        val senderWeight = scamWeightOf(signals, AnalyzerId.SENDER)
        val patternWeight = scamWeightOf(signals, AnalyzerId.PATTERN)

        return if (classifierAvailable) {
            val classifierWeight = scamWeightOf(signals, AnalyzerId.CLASSIFIER)
            CLASSIFIER_COEFFICIENT * classifierWeight +
                URL_COEFFICIENT * urlWeight +
                SENDER_COEFFICIENT * senderWeight +
                PATTERN_COEFFICIENT * patternWeight
        } else {
            val ruleScore = URL_COEFFICIENT * urlWeight + SENDER_COEFFICIENT * senderWeight + PATTERN_COEFFICIENT * patternWeight
            ruleScore / RULE_COEFFICIENT_TOTAL
        }
    }

    private fun scamWeightOf(signals: List<Signal>, id: AnalyzerId): Float =
        (signals.firstOrNull { it.analyzerId == id } as? Signal.Scored)?.scamWeight ?: 0f

    /** Step 3's threshold table. */
    private fun thresholdVerdict(score: Float): Pair<Verdict, Confidence> = when {
        score >= SCAM_FLOOR -> Verdict.SCAM to (if (score >= SCAM_HIGH_FLOOR) Confidence.HIGH else Confidence.MEDIUM)
        score >= SUSPICIOUS_FLOOR -> Verdict.SUSPICIOUS to (if (score >= SUSPICIOUS_MEDIUM_FLOOR) Confidence.MEDIUM else Confidence.LOW)
        else -> Verdict.SAFE to (if (score <= SAFE_HIGH_CEILING) Confidence.HIGH else Confidence.LOW)
    }

    /** Step 4: sum categoryHints across all `Signal.Scored` entries, classifier weighted 0.6. */
    private fun combinedCategoryHints(signals: List<Signal>): Map<ScamCategory, Float> {
        val totals = mutableMapOf<ScamCategory, Float>()
        for (signal in signals) {
            if (signal !is Signal.Scored) continue
            val weight = if (signal.analyzerId == AnalyzerId.CLASSIFIER) CLASSIFIER_CATEGORY_WEIGHT else 1f
            for ((category, hint) in signal.categoryHints) {
                totals[category] = (totals[category] ?: 0f) + hint * weight
            }
        }
        return totals
    }

    private fun categoryFor(verdict: Verdict, hints: Map<ScamCategory, Float>): Pair<ScamCategory, Confidence> {
        if (verdict == Verdict.SAFE) return ScamCategory.NOT_SCAM to Confidence.HIGH

        val top = hints.entries.maxByOrNull { it.value }
        val confidence = when {
            top == null -> Confidence.LOW
            top.value >= CATEGORY_CONFIDENCE_HIGH -> Confidence.HIGH
            top.value >= CATEGORY_CONFIDENCE_MEDIUM -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return (top?.key ?: ScamCategory.OTHER_SCAM) to confidence
    }

    /**
     * Step 5: CRITICAL -> WARN -> INFO, then by the producing signal's own weight descending.
     * `Severity`'s declared order is INFO, WARN, CRITICAL, so its ordinal sorts ascending by
     * severity already -- descending ordinal is exactly CRITICAL-first.
     */
    private fun orderedEvidence(signals: List<Signal>): List<Evidence> {
        val weighted = signals.filterIsInstance<Signal.Scored>()
            .flatMap { signal -> signal.evidence.map { it to signal.scamWeight } }
        return weighted
            .sortedWith(
                compareByDescending<Pair<Evidence, Float>> { it.first.severity.ordinal }
                    .thenByDescending { it.second },
            )
            .map { it.first }
    }

    private const val SCAM_FLOOR = 0.70f
    private const val SCAM_HIGH_FLOOR = 0.85f
    private const val SUSPICIOUS_FLOOR = 0.35f
    private const val SUSPICIOUS_MEDIUM_FLOOR = 0.50f
    private const val SAFE_HIGH_CEILING = 0.15f
}
