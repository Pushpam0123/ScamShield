package com.scamshield.core.data.rulepack

import com.scamshield.analyzer.url.BloomDomainReputationIndex
import com.scamshield.core.analysis.url.PublicSuffixListParser
import com.scamshield.core.model.DomainReputationIndex
import com.scamshield.core.model.PublicSuffixList
import com.scamshield.core.model.RulePack

/**
 * The output of [RulePackLoader.load]: everything an analyzer needs, already wired.
 *
 * [publicSuffixList] is never null -- an empty rule set still functions (every unlisted TLD
 * falls back to the PSL algorithm's own implicit "last label is the suffix" rule; see
 * [PublicSuffixListParser]'s doc comment). [reputationIndex] degrades to null instead, matching
 * `UrlAnalyzer`'s existing nullable-and-tolerant contract for it.
 *
 * [isBundledFallback] is true only when the four core JSON files themselves failed to parse
 * and [DefaultRulePack] was substituted wholesale -- `architecture.md` section 11 calls for a
 * non-fatal report in that case, which is a caller's decision (Settings does not exist until
 * Phase 5), not this class's.
 */
data class LoadedRulePack(
    val rulePack: RulePack,
    val publicSuffixList: PublicSuffixList,
    val reputationIndex: DomainReputationIndex?,
    val isBundledFallback: Boolean,
)

/**
 * Loads and assembles the rule pack from [source] (`architecture.md` section 11 /
 * `implementation.md` Phase 1.10).
 *
 * The four core JSON files (banks, shorteners, typosquat, patterns) are loaded as one atomic
 * unit: if any one of them is missing, unreadable, or fails to decode, the whole pack falls
 * back to [DefaultRulePack] rather than mixing a partially-loaded pack with the bundled
 * default -- "never run with a partially-loaded pack" is the exact wording of the constraint
 * this exists to satisfy. `public_suffix_list.dat` and `reputation.bin` are treated as
 * independent, individually-optional inputs instead, because both of their consumers already
 * have a documented, tested "absent" behavior that is safe to fall into on their own (see
 * [LoadedRulePack]'s doc comment) -- there is no need to discard a perfectly good banks.json
 * just because reputation.bin happened to be corrupt.
 */
class RulePackLoader(private val source: RulePackAssetSource) {

    fun load(): LoadedRulePack {
        val rulePack = runCatching { loadRulePack() }.getOrNull()

        val publicSuffixList = runCatching { PublicSuffixListParser.parse(readPslLines()) }
            .getOrDefault(PublicSuffixListParser.parse(emptyList()))

        val reputationIndex = runCatching { BloomDomainReputationIndex.parse(source.readBytes(REPUTATION_FILE)) }
            .getOrNull()

        return if (rulePack != null) {
            LoadedRulePack(rulePack, publicSuffixList, reputationIndex, isBundledFallback = false)
        } else {
            LoadedRulePack(DefaultRulePack.pack, publicSuffixList, reputationIndex, isBundledFallback = true)
        }
    }

    private fun loadRulePack(): RulePack {
        val meta = RulePackJsonParser.parseMeta(source.readText(META_FILE))
        val banks = RulePackJsonParser.parseBanks(source.readText(BANKS_FILE))
        val (shorteners, brandOperated) = RulePackJsonParser.parseShorteners(source.readText(SHORTENERS_FILE))
        val (confusables, suspiciousTlds) = RulePackJsonParser.parseTyposquat(source.readText(TYPOSQUAT_FILE))
        val patterns = RulePackJsonParser.parsePatterns(source.readText(PATTERNS_FILE))
        return RulePackJsonParser.assemble(
            meta = meta,
            banks = banks,
            shorteners = shorteners,
            shortenerBrandOperated = brandOperated,
            confusables = confusables,
            suspiciousTlds = suspiciousTlds,
            patterns = patterns,
        )
    }

    /** [PublicSuffixListParser.parse] wants one rule per line, no comments, no blanks. */
    private fun readPslLines(): List<String> =
        source.readText(PSL_FILE).lineSequence().filter { it.isNotBlank() }.toList()

    companion object {
        private const val META_FILE = "meta.json"
        private const val BANKS_FILE = "banks.json"
        private const val SHORTENERS_FILE = "shorteners.json"
        private const val TYPOSQUAT_FILE = "typosquat.json"
        private const val PATTERNS_FILE = "patterns.json"
        private const val PSL_FILE = "public_suffix_list.dat"
        private const val REPUTATION_FILE = "reputation.bin"
    }
}
