package com.scamshield.core.data.rulepack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format mirrors of `rulepack/build_rulepack.py`'s emitted JSON (the `_comment`-stripped,
 * minified form written to `app/src/main/assets/rulepack/v1/`), one file per data class.
 *
 * These stay separate from `:core:model`'s `RulePack`/`BankEntry`/`PatternRule` -- D-004 keeps
 * the parsed domain types free of any serialization framework so analyzer modules never need
 * one either. [RulePackJsonParser] is the only place that converts between the two shapes.
 */
@Serializable
internal data class BanksFileDto(
    @SerialName("schema_version") val schemaVersion: Int,
    val brands: List<BrandDto>,
)

@Serializable
internal data class BrandDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val aliases: List<String>,
    val domains: List<String>,
    @SerialName("dlt_headers") val dltHeaders: List<String>,
)

@Serializable
internal data class ShortenersFileDto(
    @SerialName("schema_version") val schemaVersion: Int,
    val shorteners: List<String>,
    @SerialName("brand_operated") val brandOperated: Map<String, String> = emptyMap(),
)

@Serializable
internal data class TyposquatFileDto(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("single_char_folds") val singleCharFolds: Map<String, String>,
    @SerialName("sequence_folds") val sequenceFolds: Map<String, String>,
    val distance: DistanceDto,
    @SerialName("suspicious_tlds") val suspiciousTlds: List<String>,
)

@Serializable
internal data class DistanceDto(
    @SerialName("short_label_max_length") val shortLabelMaxLength: Int,
    @SerialName("short_label_distance") val shortLabelDistance: Int,
    @SerialName("long_label_distance") val longLabelDistance: Int,
)

@Serializable
internal data class PatternsFileDto(
    @SerialName("schema_version") val schemaVersion: Int,
    val patterns: List<PatternDto>,
)

@Serializable
internal data class PatternDto(
    val id: String,
    val lang: List<String>,
    val pattern: String,
    @SerialName("suppress_if") val suppressIf: String? = null,
    val evidence: String,
    val severity: String,
    val weight: Float,
    @SerialName("category_hints") val categoryHints: Map<String, Float> = emptyMap(),
)

@Serializable
internal data class MetaFileDto(
    @SerialName("pack_version") val packVersion: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
)
