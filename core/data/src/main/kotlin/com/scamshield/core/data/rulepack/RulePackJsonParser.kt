package com.scamshield.core.data.rulepack

import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.ConfusableTable
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.Language
import com.scamshield.core.model.PackMeta
import com.scamshield.core.model.PatternRule
import com.scamshield.core.model.RulePack
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Decodes the four JSON files authored under `rulepack/src` (as emitted by `build_rulepack.py`
 * to `app/src/main/assets/rulepack/v1/`) plus `meta.json` into `:core:model`'s [RulePack].
 *
 * Pure and Android-free by design: every function here takes the raw file text and returns a
 * domain object, so [RulePackLoader] is the only class that touches actual asset I/O, and this
 * class is unit-testable with plain JSON string fixtures.
 *
 * Throws (`SerializationException`, or [RulePackValidationException] for the schema-version
 * checks) on any malformed or unrecognized input, on purpose -- `architecture.md` section 11
 * requires the app to "never run with a partially-loaded pack," so [RulePackLoader] must be
 * able to catch a single exception here and fall back to [DefaultRulePack] wholesale rather
 * than silently coping with (say) three-quarters of a pack.
 *
 * `build_rulepack.py` already runs full JSON-Schema + cross-file validation before a pack ever
 * reaches an asset; this class re-checks only `schema_version`, since that is the one thing a
 * future incompatible pack format could get past kotlinx.serialization's structural decoding
 * (every other schema rule -- alias precision, duplicate ids, valid enum values -- either has
 * no runtime consequence beyond what decoding already enforces, or is exactly the kind of
 * full JSON-Schema re-validation this module deliberately does not reimplement; see this
 * class's own doc comment on scope).
 */
internal object RulePackJsonParser {

    private const val EXPECTED_SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parseBanks(text: String): List<BankEntry> {
        val dto = json.decodeFromString<BanksFileDto>(text)
        checkSchemaVersion("banks.json", dto.schemaVersion)
        return dto.brands.map { brand ->
            BankEntry(
                id = brand.id,
                displayName = brand.displayName,
                aliases = brand.aliases,
                domains = brand.domains,
                dltHeaders = brand.dltHeaders,
            )
        }
    }

    fun parseShorteners(text: String): Pair<Set<String>, Map<String, String>> {
        val dto = json.decodeFromString<ShortenersFileDto>(text)
        checkSchemaVersion("shorteners.json", dto.schemaVersion)
        return dto.shorteners.toSet() to dto.brandOperated
    }

    fun parseTyposquat(text: String): Pair<ConfusableTable, Set<String>> {
        val dto = json.decodeFromString<TyposquatFileDto>(text)
        checkSchemaVersion("typosquat.json", dto.schemaVersion)
        val table = ConfusableTable(
            // `:core:model`'s ConfusableTable.singleCharFolds is Map<Char, Char> -- one UTF-16
            // code unit -- but typosquat.json's schema measures "one character" in Unicode code
            // points the way Python's `len()` does (build_rulepack.py's validator is Python).
            // A handful of authored folds (the Mathematical Sans-Serif Bold confusables, e.g.
            // U+1D5EE) are single code points outside the Basic Multilingual Plane, which Kotlin
            // represents as a two-Char surrogate pair -- one code point, but not one Char. Those
            // few entries cannot be represented in the current Char-keyed model and are dropped
            // rather than crashing the whole pack load over eight fold entries out of ~100.
            singleCharFolds = dto.singleCharFolds.entries
                .filter { (k, v) -> k.length == 1 && v.length == 1 }
                .associate { (k, v) -> k.single() to v.single() },
            sequenceFolds = dto.sequenceFolds,
            shortLabelDistance = dto.distance.shortLabelDistance,
            longLabelDistance = dto.distance.longLabelDistance,
            shortLabelMaxLength = dto.distance.shortLabelMaxLength,
        )
        return table to dto.suspiciousTlds.toSet()
    }

    fun parsePatterns(text: String): List<PatternRule> {
        val dto = json.decodeFromString<PatternsFileDto>(text)
        checkSchemaVersion("patterns.json", dto.schemaVersion)
        return dto.patterns.map { rule ->
            PatternRule(
                id = rule.id,
                languages = rule.lang.map { Language.valueOf(it) }.toSet(),
                regex = Regex(rule.pattern),
                suppressIf = rule.suppressIf?.let { Regex(it) },
                evidence = EvidenceType.valueOf(rule.evidence),
                severity = Severity.valueOf(rule.severity),
                weight = rule.weight,
                categoryHints = rule.categoryHints.mapKeys { (k, _) -> ScamCategory.valueOf(k) },
            )
        }
    }

    fun parseMeta(text: String): PackMeta {
        val dto = json.decodeFromString<MetaFileDto>(text)
        checkSchemaVersion("meta.json", dto.schemaVersion)
        return PackMeta(
            version = dto.packVersion,
            generatedAt = dto.generatedAt,
            schemaVersion = dto.schemaVersion,
        )
    }

    /**
     * Assembles the four parsed files into a [RulePack]. [reputationIndex] and
     * [publicSuffixList] are not part of this -- [RulePackLoader] wires those in separately,
     * since one degrades to `null` and the other to an empty rule list on their own failure
     * paths rather than invalidating the whole pack (see that class's doc comment).
     */
    fun assemble(
        meta: PackMeta,
        banks: List<BankEntry>,
        shorteners: Set<String>,
        shortenerBrandOperated: Map<String, String>,
        confusables: ConfusableTable,
        suspiciousTlds: Set<String>,
        patterns: List<PatternRule>,
    ) = RulePack(
        meta = meta,
        banks = banks,
        shorteners = shorteners,
        shortenerBrandOperated = shortenerBrandOperated,
        confusables = confusables,
        patterns = patterns,
        suspiciousTlds = suspiciousTlds,
    )

    private fun checkSchemaVersion(fileName: String, actual: Int) {
        if (actual != EXPECTED_SCHEMA_VERSION) {
            throw RulePackValidationException(
                "$fileName has schema_version $actual, expected $EXPECTED_SCHEMA_VERSION",
            )
        }
    }
}

internal class RulePackValidationException(message: String) : Exception(message)
