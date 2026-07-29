package com.scamshield.core.analysis.language

import com.scamshield.core.model.Language

/**
 * Script-based language detection, design.md section 2.3. Cheap and sufficient -- this
 * decides which explanation string set to show, nothing more. It is deliberately NOT a
 * classifier feature: the classifier handles mixed script natively, and hard-routing by
 * language would throw away code-mixed signal (a message can be mostly English with a
 * Hinglish threat wedged in the middle, and that wedge matters).
 *
 * Order of checks:
 *   1. >= 20% of (non-whitespace) codepoints in the Devanagari block -> HI, or MR if a
 *      Marathi marker word is present.
 *   2. >= 20% in the Bengali / Tamil / Telugu blocks -> BN / TA / TE respectively.
 *   3. Otherwise Latin script: >= 2 hits against a romanized-Hindi marker lexicon -> HI_LATN,
 *      else EN.
 *   4. No script majority and no Latin content at all (e.g. emoji-only, digits-only) -> UNKNOWN.
 */
object LanguageDetector {

    private const val SCRIPT_THRESHOLD = 0.20

    private val DEVANAGARI = 0x0900..0x097F
    private val BENGALI = 0x0980..0x09FF
    private val TAMIL = 0x0B80..0x0BFF
    private val TELUGU = 0x0C00..0x0C7F

    fun detect(normalizedText: String): Language {
        val codepoints = normalizedText.codePoints().toArray().filter { !Character.isWhitespace(it) }
        if (codepoints.isEmpty()) return Language.UNKNOWN

        val total = codepoints.size.toDouble()
        val devanagariShare = codepoints.count { it in DEVANAGARI } / total
        val bengaliShare = codepoints.count { it in BENGALI } / total
        val tamilShare = codepoints.count { it in TAMIL } / total
        val teluguShare = codepoints.count { it in TELUGU } / total

        return when {
            devanagariShare >= SCRIPT_THRESHOLD ->
                if (MarathiMarkers.hasMarker(normalizedText)) Language.MR else Language.HI
            bengaliShare >= SCRIPT_THRESHOLD -> Language.BN
            tamilShare >= SCRIPT_THRESHOLD -> Language.TA
            teluguShare >= SCRIPT_THRESHOLD -> Language.TE
            hasLatinLetter(codepoints) -> {
                val hits = RomanizedHindiLexicon.countMarkerHits(normalizedText)
                if (hits >= 2) Language.HI_LATN else Language.EN
            }
            else -> Language.UNKNOWN
        }
    }

    private fun hasLatinLetter(codepoints: List<Int>) =
        codepoints.any { it in 0x0041..0x005A || it in 0x0061..0x007A }
}
