package com.scamshield.analyzer.classifier

import android.content.Context
import java.io.InputStream

/**
 * How [ClassifierAnalyzer] reaches its three on-device asset files, abstracted behind an
 * interface so the analyzer can be unit-tested on the JVM with a fake that supplies bytes (or
 * throws) — the real ONNX Runtime / DJL tokenizer loads need native libraries and only run in an
 * instrumented test (design.md §6, Phase 4 parity gate).
 *
 * The three files are produced by the `ml/` pipeline and copied into `assets/model/` by
 * `:app`'s `copyModelAssets` task (see `app/build.gradle.kts`). They are gitignored generated
 * output; when they're absent, every method here throws, and the analyzer degrades to
 * `Signal.Unavailable` (architecture.md C6) rather than failing the app.
 */
interface ClassifierAssets {
    /** The quantized ONNX model bytes (`assets/model/model.onnx`). */
    fun openModel(): InputStream

    /** The HuggingFace `tokenizer.json` (`assets/model/tokenizer.json`). */
    fun openTokenizer(): InputStream

    /** The calibration `meta.json` text (`assets/model/meta.json`), carrying the temperature. */
    fun readMetaJson(): String
}

/**
 * The production [ClassifierAssets], reading from the app's packaged assets. Constructed in
 * `:app`'s Hilt graph (Phase 4) with the application [Context]; kept out of [ClassifierAnalyzer]
 * itself so the analyzer has no Android-asset dependency to stand in the way of JVM unit tests.
 */
class AndroidClassifierAssets(
    private val context: Context,
    private val assetDir: String = "model",
) : ClassifierAssets {
    override fun openModel(): InputStream = context.assets.open("$assetDir/model.onnx")

    override fun openTokenizer(): InputStream = context.assets.open("$assetDir/tokenizer.json")

    override fun readMetaJson(): String =
        context.assets.open("$assetDir/meta.json").bufferedReader().use { it.readText() }
}
