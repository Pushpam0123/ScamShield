package com.scamshield.analyzer.classifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import kotlin.math.abs

/**
 * design.md §9.5's headline Phase 4 acceptance row — the on-device parity gate:
 *
 *   "the same samples through the Android classifier match the ONNX Python output within 0.02.
 *    A mismatch here is almost always a tokenizer discrepancy — fix the tokenizer, do not adjust
 *    thresholds."
 *
 * The fixture (`assets/model/parity_fixture.json`) is written by `ml/export_parity_fixture.py`
 * from the *same* `model.int8.onnx` + `tokenizer.json` this test loads, so any delta above 0.02
 * is a real cross-runtime disagreement — the DJL tokenizer diverging from the Rust `tokenizers`
 * one, or ORT Mobile's INT8 kernels from ORT desktop's — not noise. This also finally discharges
 * D-009: the DJL tokenizer had never been byte-confirmed against the training `tokenizer.json`.
 *
 * Both sides use raw `softmax(binary_logits)[1]`, temperature-free (see
 * [ClassifierAnalyzer.rawScamProbabilityForParity]).
 *
 * What this run verifies: the toy model's `WordLevel` tokenizer (§9/§10). A real MuRIL-family run
 * would ship a WordPiece tokenizer and must re-clear this gate — the assertion is model-agnostic,
 * only the bundled assets change.
 *
 * The assets are gitignored generated output; on a checkout without a trained model the test
 * assumes-out rather than failing (architecture.md C6 — absence is a valid state).
 */
@RunWith(AndroidJUnit4::class)
class ClassifierParityTest {

    @Serializable
    private data class Fixture(
        val model: String,
        @SerialName("max_seq_len") val maxSeqLen: Int,
        @SerialName("max_delta") val maxDelta: Double,
        val samples: List<Sample>,
    )

    @Serializable
    private data class Sample(val text: String, @SerialName("p_scam") val pScam: Double)

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the classifier's three asset files out of the *test* APK's own assets. */
    private class TestApkAssets(private val assets: android.content.res.AssetManager) : ClassifierAssets {
        override fun openModel(): InputStream = assets.open("model/model.onnx")
        override fun openTokenizer(): InputStream = assets.open("model/tokenizer.json")
        override fun readMetaJson(): String = assets.open("model/meta.json").bufferedReader().use { it.readText() }
    }

    @Test
    fun androidInferenceMatchesPythonOnnxWithinParityDelta() = runTest {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixtureText = runCatching { assets.open("model/parity_fixture.json").bufferedReader().use { it.readText() } }
            .getOrNull()
        // No model bundled into the test assets → nothing to parity-check. Run
        // `cd ml && make export parity-fixture` then `:analyzer:classifier:copyModelTestAssets`.
        assumeTrue("parity fixture absent; skipping (no trained model bundled)", fixtureText != null)

        val fixture = json.decodeFromString(Fixture.serializer(), fixtureText!!)
        assertThat(fixture.samples).isNotEmpty()

        val analyzer = ClassifierAnalyzer(TestApkAssets(assets))
        var worst = 0.0
        var worstText = ""
        for (sample in fixture.samples) {
            val android = analyzer.rawScamProbabilityForParity(sample.text)
            assertThat(android).isNotNull()
            val delta = abs(android!! - sample.pScam)
            if (delta > worst) {
                worst = delta
                worstText = sample.text
            }
        }

        assertThat(worst).isLessThan(fixture.maxDelta)
        // Surfaced on failure so the (tokenizer) culprit is one message, not a haystack.
        println("parity gate: worst |Δp_scam| = $worst over ${fixture.samples.size} samples (\"$worstText\")")
    }
}
