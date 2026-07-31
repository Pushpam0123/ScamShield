package com.scamshield.analyzer.sender

import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.BrandClaim
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import com.scamshield.core.model.Verdict

/**
 * design.md section 4: the sender analyzer, the other of the two analyzers (besides the URL
 * analyzer) permitted to set `forceVerdict` (architecture.md section 7).
 *
 * All three evidence rules require a brand claim to already be present on the message --
 * a `senderHint`, or its absence, is never evidence of anything on its own.
 */
class SenderAnalyzer(banks: List<BankEntry>) : Analyzer {

    override val id = AnalyzerId.SENDER

    // banks.json's own authoring comment: a DLT header may legitimately belong to more than
    // one brand (GOOGLE -> google, gpay), so this must be header -> SET of brand ids, not a
    // one-to-one map -- a one-to-one map would false-positive on genuine Google Pay / Amazon
    // Pay messages.
    private val headerToBrandIds: Map<String, Set<String>> =
        banks.flatMap { brand -> brand.dltHeaders.map { header -> header to brand.id } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, brandIds) -> brandIds.toSet() }

    private val displayNameById: Map<String, String> = banks.associate { it.id to it.displayName }

    override suspend fun analyze(message: NormalizedMessage): Signal {
        if (message.brandClaims.isEmpty()) {
            return Signal.Scored(analyzerId = id, scamWeight = 0.0f)
        }

        val evidence = evidenceFor(message.senderHint, message.brandClaims)
        val hasCritical = evidence.any { it.severity == Severity.CRITICAL }

        return Signal.Scored(
            analyzerId = id,
            scamWeight = scoreFrom(evidence),
            categoryHints = categoryHintsFrom(evidence),
            evidence = evidence,
            forceVerdict = if (hasCritical) Verdict.SCAM else null,
        )
    }

    private fun evidenceFor(senderHint: String?, brandClaims: List<BrandClaim>): List<Evidence> {
        if (senderHint == null) {
            return listOf(Evidence(EvidenceType.BRAND_CLAIM_WITHOUT_DLT_HEADER, Severity.INFO))
        }

        if (BARE_MOBILE_NUMBER.matches(senderHint)) {
            return listOf(
                Evidence(
                    EvidenceType.UNREGISTERED_NUMERIC_SENDER,
                    Severity.CRITICAL,
                    mapOf("sender" to senderHint),
                ),
            )
        }

        if (DltHeaderFormat.isValid(senderHint)) {
            // Unknown header -> no evidence, deliberately: banks.json's registry is
            // best-effort and incomplete, so an unrecognized header must never be treated as
            // suspicious on that basis alone.
            val brandsForHeader = headerToBrandIds[DltHeaderFormat.body(senderHint)] ?: return emptyList()
            val claimedBrandIds = brandClaims.map { it.brandId }.toSet()
            if (brandsForHeader.intersect(claimedBrandIds).isNotEmpty()) return emptyList()

            return listOf(
                Evidence(
                    EvidenceType.DLT_HEADER_MISMATCH,
                    Severity.CRITICAL,
                    mapOf(
                        "sender" to senderHint,
                        "claimed_brand" to brandClaims.first().displayName,
                        "header_brands" to brandsForHeader.mapNotNull { displayNameById[it] }.sorted().joinToString(", "),
                    ),
                ),
            )
        }

        return emptyList()
    }

    private fun scoreFrom(evidence: List<Evidence>): Float =
        when (evidence.maxOfOrNull { it.severity }) {
            Severity.CRITICAL -> 0.9f
            Severity.WARN -> 0.4f
            Severity.INFO -> 0.1f
            null -> 0.0f
        }

    private fun categoryHintsFrom(evidence: List<Evidence>): Map<ScamCategory, Float> {
        val criticalTypes = setOf(EvidenceType.UNREGISTERED_NUMERIC_SENDER, EvidenceType.DLT_HEADER_MISMATCH)
        return if (evidence.any { it.type in criticalTypes }) {
            mapOf(ScamCategory.KYC_PHISHING to 0.3f)
        } else {
            emptyMap()
        }
    }

    companion object {
        private val BARE_MOBILE_NUMBER = Regex("^\\d{10}$")
    }
}
