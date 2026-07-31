package com.scamshield.analyzer.pattern

import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.PatternRule
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Signal

/**
 * design.md section 5: regex patterns from patterns.json. Never sets `forceVerdict` --
 * unlike the URL and sender analyzers, wording alone is never precise enough to override the
 * fusion policy (architecture.md section 7's own table: "Can override model? No").
 *
 * Matches run against [NormalizedMessage.normalized], not `original` -- normalization's
 * zero-width-character stripping (design.md section 2.1) exists specifically so a scammer
 * cannot defeat keyword matching by splicing invisible characters into "OTP"; matching the
 * raw original text here would silently reopen that hole.
 */
class PatternAnalyzer(private val patterns: List<PatternRule>) : Analyzer {

    override val id = AnalyzerId.PATTERN

    override suspend fun analyze(message: NormalizedMessage): Signal {
        val matched = patterns.filter { rule -> fires(rule, message) }

        val evidence = matched.map { rule -> Evidence(rule.evidence, rule.severity) }

        // design.md section 5: "total pattern-analyzer weight is capped at 0.5 regardless of
        // how many fire" -- uncapped, a verbose scam message stacks a dozen weak patterns and
        // drowns out the model.
        val scamWeight = matched.sumOf { it.weight.toDouble() }.toFloat().coerceAtMost(WEIGHT_CAP)

        return Signal.Scored(
            analyzerId = id,
            scamWeight = scamWeight,
            categoryHints = categoryHintsFrom(matched),
            evidence = evidence,
        )
    }

    private fun fires(rule: PatternRule, message: NormalizedMessage): Boolean {
        if (message.detectedLanguage !in rule.languages) return false
        if (rule.suppressIf?.containsMatchIn(message.normalized) == true) return false
        return rule.regex.containsMatchIn(message.normalized)
    }

    private fun categoryHintsFrom(matched: List<PatternRule>): Map<ScamCategory, Float> {
        val totals = mutableMapOf<ScamCategory, Float>()
        for (rule in matched) {
            for ((category, hint) in rule.categoryHints) {
                totals[category] = (totals[category] ?: 0f) + hint
            }
        }
        return totals
    }

    companion object {
        private const val WEIGHT_CAP = 0.5f
    }
}
