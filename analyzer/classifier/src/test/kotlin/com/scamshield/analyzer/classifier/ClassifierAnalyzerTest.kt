package com.scamshield.analyzer.classifier

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Signal
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * JVM-level tests for the parts of the classifier that don't need the ONNX Runtime / DJL native
 * libraries — chiefly the architecture.md C6 contract: a message must still get a (degraded)
 * result when the model can't be brought up, never an exception. The real inference path is
 * exercised in an instrumented test on the emulator (§12 day 3, the parity gate).
 */
class ClassifierAnalyzerTest {

    private fun message(text: String = "hello there") = NormalizedMessage(
        id = MessageId("t1"),
        original = text,
        normalized = text,
        urls = emptyList(),
        senderHint = null,
        detectedLanguage = Language.EN,
        brandClaims = emptyList(),
    )

    /** Assets whose files are all missing — every accessor throws, as absent assets would. */
    private object MissingAssets : ClassifierAssets {
        override fun openModel(): InputStream = throw IOException("no model asset")
        override fun openTokenizer(): InputStream = throw IOException("no tokenizer asset")
        override fun readMetaJson(): String = throw IOException("no meta asset")
    }

    /** Assets present but the meta is corrupt — a load failure that surfaces before any native call. */
    private object CorruptMetaAssets : ClassifierAssets {
        override fun openModel(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun openTokenizer(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun readMetaJson(): String = "{ this is not valid json"
    }

    @Test
    fun `id is CLASSIFIER`() {
        assertThat(ClassifierAnalyzer(MissingAssets).id).isEqualTo(AnalyzerId.CLASSIFIER)
    }

    @Test
    fun `missing model degrades to Unavailable, never throws`() = runTest {
        val signal = ClassifierAnalyzer(MissingAssets).analyze(message())
        assertThat(signal).isInstanceOf(Signal.Unavailable::class.java)
        assertThat(signal.analyzerId).isEqualTo(AnalyzerId.CLASSIFIER)
    }

    @Test
    fun `corrupt meta degrades to Unavailable, never throws`() = runTest {
        val signal = ClassifierAnalyzer(CorruptMetaAssets).analyze(message())
        assertThat(signal).isInstanceOf(Signal.Unavailable::class.java)
    }

    @Test
    fun `a failed load is not retried on the next call`() = runTest {
        val counting = object : ClassifierAssets {
            var metaReads = 0
            override fun openModel(): InputStream = throw IOException("no model")
            override fun openTokenizer(): InputStream = throw IOException("no tokenizer")
            override fun readMetaJson(): String {
                metaReads++
                throw IOException("no meta")
            }
        }
        val analyzer = ClassifierAnalyzer(counting)
        analyzer.analyze(message())
        analyzer.analyze(message())
        // First call attempts and fails the load; the second must short-circuit on loadFailed.
        assertThat(counting.metaReads).isEqualTo(1)
    }
}
