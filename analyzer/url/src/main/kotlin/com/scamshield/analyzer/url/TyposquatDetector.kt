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

        // "D == B: legitimate" (design.md section 3.2) has to be a check against *every* known
        // label before any fuzzy rule runs, not a per-pair skip inside the loop below. A
        // per-pair `continue` only protects a candidate from being flagged against the one
        // brand it happens to equal -- it does nothing to stop a *later* entry in the list from
        // fuzzy-matching the same candidate. That is exactly how "paytm.com" (paytm's own,
        // entirely legitimate domain) could get flagged as impersonating Google Pay: paytm's
        // own exact-match entry only `continue`s past itself, and the loop goes on to compare
        // "paytm" against gpay's "pay.google.com" -> label "pay", which "paytm" contains as a
        // substring. Checking global legitimacy up front closes that regardless of which order
        // brands happen to appear in the pack.
        if (knownDomains.any { (_, domain) -> domain.substringBefore('.') == candidateLabel }) return null

        val candidateSquashed = squash(candidateLabel)

        for ((brand, knownDomain) in knownDomains) {
            val knownLabel = knownDomain.substringBefore('.')
            if (knownLabel.isEmpty()) continue

            val threshold = distanceThreshold(knownLabel.length)
            if (DamerauLevenshtein.distance(candidateLabel, knownLabel) <= threshold) {
                return TyposquatHit(brand, knownLabel, knownDomain)
            }
            // The substring rule needs its own floor on the known label's length, separate
            // from the distance rule's short/long split above. A known label under
            // MIN_SUBSTRING_LABEL_LENGTH (real examples in the pack: "wa" from wa.me, "db" from
            // db.com, "sc" from sc.com) is a substring of huge numbers of ordinary, unrelated
            // words ("malware", "database", "scan"...) with no impersonation implied at all --
            // unlike the distance rule, which is already precise even at length 2-3 because an
            // edit-distance-1 budget is inherently tight. "sbi" (3 chars) is design.md's own
            // canonical substring example and must stay eligible, hence 3, not something higher.
            if (knownLabel.length >= MIN_SUBSTRING_LABEL_LENGTH && candidateLabel.contains(knownLabel)) {
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

        private const val MIN_SUBSTRING_LABEL_LENGTH = 3
    }
}
