package com.scamshield.analyzer.url

/**
 * Damerau-Levenshtein edit distance (optimal string alignment variant), used by typosquat
 * detection (design.md section 3.2). Unlike plain Levenshtein, an adjacent transposition
 * ("sbi" -> "sib") counts as a single edit, not two -- exactly the kind of slip a typosquat
 * domain is designed to produce.
 *
 * This is the restricted / optimal-string-alignment form: it does not allow a substring to
 * be edited more than once (true Damerau-Levenshtein permits, e.g., a transposition followed
 * by another edit touching the same two characters). That distinction never matters for
 * short brand-name labels at the edit-distance thresholds this app uses (1 or 2), and OSA is
 * the form almost universally implemented under the "Damerau-Levenshtein" name in practice.
 */
object DamerauLevenshtein {

    fun distance(a: String, b: String): Int {
        val lenA = a.length
        val lenB = b.length
        if (lenA == 0) return lenB
        if (lenB == 0) return lenA

        // d[i][j] = edit distance between a[0 until i] and b[0 until j]
        val d = Array(lenA + 1) { IntArray(lenB + 1) }
        for (i in 0..lenA) d[i][0] = i
        for (j in 0..lenB) d[0][j] = j

        for (i in 1..lenA) {
            for (j in 1..lenB) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    d[i - 1][j] + 1, // deletion
                    d[i][j - 1] + 1, // insertion
                    d[i - 1][j - 1] + cost, // substitution
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + cost) // transposition
                }
                d[i][j] = best
            }
        }
        return d[lenA][lenB]
    }
}
