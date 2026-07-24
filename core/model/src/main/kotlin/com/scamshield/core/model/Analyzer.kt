package com.scamshield.core.model

/**
 * The single interface every analyzer implements (`architecture.md` §7).
 *
 * It lives in `:core:model` rather than `:core:analysis` so that an analyzer module needs
 * nothing but the domain types to compile. That keeps the dependency graph a strict fan-in
 * to `:core:model` and lets the eval harness instantiate any analyzer in isolation.
 * See DECISIONS.md D-002.
 *
 * Implementations must be stateless with respect to a single [analyze] call and safe to
 * invoke concurrently — the orchestrator fans all four out at once on `Dispatchers.Default`.
 */
interface Analyzer {
    val id: AnalyzerId

    suspend fun analyze(message: NormalizedMessage): Signal
}
