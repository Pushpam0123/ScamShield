package com.scamshield.analyzer.url

/**
 * The opt-in RDAP path of design.md section 3.1. Age < 30 days -> `DOMAIN_VERY_NEW` at
 * `CRITICAL`, instead of the offline path's `INFO`.
 *
 * Deliberately just an interface here: this module carries the `scamshield.privacy-boundary`
 * Gradle plugin (architecture.md section 10.1), which fails the build on any resolved network
 * dependency, so the actual RDAP HTTP call cannot live in `:analyzer:url` at all. The
 * implementation belongs in `:core:data` -- the one module permitted a network dependency --
 * and is wired in at the `:app` layer, gated by the Settings toggle that ships in Phase 5
 * (off by default). [UrlAnalyzer] accepts this as a nullable dependency and works correctly
 * with it absent, matching architecture.md C6's spirit that a piece being unavailable must
 * degrade gracefully, not break the analyzer.
 */
interface OnlineDomainLookup {
    /** Returns age in days, or null if the lookup failed, timed out, or found nothing. */
    suspend fun ageDays(registrableDomain: String): Int?
}
