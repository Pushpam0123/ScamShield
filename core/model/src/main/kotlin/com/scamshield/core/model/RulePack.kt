package com.scamshield.core.model

/**
 * Rule-pack domain types.
 *
 * `architecture.md` §11: rules are *data*, because scam infrastructure changes weekly while
 * the app ships monthly. The parsed representation lives here in `:core:model` — with no
 * serialization framework attached — so that analyzer modules can consume a pack without
 * depending on `:core:data`, which is the only module permitted to declare a network or
 * storage dependency (§10.1). Parsing and validation happen in `:core:data`.
 * See DECISIONS.md D-004.
 */
data class RulePack(
    val meta: PackMeta,
    val banks: List<BankEntry>,
    val shorteners: Set<String>,
    val confusables: ConfusableTable,
    val patterns: List<PatternRule>,
    val suspiciousTlds: Set<String>,
)

data class PackMeta(
    /** Pack version, independent of the app version. Recorded on every [AnalysisResult]. */
    val version: String,
    val generatedAt: String,
    val schemaVersion: Int,
)

/**
 * One organisation a scam message might impersonate: a bank, wallet, telecom, or
 * government portal.
 */
data class BankEntry(
    /** Stable key, e.g. `sbi`. */
    val id: String,
    /** Name shown in explanations, e.g. `State Bank of India`. */
    val displayName: String,
    /** Lowercased strings that count as claiming to be this brand, e.g. `state bank`, `sbi`. */
    val aliases: List<String>,
    /** Registrable domains the brand genuinely uses, e.g. `onlinesbi.sbi`. */
    val domains: List<String>,
    /** DLT sender-ID bodies this brand is registered under, e.g. `SBIINB`, `SBIUPI`. */
    val dltHeaders: List<String>,
)

/**
 * Confusable-character folding table plus the Damerau-Levenshtein thresholds used by
 * typosquat detection (`design.md` §3.2, §3.3).
 */
data class ConfusableTable(
    /** Single-codepoint folds, e.g. Cyrillic `а` -> `a`, fullwidth `０` -> `0`. */
    val singleCharFolds: Map<Char, Char>,
    /** Multi-character render-level folds, e.g. `rn` -> `m`, applied after single-char folding. */
    val sequenceFolds: Map<String, String>,
    /** Edit distance allowed for a brand label of length <= [shortLabelMaxLength]. */
    val shortLabelDistance: Int,
    val longLabelDistance: Int,
    val shortLabelMaxLength: Int,
)

/**
 * One structural regex from `patterns.json`.
 *
 * The pattern analyzer never forces a verdict — it contributes weight and category hints
 * only, and its total contribution is capped (`design.md` §5).
 */
data class PatternRule(
    val id: String,
    val languages: Set<Language>,
    val regex: Regex,
    /**
     * If this matches the message, [regex] is ignored and the rule contributes nothing.
     *
     * This exists for one specific and very expensive failure: the most common genuine bank
     * SMS in India is an OTP delivery that says *"do not share this OTP with anyone"*, and a
     * plain `share.*otp` pattern fires on it. Encoding the exception as its own field rather
     * than as a negative lookahead inside [regex] keeps it reviewable — a reader can see what
     * a rule refuses to fire on without parsing regex. See DECISIONS.md D-010.
     */
    val suppressIf: Regex? = null,
    val evidence: EvidenceType,
    val severity: Severity,
    val weight: Float,
    val categoryHints: Map<ScamCategory, Float>,
)

/**
 * Offline domain reputation (`design.md` §3.1).
 *
 * The critical semantic: absence from the index means *unknown*, never *new*. A domain the
 * snapshot has not seen may simply be a legitimate small business, so the offline path may
 * emit `INFO` but never `CRITICAL`.
 */
interface DomainReputationIndex {
    /** True if the domain was observed as established (>= 180 days old) at pack build time. */
    fun isEstablished(registrableDomain: String): Boolean

    /** True for the explicit allowlist of Indian bank, government, and major-service domains. */
    fun isAllowlisted(registrableDomain: String): Boolean
}

/**
 * Public Suffix List lookup. `design.md` §2.2 forbids approximating eTLD+1 as "the last two
 * labels" — `.co.in` and `.gov.in` break that immediately, and both appear constantly in
 * Indian phishing.
 */
interface PublicSuffixList {
    /** Returns eTLD+1 for [host], or null if [host] is an IP literal or has no registrable part. */
    fun registrableDomain(host: String): String?
}
