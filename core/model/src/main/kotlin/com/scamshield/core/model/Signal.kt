package com.scamshield.core.model

/**
 * What one analyzer returns.
 *
 * [Unavailable] is a first-class outcome, not an error: `architecture.md` §6 requires that a
 * timed-out or throwing analyzer degrade the result rather than fail the request, and C6
 * requires the whole app to work with the classifier permanently absent.
 */
sealed interface Signal {
    val analyzerId: AnalyzerId

    data class Scored(
        override val analyzerId: AnalyzerId,
        /** This analyzer's own 0.0..1.0 read on how scam-like the message is. */
        val scamWeight: Float,
        val categoryHints: Map<ScamCategory, Float> = emptyMap(),
        val evidence: List<Evidence> = emptyList(),
        /**
         * Bypasses weighted fusion entirely.
         *
         * Only the URL analyzer (`design.md` §3.5) and the sender analyzer (§4.2) may set
         * this, and only on CRITICAL evidence that coincides with a brand claim. The fusion
         * policy rejects it from any other analyzer.
         */
        val forceVerdict: Verdict? = null,
    ) : Signal

    data class Unavailable(
        override val analyzerId: AnalyzerId,
        val reason: String,
    ) : Signal
}
