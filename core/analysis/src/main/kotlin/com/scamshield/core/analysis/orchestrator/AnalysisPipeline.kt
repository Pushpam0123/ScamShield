package com.scamshield.core.analysis.orchestrator

import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.NormalizedMessage

/**
 * Ties [Orchestrator] (fan-out) and [FusionPolicy] (fuse, a pure function) together with the
 * one thing a pure fusion function must not do itself: measure wall-clock latency. Kept as a
 * thin convenience so `:app`'s ViewModel does not have to.
 */
class AnalysisPipeline(
    private val orchestrator: Orchestrator,
    private val rulepackVersion: String,
    private val modelVersion: String? = null,
) {
    suspend fun analyze(message: NormalizedMessage): AnalysisResult {
        val startedAt = System.currentTimeMillis()
        val signals = orchestrator.run(message)
        val latencyMs = System.currentTimeMillis() - startedAt

        return FusionPolicy.fuse(
            messageId = message.id,
            signals = signals,
            rulepackVersion = rulepackVersion,
            modelVersion = modelVersion,
            latencyMs = latencyMs,
        )
    }
}
