package com.scamshield.analyzer.classifier

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
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
 * Inference itself is the [ClassifierScoring] object's problem, kept pure so it unit-tests on the
 * JVM; this class owns only the native edges — the DJL tokenizer encode and the ONNX Runtime run —
 * which is why they, and only they, are wrapped in the degrade-to-[Signal.Unavailable] catch.
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
        return try {
            withContext(ioDispatcher) { infer(model, message.normalized) }
        } catch (t: Throwable) {
            // design.md §6.3 / architecture.md C6: a bad encode or a failed run degrades this one
            // message to Unavailable; the loaded session stays up for the next call.
            Log.w(TAG, "classifier inference failed; degrading to unavailable", t)
            Signal.Unavailable(id, "classifier inference failed")
        }
    }

    private fun infer(model: LoadedModel, text: String): Signal {
        val (binary, category) = runModel(model, text)
        return ClassifierScoring.buildSignal(binary, category, model.temperature)
    }

    /** The two raw logit heads (`binary_logits`, `category_logits`) for one message. */
    private fun runModel(model: LoadedModel, text: String): Pair<FloatArray, FloatArray> {
        val encoded = ClassifierScoring.pack(model.tokenizer.encode(text).ids)
        if (encoded.truncated) Log.d(TAG, "message exceeded ${ClassifierScoring.SEQ_LEN} tokens; tail truncated")

        // `arrayOf(row)` is a `long[1][SEQ_LEN]` — the batch-of-one shape the graph expects.
        val inputIds = OnnxTensor.createTensor(model.environment, arrayOf(encoded.inputIds))
        val attentionMask = OnnxTensor.createTensor(model.environment, arrayOf(encoded.attentionMask))
        inputIds.use { ids ->
            attentionMask.use { mask ->
                model.session.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { result ->
                    return floatRow(result, "binary_logits") to floatRow(result, "category_logits")
                }
            }
        }
    }

    /**
     * Raw, un-calibrated `p_scam` = `softmax(binary_logits)[1]` for one message, or null if the
     * model can't be brought up. Exists solely for the instrumented parity gate (design.md §9.5),
     * which compares against the Python ONNX output — computed the same way, temperature-free — so
     * a genuine model/tokenizer discrepancy isn't masked by the shared temperature constant.
     */
    internal suspend fun rawScamProbabilityForParity(text: String): Float? {
        val model = ensureLoaded() ?: return null
        return withContext(ioDispatcher) {
            ClassifierScoring.softmax(runModel(model, text).first)[1]
        }
    }

    /** Pull the first (batch-of-one) row out of a `[1, N]` float output tensor by name. */
    private fun floatRow(result: OrtSession.Result, name: String): FloatArray {
        val tensor = result.get(name).orElseThrow { IllegalStateException("model output '$name' missing") }
        @Suppress("UNCHECKED_CAST")
        return (tensor.value as Array<FloatArray>)[0]
    }

    private companion object {
        const val TAG = "ClassifierAnalyzer"
    }
}
