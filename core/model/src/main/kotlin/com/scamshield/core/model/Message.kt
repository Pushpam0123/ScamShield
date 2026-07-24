package com.scamshield.core.model

import java.time.Instant

/** How the message reached the app. `architecture.md` §2 forbids any SMS-interception source. */
enum class MessageSource {
    SHARE_SHEET,
    CLIPBOARD,
    MANUAL,
}

/** A message exactly as it arrived, before any processing. */
data class RawMessage(
    val id: MessageId,
    val text: String,
    /** Sender ID if the sharing app supplied one, e.g. `VM-SBIINB` or a 10-digit number. */
    val senderHint: String?,
    val source: MessageSource,
    val receivedAt: Instant,
)

/**
 * Scripts ScamShield can recognise. Selects the explanation string set only —
 * `design.md` §2.3: language is deliberately *not* a classifier feature, because
 * hard-routing by language throws away code-mixed signal.
 */
enum class Language {
    EN,
    HI,
    /** Romanized Hindi ("aapka khata band ho jayega") — the dominant real-world input class. */
    HI_LATN,
    BN,
    TA,
    TE,
    MR,
    UNKNOWN,
}

/**
 * One URL found in the message.
 *
 * Both the written form and the derived form are retained: homograph and
 * link-text-mismatch detection operate on what was *written*, not on what it
 * normalises to.
 */
data class ExtractedUrl(
    val raw: String,
    val scheme: String?,
    /** Host as written, pre-punycode-decoding. */
    val host: String,
    /** eTLD+1 via the Public Suffix List. Null when the host is an IP literal or unparseable. */
    val registrableDomain: String?,
    val path: String?,
    val isPunycode: Boolean,
    /** Anchor text, when the source was rich text. Drives `LINK_TEXT_MISMATCH`. */
    val displayText: String?,
    /** Offsets into [NormalizedMessage.original], for UI highlighting. */
    val spanStart: Int,
    val spanEnd: Int,
)

/**
 * An organisation the message claims to be from.
 *
 * `design.md` §4.1: brand-claim extraction is a shared pure primitive, and its result
 * is carried on [NormalizedMessage] so that the URL and sender analyzers can both consume
 * it without depending on each other (`architecture.md` §5 forbids analyzer-to-analyzer
 * dependencies). See DECISIONS.md D-003.
 */
data class BrandClaim(
    /** Key into `banks.json`, e.g. `sbi`. */
    val brandId: String,
    /** Human-readable name for explanation slots, e.g. `State Bank of India`. */
    val displayName: String,
    /** The alias that actually matched, e.g. `state bank`. */
    val matchedAlias: String,
    /** Span in [NormalizedMessage.original] where the claim appears. */
    val spanStart: Int,
    val spanEnd: Int,
)

/**
 * The message after `design.md` §2 normalization.
 *
 * [original] is never discarded. Every analyzer that reasons about how something *looks*
 * to a human — homographs, mixed script, anchor text — must read [original].
 */
data class NormalizedMessage(
    val id: MessageId,
    /** Verbatim input. Required for homograph detection and UI span highlighting. */
    val original: String,
    /** NFKC, zero-width stripped, whitespace collapsed, `Locale.ROOT` lowercased. */
    val normalized: String,
    val urls: List<ExtractedUrl>,
    val senderHint: String?,
    val detectedLanguage: Language,
    val brandClaims: List<BrandClaim>,
)
