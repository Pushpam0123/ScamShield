package com.scamshield.analyzer.url

/**
 * design.md section 3.3's mixed-script check: a single domain label mixing Latin with
 * Cyrillic, Greek, or Armenian codepoints. Confusable-character *folding* -- the table-driven
 * half of section 3.3 -- is handled separately by [ConfusableFolder] plus a re-run of
 * [TyposquatDetector], since that half's actual finding is a typosquat, just one discovered
 * via folding rather than direct comparison (see DECISIONS.md).
 *
 * Uses `Character.UnicodeScript`, the JDK's own script classification, rather than hand-rolled
 * codepoint ranges -- correctness here matters and the JDK table is the authoritative one.
 */
internal object HomographDetector {

    /** True if [label] contains both a Latin codepoint and a Cyrillic/Greek/Armenian one. */
    fun hasMixedScript(label: String): Boolean {
        var hasLatin = false
        var hasSuspiciousScript = false

        for (codepoint in label.codePoints()) {
            when (Character.UnicodeScript.of(codepoint)) {
                Character.UnicodeScript.LATIN -> hasLatin = true
                Character.UnicodeScript.CYRILLIC,
                Character.UnicodeScript.GREEK,
                Character.UnicodeScript.ARMENIAN,
                -> hasSuspiciousScript = true
                else -> Unit
            }
            if (hasLatin && hasSuspiciousScript) return true
        }
        return false
    }
}
