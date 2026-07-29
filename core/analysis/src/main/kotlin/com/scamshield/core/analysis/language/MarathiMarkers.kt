package com.scamshield.core.analysis.language

/**
 * design.md section 2.3: within Devanagari-script text, disambiguate Hindi from Marathi on
 * "a small marker-word list" rather than a full classifier. These are function words and
 * common verb forms that are spelled differently in Marathi than in Hindi -- not vocabulary
 * that merely differs in usage frequency, but words a Hindi speaker would not write this way
 * at all (आहे "is" rather than Hindi है, नाही "no" rather than Hindi नहीं, मी "I" rather than
 * Hindi मैं). A single hit is treated as sufficient, unlike the much larger and individually
 * weaker HI_LATN lexicon in [RomanizedHindiLexicon] -- each of these words is a strong signal
 * on its own precisely because it has no ordinary Hindi reading.
 */
internal object MarathiMarkers {

    private val MARKERS = setOf(
        "आहे", "आहेत", "नाही", "मी", "तू", "तुम्ही", "आम्ही", "त्याने", "तिने",
        "करा", "करतो", "करते", "झाले", "झाला", "झाली", "सांगा", "सांगितले",
        "अजून", "तुमचा", "तुमची", "तुमचे", "माझा", "माझी", "माझे", "आपले", "असून",
        "होणार", "व्हा", "केले", "केला", "केली", "यावे", "जावे", "पाहिजे", "मिळेल",
    )

    fun hasMarker(text: String): Boolean {
        val tokens = text.split(WORD_BOUNDARY)
        return tokens.any { it in MARKERS }
    }

    // \p{L} alone is not enough: Devanagari vowel signs (matras) like the "u" in "तुमचे" are
    // Unicode category Mark (Mn/Mc), not Letter, so splitting on "not a letter" alone breaks
    // words apart at every matra. \p{M} keeps combining marks attached to their base letter.
    private val WORD_BOUNDARY = Regex("[^\\p{L}\\p{M}]+")
}
