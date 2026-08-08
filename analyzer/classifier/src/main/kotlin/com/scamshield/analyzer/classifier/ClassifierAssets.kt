package com.scamshield.analyzer.classifier

import android.content.Context
import java.io.InputStream

/**
 * How [ClassifierAnalyzer] reaches its three on-device asset files, behind an interface so the
 * analyzer unit-tests on the JVM with a fake (the real ONNX/DJL loads need native libraries).
 *
 * The files are produced by `ml/` and copied into `assets/model/` by `:app`'s `copyModelAssets`
 * task; they're gitignored generated output, and when absent every method throws so the analyzer
 * degrades to `Signal.Unavailable` (architecture.md C6).
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
 * The production [ClassifierAssets], reading from the app's packaged assets. Kept out of
 * [ClassifierAnalyzer] itself so the analyzer has no Android-asset dependency blocking JVM tests.
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
