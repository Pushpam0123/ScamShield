package com.scamshield.analyzer.url

import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.ConfusableTable
import com.scamshield.core.model.DomainReputationIndex
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.ExtractedUrl
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import com.scamshield.core.model.Verdict

/**
 * design.md section 3: the highest-precision analyzer, the only one besides the sender
 * analyzer permitted to set `forceVerdict` (architecture.md section 7).
 *
 * [reputationIndex] and [onlineDomainLookup] are both nullable: a missing or invalid rule
 * pack must degrade the domain-age check to "no evidence from this check", not fail the
 * whole analyzer (architecture.md C6). [onlineDomainLookup] is effectively always null until
 * Phase 5 wires up the opt-in RDAP toggle -- see [OnlineDomainLookup]'s own doc comment.
 */
class UrlAnalyzer(
    private val confusables: ConfusableTable,
    banks: List<BankEntry>,
    private val shorteners: Set<String>,
    private val shortenerBrandOperated: Map<String, String>,
    private val suspiciousTlds: Set<String>,
    private val reputationIndex: DomainReputationIndex?,
    private val onlineDomainLookup: OnlineDomainLookup? = null,
) : Analyzer {

    override val id = AnalyzerId.URL

    private val typosquatDetector = TyposquatDetector(confusables)
    private val knownDomains: List<Pair<BankEntry, String>> =
        banks.flatMap { brand -> brand.domains.map { domain -> brand to domain } }

    override suspend fun analyze(message: NormalizedMessage): Signal {
        val evidence = mutableListOf<Evidence>()
        for (url in message.urls) {
            evidence += evidenceFor(url)
        }

        // design.md section 3.5: force SCAM only on >= 1 CRITICAL evidence item *and* a
        // brand claim present. A new/unknown domain alone (an INFO-severity finding) is
        // never enough -- that would false-positive on every legitimate small business.
        val hasCritical = evidence.any { it.severity == Severity.CRITICAL }
        val forceVerdict = if (hasCritical && message.brandClaims.isNotEmpty()) Verdict.SCAM else null

        return Signal.Scored(
            analyzerId = id,
            scamWeight = scoreFrom(evidence),
            categoryHints = categoryHintsFrom(evidence),
            evidence = evidence,
            forceVerdict = forceVerdict,
        )
    }

    private suspend fun evidenceFor(url: ExtractedUrl): List<Evidence> {
        val span = url.spanStart until url.spanEnd

        if (isIpLiteral(url.host)) {
            // No registrable domain to run the rest of the checks against.
            return listOf(Evidence(EvidenceType.IP_ADDRESS_HOST, Severity.CRITICAL, mapOf("host" to url.host), span))
        }

        val registrable = url.registrableDomain ?: return emptyList()
        // design.md section 3.3 requires the homograph and confusable-fold checks to "operate
        // on the ORIGINAL host string" -- but `registrable` has already been through
        // PublicSuffixListParser's IDN.toASCII (needed for eTLD+1 matching against ASCII PSL
        // rules), which punycode-encodes any Cyrillic/Greek/Armenian codepoints away into an
        // opaque "xn--" label. Run the typosquat/homograph/fold checks against the original-
        // script reconstruction instead, or a real Unicode homograph domain can never trigger
        // either check no matter what it contains -- see originalScriptRegistrable's own doc
        // comment for why reconstructing it from `url.host` is safe.
        val originalRegistrable = originalScriptRegistrable(registrable, url.host)
        val label = originalRegistrable.substringBefore('.')
        val evidence = mutableListOf<Evidence>()

        val directHit = typosquatDetector.detect(label, knownDomains)
        if (directHit != null) {
            evidence += typosquatEvidence(originalRegistrable, directHit, span)
        }

        if (HomographDetector.hasMixedScript(label)) {
            evidence += Evidence(EvidenceType.HOMOGRAPH_CHARACTERS, Severity.CRITICAL, mapOf("host" to url.host), span)
        }

        // design.md section 3.3: fold confusables, then re-run the typosquat check on the
        // folded form. Only meaningful if the direct check (on the raw label) missed --
        // running it unconditionally would just duplicate the same evidence twice for an
        // already-ASCII label, where folding is a no-op.
        if (directHit == null) {
            val folded = ConfusableFolder.fold(label, confusables)
            if (folded != label) {
                // An EXACT match after folding is handled separately from
                // TyposquatDetector.detect, not passed through it: that detector treats
                // "candidate == known brand label" as "legitimate, D == B" -- correct for a
                // raw candidate (it really is the brand's own domain), but wrong here. We
                // already know (folded != label) that what the user actually sees is NOT the
                // brand's real domain; folding to an exact match is precisely the
                // impersonation design.md section 3.3 calls "a strong hit", not an exemption.
                val exactFoldMatch = knownDomains.firstOrNull { (_, domain) -> domain.substringBefore('.') == folded }
                if (exactFoldMatch != null) {
                    val (brand, domain) = exactFoldMatch
                    evidence += typosquatEvidence(originalRegistrable, TyposquatHit(brand, folded, domain), span)
                } else {
                    typosquatDetector.detect(folded, knownDomains)?.let { foldedHit ->
                        evidence += typosquatEvidence(originalRegistrable, foldedHit, span)
                    }
                }
            }
        }

        if (isShortener(registrable)) {
            evidence += Evidence(EvidenceType.URL_SHORTENER, Severity.WARN, mapOf("host" to url.host), span)
        }

        val tld = registrable.substringAfterLast('.')
        if (tld in suspiciousTlds) {
            evidence += Evidence(EvidenceType.SUSPICIOUS_TLD, Severity.WARN, mapOf("tld" to tld), span)
        }

        // design.md section 3.4: anchor-text mismatch. Always inert in v1 -- this app
        // ingests plain text only, so UrlExtractor never populates displayText (see its own
        // doc comment). Written so it activates automatically if that ever changes.
        //
        // The local `val` captures below are not stylistic: a nullable property declared in
        // another module (ExtractedUrl lives in :core:model) cannot be smart-cast after a
        // null check, only a local variable can.
        val displayText = url.displayText
        if (displayText != null && !displayText.contains(registrable, ignoreCase = true)) {
            evidence += Evidence(
                EvidenceType.LINK_TEXT_MISMATCH,
                Severity.CRITICAL,
                mapOf("displayed" to displayText, "actual" to registrable),
                span,
            )
        }

        val path = url.path
        if (url.scheme.equals("http", ignoreCase = true) && path != null && CREDENTIAL_PATH.containsMatchIn(path)) {
            evidence += Evidence(EvidenceType.NON_HTTPS_CREDENTIAL_PAGE, Severity.WARN, mapOf("path" to path), span)
        }

        evidence += domainAgeEvidence(registrable, span)

        return evidence
    }

    private fun typosquatEvidence(actualDomain: String, hit: TyposquatHit, span: IntRange) = Evidence(
        EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND,
        Severity.CRITICAL,
        mapOf("actual" to actualDomain, "brand" to hit.brand.displayName, "real_domain" to hit.matchedDomain),
        span,
    )

    /**
     * design.md section 3.1. The online path takes priority when available and confirms an
     * age: CRITICAL if under 30 days, no evidence at all if it confirms the domain is older
     * (a stronger and more specific finding than the offline path can produce either way).
     * With no online lookup wired up -- true for all of Phase 1 -- this always falls through
     * to the offline path, which can only say "unknown", at INFO, never "new".
     */
    private suspend fun domainAgeEvidence(registrable: String, span: IntRange): List<Evidence> {
        val ageDays = onlineDomainLookup?.ageDays(registrable)
        if (ageDays != null) {
            return if (ageDays < 30) {
                listOf(
                    Evidence(
                        EvidenceType.DOMAIN_VERY_NEW,
                        Severity.CRITICAL,
                        mapOf("domain" to registrable, "age_days" to ageDays.toString()),
                        span,
                    ),
                )
            } else {
                emptyList()
            }
        }

        val index = reputationIndex ?: return emptyList()
        if (index.isAllowlisted(registrable) || index.isEstablished(registrable)) return emptyList()
        return listOf(Evidence(EvidenceType.DOMAIN_VERY_NEW, Severity.INFO, mapOf("domain" to registrable), span))
    }

    private fun isShortener(registrable: String): Boolean {
        if (registrable in shortenerBrandOperated) return false // brand's own shortener: never a warning
        return registrable in shorteners
    }

    /**
     * Reconstructs the registrable domain in its original script from [host] (the as-written
     * form `ExtractedUrl.host` preserves) rather than [asciiRegistrable] (the IDN-encoded form
     * `PublicSuffixListParser` returns). IDN-encodes each label independently and never changes
     * label count or order, so the original-script registrable domain is exactly [host]'s
     * trailing N labels, where N is [asciiRegistrable]'s own label count -- correct regardless
     * of how many subdomain labels precede the registrable part. A no-op for an
     * already-ASCII host, since encoding ASCII through IDN.toASCII changes nothing.
     */
    private fun originalScriptRegistrable(asciiRegistrable: String, host: String): String {
        val labelCount = asciiRegistrable.count { it == '.' } + 1
        return host.split('.').takeLast(labelCount).joinToString(".")
    }

    /** Duplicated (not shared) from `:core:analysis`'s PSL parser -- see DECISIONS.md. */
    private fun isIpLiteral(host: String): Boolean {
        val stripped = host.trim().removeSurrounding("[", "]")
        if (IPV4_LITERAL.matches(stripped)) {
            return stripped.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
        }
        return stripped.contains(':') && IPV6_LITERAL.matches(stripped)
    }

    private fun scoreFrom(evidence: List<Evidence>): Float =
        when (evidence.maxOfOrNull { it.severity }) {
            Severity.CRITICAL -> 0.9f
            Severity.WARN -> 0.4f
            Severity.INFO -> 0.1f
            null -> 0.0f
        }

    private fun categoryHintsFrom(evidence: List<Evidence>): Map<ScamCategory, Float> {
        val kycHintTypes = setOf(
            EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND,
            EvidenceType.HOMOGRAPH_CHARACTERS,
            EvidenceType.NON_HTTPS_CREDENTIAL_PAGE,
            EvidenceType.IP_ADDRESS_HOST,
        )
        return if (evidence.any { it.type in kycHintTypes }) {
            mapOf(ScamCategory.KYC_PHISHING to 0.3f)
        } else {
            emptyMap()
        }
    }

    companion object {
        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_LITERAL = Regex("^[0-9a-fA-F:]+$")

        // design.md section 3.4: /(login|verify|kyc|update|otp|pay)/i
        private val CREDENTIAL_PATH = Regex("(login|verify|kyc|update|otp|pay)", RegexOption.IGNORE_CASE)
    }
}
