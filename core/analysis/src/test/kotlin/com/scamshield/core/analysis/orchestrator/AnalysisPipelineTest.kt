package com.scamshield.core.analysis.orchestrator

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Signal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AnalysisPipelineTest {

    private class FakeAnalyzer(override val id: AnalyzerId, private val weight: Float) : Analyzer {
        override suspend fun analyze(message: NormalizedMessage) = Signal.Scored(id, weight)
    }

    private fun message() = NormalizedMessage(
        id = MessageId("t"), original = "hi", normalized = "hi",
        urls = emptyList(), senderHint = null, detectedLanguage = Language.EN, brandClaims = emptyList(),
    )

    @Test
    fun `analyze runs the orchestrator, fuses the result, and stamps in the rule pack version`() = runTest {
        val pipeline = AnalysisPipeline(
            orchestrator = Orchestrator(listOf(FakeAnalyzer(AnalyzerId.URL, 0f))),
            rulepackVersion = "v1-integration-test",
        )
        val result = pipeline.analyze(message())
        assertThat(result.rulepackVersion).isEqualTo("v1-integration-test")
        assertThat(result.modelVersion).isNull()
        assertThat(result.latencyMs).isAtLeast(0)
        assertThat(result.messageId).isEqualTo(MessageId("t"))
    }
}
