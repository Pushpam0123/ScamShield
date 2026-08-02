package com.scamshield.analyzer.classifier

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Signal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * design.md §6: the on-device ML classifier, run through ONNX Runtime Mobile with a HuggingFace
 * tokenizer. It is the one analyzer expected to sometimes be absent (architecture.md C6): a
 * missing/corrupt model, a session-creation failure, or an inference exception all degrade to
 * [Signal.Unavailable], never a crash — the whole app stays usable on the rule analyzers alone.
 *
 * **Loading is lazy and happens at most once**, on [ioDispatcher], guarded by [loadMutex] so a
 * burst of concurrent [analyze] calls (the orchestrator fans all analyzers out at once) creates
 * exactly one ONNX session. A load that throws is remembered ([loadFailed]) so we don't retry a
 * broken model on every message. The ONNX Runtime session is created with a single intra-op
 * thread (design.md §6 / implementation.md Phase 4) — this runs alongside three other analyzers
 * and a UI, and a phone's few big cores are better left for them than saturated by one matmul.
 *
 * Scope note: this file lands the *loading* half of Phase 4 (day 1 of §12's plan). Tokenization,
 * the calibrated `p_scam` mapping, evidence, and category selection (design.md §6.1/§6.2) are the
 * next step and are why [analyze] still returns [Signal.Unavailable] even once the model loads;
 * the loaded [LoadedModel] holds everything that step needs.
 */
class ClassifierAnalyzer(
    private val assets: ClassifierAssets,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Analyzer {

    override val id: AnalyzerId = AnalyzerId.CLASSIFIER

    private val loadMutex = Mutex()

    @Volatile
    private var loaded: LoadedModel? = null

    @Volatile
    private var loadFailed = false

    /** Everything a single inference needs, created once by [load]. */
    private class LoadedModel(
        val environment: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: HuggingFaceTokenizer,
        val temperature: Float,
    ) : Closeable {
        override fun close() {
            runCatching { session.close() }
            runCatching { tokenizer.close() }
            // OrtEnvironment is a process-wide singleton; do not close it here.
        }
    }

    private suspend fun ensureLoaded(): LoadedModel? {
        loaded?.let { return it }
        if (loadFailed) return null
        return loadMutex.withLock {
            loaded?.let { return it }
            if (loadFailed) return null
            try {
                withContext(ioDispatcher) { load() }.also { loaded = it }
            } catch (t: Throwable) {
                // design.md §6.3: any failure to bring the model up is a degrade, not an error.
                loadFailed = true
                Log.w(TAG, "classifier model failed to load; degrading to unavailable", t)
                null
            }
        }
    }

    private fun load(): LoadedModel {
        val temperature = parseClassifierMeta(assets.readMetaJson()).temperature
        val tokenizer = assets.openTokenizer().use { HuggingFaceTokenizer.newInstance(it, null) }
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val session = assets.openModel().use { environment.createSession(it.readBytes(), options) }
        return LoadedModel(environment, session, tokenizer, temperature)
    }

    override suspend fun analyze(message: NormalizedMessage): Signal {
        val model = ensureLoaded() ?: return Signal.Unavailable(id, "classifier model unavailable")
        // Loading works; tokenization + calibrated scoring + evidence come next (§12 day 2).
        @Suppress("UNUSED_VARIABLE")
        val ready = model
        return Signal.Unavailable(id, "classifier inference not yet implemented")
    }

    private companion object {
        const val TAG = "ClassifierAnalyzer"
    }
}
