package com.scamshield.core.model

/**
 * Every reason ScamShield is able to give a user.
 *
 * This enum is the contract between the analyzers and `:core:explain`: each constant
 * has exactly one explanation template per language. Adding a constant without adding
 * its template is a build failure, not a runtime blank.
 */
enum class EvidenceType {
    // --- URL analyzer (design.md §3) ---
    DOMAIN_VERY_NEW,
    TYPOSQUAT_OF_KNOWN_BRAND,
    HOMOGRAPH_CHARACTERS,
    URL_SHORTENER,
    LINK_TEXT_MISMATCH,
    IP_ADDRESS_HOST,
    SUSPICIOUS_TLD,
    NON_HTTPS_CREDENTIAL_PAGE,

    // --- Sender analyzer (design.md §4) ---
    BRAND_CLAIM_WITHOUT_DLT_HEADER,
    UNREGISTERED_NUMERIC_SENDER,
    DLT_HEADER_MISMATCH,

    // --- Pattern analyzer (design.md §5) ---
    OTP_SOLICITATION,
    UPI_COLLECT_FRAMING,
    URGENCY_DEADLINE,
    THREAT_OF_LOSS,
    UNREALISTIC_REWARD,
    PREMIUM_RATE_NUMBER,
    CRYPTO_WALLET_ADDRESS,
    ADVANCE_FEE_REQUEST,

    // --- Classifier (design.md §6) ---
    MODEL_HIGH_SCAM_SCORE,
    MODEL_LOW_SCAM_SCORE,
}

/**
 * One human-checkable reason behind a verdict. `architecture.md` G2 requires at least
 * one of these on every result.
 *
 * [slots] carries template fill values rather than pre-rendered text, because rendering
 * happens in `:core:explain` against the user's chosen language — an analyzer must never
 * produce a user-visible string (constraint C5).
 */
data class Evidence(
    val type: EvidenceType,
    val severity: Severity,
    val slots: Map<String, String> = emptyMap(),
    /** Span in [NormalizedMessage.original] the UI should highlight, when there is one. */
    val highlightSpan: IntRange? = null,
)
