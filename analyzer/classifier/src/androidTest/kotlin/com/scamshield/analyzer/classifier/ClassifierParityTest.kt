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
 * design.md §9.5's headline Phase 4 acceptance row: the same samples through the Android classifier
 * match the ONNX Python output within 0.02 ("a mismatch is almost always a tokenizer discrepancy —
 * fix the tokenizer, not the thresholds").
 *
 * The fixture is written by `ml/export_parity_fixture.py` from the *same* model + tokenizer this
 * test loads, so any delta > 0.02 is a real cross-runtime disagreement (DJL vs Rust tokenizers, or
 * ORT Mobile vs desktop INT8), not noise. This also discharges D-009 (the DJL tokenizer had never
 * been byte-confirmed against the training `tokenizer.json`). Both sides use raw, temperature-free
 * `softmax(binary_logits)[1]`.
 *
 * Verifies the toy model's `WordLevel` tokenizer; a real WordPiece model must re-clear the same
 * (model-agnostic) gate. Assets are gitignored — without a bundled model the test assumes-out (C6).
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
