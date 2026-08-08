package com.scamshield.analyzer.classifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The subset of `meta.json` (written by `ml/calibrate.py`) this analyzer needs. [temperature] `T`
 * is fitted on the validation set (design.md §6.2); `p_scam` is only meaningful after dividing the
 * binary logits by it, so a missing/unparseable temperature is a hard load failure, not a default.
 *
 * [modelVersion] is optional provenance for [com.scamshield.core.model.AnalysisResult]; the toy
 * `meta.json` carries none, so it stays null until a real model writes one.
 *
 * [json] ignores the diagnostic fields (`nll_before`, `nll_after`, `val_rows`) rather than failing.
 */
@Serializable
data class ClassifierMeta(
    val temperature: Float,
    @SerialName("model_version") val modelVersion: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

fun parseClassifierMeta(text: String): ClassifierMeta = json.decodeFromString(ClassifierMeta.serializer(), text)
