package com.scamshield.core.data.rulepack

import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.ConfusableTable
import com.scamshield.core.model.PackMeta
import com.scamshield.core.model.RulePack

/**
 * The last-resort pack `RulePackLoader` falls back to when the bundled asset pack itself
 * fails to parse -- `architecture.md` section 11: "If validation fails, the app falls back to
 * the bundled pack... it never runs with a partially-loaded pack."
 *
 * This is deliberately hand-written Kotlin, not a second copy of the JSON asset: v1 ships only
 * one pack (bundled), so the one failure mode this exists to survive is the bundled asset
 * itself being corrupt or unreadable, and a fallback built from the same asset file would not
 * survive that. It is intentionally small -- a handful of the highest-volume brands and
 * shorteners -- because its job is "the rules engine still produces *some* evidence and the
 * app does not crash," not full precision; `RulePackLoader` records when this path was taken
 * so a caller can tell the difference.
 */
internal object DefaultRulePack {

    val pack = RulePack(
        meta = PackMeta(version = "bundled-fallback", generatedAt = "", schemaVersion = 1),
        banks = listOf(
            BankEntry(
                id = "sbi", displayName = "State Bank of India",
                aliases = listOf("sbi", "state bank of india", "state bank"),
                domains = listOf("onlinesbi.sbi", "sbi.co.in", "yono.sbi"),
                dltHeaders = listOf("SBIINB", "SBIBNK", "SBIUPI"),
            ),
            BankEntry(
                id = "hdfc", displayName = "HDFC Bank",
                aliases = listOf("hdfc", "hdfc bank", "hdfcbank"),
                domains = listOf("hdfcbank.com"),
                dltHeaders = listOf("HDFCBK", "HDFCBN"),
            ),
            BankEntry(
                id = "icici", displayName = "ICICI Bank",
                aliases = listOf("icici", "icici bank", "icicibank"),
                domains = listOf("icicibank.com"),
                dltHeaders = listOf("ICICIB", "ICICIT"),
            ),
            BankEntry(
                id = "paytm", displayName = "Paytm",
                aliases = listOf("paytm"),
                domains = listOf("paytm.com"),
                dltHeaders = listOf("PAYTM"),
            ),
            BankEntry(
                id = "incometax", displayName = "Income Tax Department",
                aliases = listOf("income tax", "income tax department", "incometax"),
                domains = listOf("incometax.gov.in"),
                dltHeaders = listOf("ITDEPT"),
            ),
        ),
        shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl"),
        shortenerBrandOperated = emptyMap(),
        // No confusable folds: an empty fold table just means the homograph re-check after
        // folding never fires (design.md section 3.3's *first* check, plain mixed-script
        // detection, is unaffected -- it needs no table at all). Losing that one refinement is
        // an acceptable cost for a path that only runs when the real pack is already broken.
        confusables = ConfusableTable(
            singleCharFolds = emptyMap(),
            sequenceFolds = emptyMap(),
            shortLabelDistance = 1,
            longLabelDistance = 2,
            shortLabelMaxLength = 6,
        ),
        patterns = emptyList(),
        suspiciousTlds = setOf("xyz", "top", "tk", "click", "gq"),
    )
}
