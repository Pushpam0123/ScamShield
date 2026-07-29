package com.scamshield.core.analysis.language

/**
 * design.md section 2.3's romanized-Hindi marker lexicon for detecting HI_LATN -- Hindi
 * written in Latin script ("aapka khata band ho jayega"), the dominant real-world input class
 * for this app and the one general-purpose language identifiers handle worst, since every
 * token here is also a valid (if unlikely) English string in isolation.
 *
 * design.md describes this as "a ~300-token" lexicon. This one holds under 200: a smaller,
 * genuinely common set of words is worth more than padding to a round number with entries
 * nobody would actually type, and the threshold below (>= 2 hits) is chosen assuming every
 * entry here is a real, frequent word -- see DECISIONS.md. Grow it from Phase 2's actual
 * labelled corpus rather than by guessing further entries now.
 *
 * A handful of otherwise-natural entries are deliberately absent because they collide with
 * ordinary English: "to", "hi", "do", "ha", "ya", and "main" are all common English words or
 * interjections in their own right, and "the" is literally the most frequent word in English
 * -- any of these in the list would misroute plain English SMS. "bank", "account", "number",
 * "mobile", "phone", "fees", and "lottery" are excluded for the same reason from the other
 * direction: they are the exact English loanwords a genuine English-language bank or scam SMS
 * uses verbatim, so counting them here would push real English messages toward HI_LATN.
 *
 * Every remaining token is a single ASCII word; the detector matches on whole tokens only, so
 * this cannot false-positive by matching part of an unrelated English word.
 */
internal object RomanizedHindiLexicon {

    private val MARKERS = setOf(
        // Pronouns and possessives
        "aap", "aapka", "aapki", "aapke", "aapko", "aapse", "aapne",
        "tum", "tumhara", "tumhari", "tumhare", "tumko", "tumse",
        "mera", "meri", "mere", "mujhe", "mujhko", "mai",
        "hum", "humara", "humari", "humare", "hamara", "hamari", "hamare", "humko", "hamein",
        "yeh", "ye", "woh", "wo", "iska", "iski", "iske", "uska", "uski", "uske",
        "kaun", "kya", "kyun", "kyu", "kaise", "kahan", "kab", "kitna", "kitne", "kitni",
        "jo", "jis", "jab", "tab", "jahan", "wahan", "yahan",

        // Conjunctions and particles
        "agar", "magar", "lekin", "kintu", "phir", "fir", "bhi", "toh",
        "nahi", "nahin", "haan", "ji", "aur", "athva",

        // Common verb forms
        "hai", "hain", "tha", "thi", "hoga", "hogi", "honge",
        "kar", "karo", "karein", "kare", "karna", "karta", "karti", "karte",
        "hua", "hui", "hue", "jayega", "jayegi", "jaenge", "jaega",
        "aaya", "aayi", "aaye", "aana", "jana", "gaya", "gayi", "gaye",
        "dena", "diya", "di", "diye", "dijiye",
        "lena", "liya", "li", "liye", "lijiye",
        "batao", "bataye", "bataiye", "bata", "batayein",
        "bhejo", "bhejiye", "bhej", "bhejein", "bheja",
        "dekho", "dekhiye", "dekh", "dekhein",
        "suno", "suniye", "sunna",
        "milega", "milegi", "milenge", "mila", "mili",
        "chahiye", "mangta", "chalega", "rukho", "ruko", "kariye",

        // Urgency / time
        "turant", "turnat", "abhi", "jaldi", "shighra", "aaj", "kal", "parso",
        "hamesha", "kabhi", "subah", "shaam", "raat", "din", "hafta", "mahina", "saal", "jald",

        // Quantifiers
        "kisi", "koi", "sabhi", "sab", "dono", "kuch", "thoda", "bahut", "zyada", "jyada", "kam", "sirf", "keval",

        // Financial / scam-relevant terms (not the bare English loanwords, see class doc)
        "paisa", "paise", "rupaye", "rupiya", "rupya", "khata", "khaata",
        "jama", "nikaal", "udhar", "karza", "karz", "shulk",
        "inaam", "inam", "puraskar", "jeet", "jeeta", "jeete", "vijeta",

        // Politeness / greetings
        "kripya", "meherbani", "dhanyavad", "shukriya", "namaste", "namaskar",
        "maafi", "zaroor", "jarur",

        // Common nouns
        "ghar", "kaam", "naam", "pata",
    )

    private val WORD_BOUNDARY = Regex("[^a-z0-9]+")

    fun countMarkerHits(normalizedText: String): Int {
        val tokens = normalizedText.split(WORD_BOUNDARY)
        return tokens.count { it in MARKERS }
    }
}
