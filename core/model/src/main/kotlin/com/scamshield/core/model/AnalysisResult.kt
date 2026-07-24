package com.scamshield.core.model

/**
 * The output of the fusion policy and the input to both the Result screen and history.
 *
 * [rulepackVersion] and [modelVersion] are recorded per-result rather than per-app because
 * rule packs update independently of releases (`architecture.md` §11); without them, a past
 * verdict in history cannot be explained.
 */
data class AnalysisResult(
    val messageId: MessageId,
    val verdict: Verdict,
    val confidence: Confidence,
    val category: ScamCategory,
    val categoryConfidence: Confidence,
    /** Sorted by severity descending, then contributing weight descending (`design.md` §7 step 5). */
    val evidence: List<Evidence>,
    val analyzersRun: Set<AnalyzerId>,
    val latencyMs: Long,
    /** Null when the classifier was unavailable — the app is still a complete product (C6). */
    val modelVersion: String?,
    val rulepackVersion: String,
)
