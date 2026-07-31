package com.scamshield.core.analysis.orchestrator

import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * architecture.md's data-flow diagram: fans every [analyzers] entry out concurrently on
 * `Dispatchers.Default` and applies the 400 ms budget from the same diagram ("coroutine
 * fan-out, 400 ms budget"). Since every analyzer starts at the same instant, giving each one
 * its own 400 ms [withTimeout] is equivalent to one overall 400 ms budget for the whole
 * fan-out, and is far simpler than reasoning about a shared deadline against partially
 * completed jobs.
 *
 * "Any analyzer that times out or throws yields a `Signal.Unavailable` and the fusion policy
 * proceeds without it — it never fails the request" (architecture.md section 6). The
 * classifier is the only analyzer *expected* to be absent (C6); a rule analyzer throwing is not
 * supposed to happen, but degrading the same way rather than failing the whole check is the
 * same C6 principle applied uniformly, not a special case.
 *
 * [dispatcher] defaults to `Dispatchers.Default` in production; tests inject a
 * `TestDispatcher` sharing the test's own scheduler so a timeout can be exercised against
 * virtual time instead of a real 400 ms sleep per test.
 */
class Orchestrator(
    private val analyzers: List<Analyzer>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun run(message: NormalizedMessage): List<Signal> = coroutineScope {
        analyzers
            .map { analyzer -> async(dispatcher) { runOne(analyzer, message) } }
            .awaitAll()
    }

    private suspend fun runOne(analyzer: Analyzer, message: NormalizedMessage): Signal =
        try {
            withTimeout(TIMEOUT_MS) { analyzer.analyze(message) }
        } catch (e: TimeoutCancellationException) {
            Signal.Unavailable(analyzer.id, "timed out after ${TIMEOUT_MS}ms")
        } catch (e: CancellationException) {
            // TimeoutCancellationException (caught above) is itself a CancellationException,
            // so it must be matched first. What reaches this branch is a *real* external
            // cancellation of the orchestrator's own scope (e.g. the caller navigated away),
            // which structured concurrency requires to keep propagating, not be swallowed into
            // a degraded signal.
            throw e
        } catch (e: Exception) {
            Signal.Unavailable(analyzer.id, e.message ?: e::class.simpleName ?: "analyzer threw")
        }

    companion object {
        const val TIMEOUT_MS = 400L
    }
}
