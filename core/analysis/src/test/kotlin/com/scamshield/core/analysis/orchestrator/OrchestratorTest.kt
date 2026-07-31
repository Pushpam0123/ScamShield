package com.scamshield.core.analysis.orchestrator

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Signal
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OrchestratorTest {

    private fun message() = NormalizedMessage(
        id = MessageId("t"),
        original = "hello",
        normalized = "hello",
        urls = emptyList(),
        senderHint = null,
        detectedLanguage = Language.EN,
        brandClaims = emptyList(),
    )

    private class FakeAnalyzer(
        override val id: AnalyzerId,
        private val behavior: suspend () -> Signal,
    ) : Analyzer {
        override suspend fun analyze(message: NormalizedMessage): Signal = behavior()
    }

    private fun scored(id: AnalyzerId, weight: Float = 0.5f) = FakeAnalyzer(id) {
        Signal.Scored(analyzerId = id, scamWeight = weight)
    }

    @Test
    fun `all analyzers run and their signals come back`() = runTest {
        val orchestrator = Orchestrator(
            listOf(scored(AnalyzerId.URL), scored(AnalyzerId.SENDER), scored(AnalyzerId.PATTERN)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val signals = orchestrator.run(message())
        assertThat(signals.map { it.analyzerId }).containsExactly(AnalyzerId.URL, AnalyzerId.SENDER, AnalyzerId.PATTERN)
        assertThat(signals).hasSize(3)
    }

    @Test
    fun `a throwing analyzer degrades to Unavailable instead of failing the whole run`() = runTest {
        val throwing = FakeAnalyzer(AnalyzerId.URL) { throw IllegalStateException("boom") }
        val orchestrator = Orchestrator(
            listOf(throwing, scored(AnalyzerId.SENDER)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val signals = orchestrator.run(message())
        val urlSignal = signals.first { it.analyzerId == AnalyzerId.URL }
        assertThat(urlSignal).isInstanceOf(Signal.Unavailable::class.java)
        assertThat((urlSignal as Signal.Unavailable).reason).contains("boom")
        assertThat(signals.first { it.analyzerId == AnalyzerId.SENDER }).isInstanceOf(Signal.Scored::class.java)
    }

    @Test
    fun `an analyzer that exceeds the timeout degrades to Unavailable`() = runTest {
        val slow = FakeAnalyzer(AnalyzerId.PATTERN) {
            delay(Orchestrator.TIMEOUT_MS + 1000)
            Signal.Scored(analyzerId = AnalyzerId.PATTERN, scamWeight = 0.9f)
        }
        val orchestrator = Orchestrator(listOf(slow), dispatcher = StandardTestDispatcher(testScheduler))
        val signals = orchestrator.run(message())
        assertThat(signals[0]).isInstanceOf(Signal.Unavailable::class.java)
        assertThat((signals[0] as Signal.Unavailable).reason).contains("timed out")
    }

    @Test
    fun `a fast analyzer under the timeout still succeeds`() = runTest {
        val fast = FakeAnalyzer(AnalyzerId.URL) {
            delay(Orchestrator.TIMEOUT_MS - 50)
            Signal.Scored(analyzerId = AnalyzerId.URL, scamWeight = 0.2f)
        }
        val orchestrator = Orchestrator(listOf(fast), dispatcher = StandardTestDispatcher(testScheduler))
        val signals = orchestrator.run(message())
        assertThat(signals[0]).isInstanceOf(Signal.Scored::class.java)
    }

    @Test
    fun `an empty analyzer list produces an empty signal list`() = runTest {
        val orchestrator = Orchestrator(emptyList(), dispatcher = StandardTestDispatcher(testScheduler))
        assertThat(orchestrator.run(message())).isEmpty()
    }
}
