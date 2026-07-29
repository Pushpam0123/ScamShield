package com.scamshield.analyzer.url

import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.ConfusableTable

/** One brand domain a candidate label matched against, and how. */
internal data class TyposquatHit(
    val brand: BankEntry,
    /** The known domain's SLD label that was matched, e.g. "sbi" from "sbi.co.in". */
    val matchedLabel: String,
    /** The known domain in full, e.g. "sbi.co.in" -- goes in the "real address" evidence slot. */
    val matchedDomain: String,
)

/**
 * design.md section 3.2's three typosquat rules, run against every known brand domain.
 *
 * The registrable domain's SLD label is always its first label, regardless of how many
 * labels the public suffix itself has -- "onlinesbi" from "onlinesbi.co.in" the same way as
 * "sbi" from "sbi.com" -- because a registrable domain is by construction one label plus the
 * full suffix. Neither side of the comparison needs a fresh PSL lookup for this reason.
 *
 * Distance thresholds come from the rule pack's [ConfusableTable], not a hardcoded constant --
 * architecture.md section 11: rules are data, and a threshold tuned during Phase 2's dataset
 * work should not require a code change to ship.
 */
internal class TyposquatDetector(private val distanceConfig: ConfusableTable) {

    /**
     * [candidateLabel] is the message's candidate registrable domain's SLD label, already
     * lowercase. [knownDomains] is every domain across every brand in the pack -- the design's
     * own pseudocode compares against "known brand domain B", not only domains of a brand the
     * message already claims to be from, since the point is catching an *unclaimed* brand
     * impersonation just as much as a claimed one.
     */
    fun detect(candidateLabel: String, knownDomains: List<Pair<BankEntry, String>>): TyposquatHit? {
        if (candidateLabel.isEmpty()) return null
        val candidateSquashed = squash(candidateLabel)

        for ((brand, knownDomain) in knownDomains) {
            val knownLabel = knownDomain.substringBefore('.')
            if (knownLabel.isEmpty() || candidateLabel == knownLabel) continue // "D == B": legitimate

            val threshold = distanceThreshold(knownLabel.length)
            if (DamerauLevenshtein.distance(candidateLabel, knownLabel) <= threshold) {
                return TyposquatHit(brand, knownLabel, knownDomain)
            }
            if (candidateLabel.contains(knownLabel)) {
                // "sbi-kyc-verify.xyz" containing "sbi" -- design.md's own example, and per
                // its own note, the highest-yield of the three checks in practice.
                return TyposquatHit(brand, knownLabel, knownDomain)
            }
            if (candidateSquashed == squash(knownLabel)) {
                return TyposquatHit(brand, knownLabel, knownDomain)
            }
        }
        return null
    }

    /** design.md section 3.2: threshold(len(B)) -- short brands get a tighter budget. */
    private fun distanceThreshold(knownLabelLength: Int): Int =
        if (knownLabelLength <= distanceConfig.shortLabelMaxLength) {
            distanceConfig.shortLabelDistance
        } else {
            distanceConfig.longLabelDistance
        }

    companion object {
        /** Strips '-', '.', '_', and digits -- design.md section 3.2's own definition of squash. */
        fun squash(s: String): String = s.filterNot { it == '-' || it == '.' || it == '_' || it.isDigit() }
    }
}
