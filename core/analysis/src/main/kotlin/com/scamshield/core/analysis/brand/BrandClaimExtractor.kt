package com.scamshield.core.analysis.brand

import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.BrandClaim

/**
 * design.md section 4.1: scans a message for brand aliases from banks.json. A hit means the
 * message *claims* to be from that organization -- nothing more. This is a shared primitive
 * consumed by both the URL analyzer (design.md section 3.5's force-verdict rule) and the
 * sender analyzer (section 4.2's DLT header validation), living here in `:core:analysis`
 * rather than in either analyzer, which is the one deliberate exception to "analyzers share
 * nothing" (architecture.md section 5) -- see DECISIONS.md.
 *
 * Matching runs directly against the message's original text, case-insensitively, rather than
 * against the normalized form: spans need to index into `original` for UI highlighting
 * (matching how [com.scamshield.core.analysis.url.UrlExtractor] handles the same tradeoff),
 * and a brand alias has no homograph-sensitive characters the way a URL host does, so nothing
 * is lost by not pre-normalizing. As with URL extraction, a zero-width character spliced
 * into a brand name to evade this matcher is not specifically handled -- revisit from Phase
 * 2's real corpus if it turns out to matter in practice.
 *
 * Matching is whole-word: "sbi" inside "SBIINB" (a DLT header, not a claim) does not match,
 * because a letter/digit immediately follows within the same token.
 */
object BrandClaimExtractor {

    fun extract(original: String, banks: List<BankEntry>): List<BrandClaim> {
        val claims = mutableListOf<BrandClaim>()
        for (brand in banks) {
            for (alias in brand.aliases) {
                val pattern = aliasPattern(alias) ?: continue
                for (match in pattern.findAll(original)) {
                    claims += BrandClaim(
                        brandId = brand.id,
                        displayName = brand.displayName,
                        matchedAlias = alias,
                        spanStart = match.range.first,
                        spanEnd = match.range.last + 1,
                    )
                }
            }
        }
        return claims.sortedBy { it.spanStart }
    }

    /** Alias words joined with flexible whitespace, so "State   Bank\nof India" still hits. */
    private fun aliasPattern(alias: String): Regex? {
        val words = alias.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val escaped = words.joinToString("\\s+") { Regex.escape(it) }
        return Regex(
            "(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
    }
}
