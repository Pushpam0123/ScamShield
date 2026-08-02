package com.scamshield.analyzer.classifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The subset of `meta.json` (written by `ml/calibrate.py`) this analyzer needs: the temperature
 * `T` fitted on the validation set (design.md §6.2). `p_scam` is only meaningful after dividing
 * the binary logits by `T` — the fusion policy's thresholds assume a calibrated probability, so
 * a missing or unparseable temperature is a hard load failure, not a silent default to 1.0.
 *
 * `meta.json` also carries diagnostic fields (`nll_before`, `nll_after`, `val_rows`) this
 * doesn't model; [json] ignores them rather than failing to parse.
 */
@Serializable
data class ClassifierMeta(val temperature: Float)

private val json = Json { ignoreUnknownKeys = true }

fun parseClassifierMeta(text: String): ClassifierMeta = json.decodeFromString(ClassifierMeta.serializer(), text)
