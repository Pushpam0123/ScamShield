package com.scamshield.analyzer.url

import com.scamshield.core.model.ConfusableTable

/**
 * Applies the rule pack's confusable-character table to a host label, design.md section 3.3:
 * single-character folds first (Cyrillic/Greek/Armenian look-alikes, fullwidth digits, ...),
 * then multi-character render-level folds ("rn" -> "m").
 *
 * Operates on whatever string it is given -- callers are responsible for passing the
 * *original*, unnormalized label. Folding the already-lowercased/NFKC'd form would find
 * nothing, since normalization has already erased the very characters a homograph attack
 * depends on (this is the same reasoning documented on UrlExtractor).
 */
internal object ConfusableFolder {

    fun fold(label: String, table: ConfusableTable): String {
        val singleFolded = buildString {
            for (char in label) {
                append(table.singleCharFolds[char] ?: char)
            }
        }
        var result = singleFolded
        for ((sequence, replacement) in table.sequenceFolds) {
            result = result.replace(sequence, replacement)
        }
        return result
    }
}
