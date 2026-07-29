package com.scamshield.core.analysis.ingest

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalization, design.md section 2.1. Order matters -- each step is applied to the
 * output of the previous one, in exactly this sequence:
 *
 * 1. Unicode NFKC normalization.
 * 2. Strip zero-width characters that scammers insert to break naive keyword matching
 *    (O + ZWSP + T + ZWSP + P still reads as "OTP" to a human but not to `contains("otp")`).
 * 3. Fold confusable whitespace to U+0020 and collapse runs.
 * 4. Lowercase using Locale.ROOT -- never the device locale. The Turkish locale maps
 *    'I' to dotless-i, which would silently break every ASCII keyword pattern for a device
 *    set to Turkish.
 * 5. Digits, punctuation, and emoji are left alone. Currency symbols, "!!!", and digit
 *    density are themselves signal, not noise to be stripped.
 *
 * This operates on NormalizedMessage.original and produces `normalized`; the caller is
 * responsible for keeping `original` untouched, since homograph and link-mismatch detection
 * (design.md section 3.3) require the exact source characters.
 *
 * Every character class below is written with a doubled backslash (`"\\u200B"`) rather than
 * a single Kotlin-level unicode escape or a literal invisible character. A literal invisible
 * character pasted into source is unreadable and unauditable in a diff; a single-backslash
 * Kotlin escape gets decoded into the actual character by the KOTLIN COMPILER at compile time,
 * which is exactly as unauditable in the compiled class as a literal character would be in
 * source. Doubling the backslash keeps the six characters `\`, `u`, `2`, `0`, `0`, `B` as the
 * literal runtime String value; java.util.regex.Pattern independently supports `\uhhhh` as a
 * *pattern*-level escape, so it decodes to the codepoint only when the regex itself compiles,
 * and the hex digits stay visible and greppable at every stage before that.
 */
object TextNormalizer {

    // design.md section 2.1 step 2: ZERO WIDTH SPACE, ZERO WIDTH NON-JOINER,
    // ZERO WIDTH JOINER, WORD JOINER, ZERO WIDTH NO-BREAK SPACE (BOM).
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")

    // design.md section 2.1 step 3: NO-BREAK SPACE, FIGURE SPACE, NARROW NO-BREAK SPACE.
    private val CONFUSABLE_WHITESPACE = Regex("[\\u00A0\\u2007\\u202F]")

    private val WHITESPACE_RUN = Regex("\\s+")

    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val zeroWidthStripped = ZERO_WIDTH.replace(nfkc, "")
        val whitespaceFolded = CONFUSABLE_WHITESPACE.replace(zeroWidthStripped, " ")
        val collapsed = WHITESPACE_RUN.replace(whitespaceFolded, " ").trim()
        return collapsed.lowercase(Locale.ROOT)
    }
}
