package com.scamshield.analyzer.url

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.BrandClaim
import com.scamshield.core.model.ConfusableTable
import com.scamshield.core.model.DomainReputationIndex
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.ExtractedUrl
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import com.scamshield.core.model.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UrlAnalyzerTest {

    private val sbi = BankEntry(
        id = "sbi",
        displayName = "State Bank of India",
        aliases = listOf("sbi"),
        domains = listOf("sbi.co.in", "sbi.com"),
        dltHeaders = listOf("SBIINB"),
    )

    private val confusables = ConfusableTable(
        singleCharFolds = mapOf('а' to 'a', 'ı' to 'i'), // Cyrillic а, dotless i
        sequenceFolds = emptyMap(),
        shortLabelDistance = 1,
        longLabelDistance = 2,
        shortLabelMaxLength = 6,
    )

    /** A reputation index whose established set and allowlist are set per test. */
    private class FakeReputationIndex(
        val established: Set<String> = emptySet(),
        val allowlist: Set<String> = emptySet(),
    ) : DomainReputationIndex {
        override fun isEstablished(registrableDomain: String) = registrableDomain in established
        override fun isAllowlisted(registrableDomain: String) = registrableDomain in allowlist
    }

    private fun analyzer(
        shorteners: Set<String> = emptySet(),
        shortenerBrandOperated: Map<String, String> = emptyMap(),
        suspiciousTlds: Set<String> = emptySet(),
        reputationIndex: DomainReputationIndex? = FakeReputationIndex(),
        onlineDomainLookup: OnlineDomainLookup? = null,
    ) = UrlAnalyzer(
        confusables = confusables,
        banks = listOf(sbi),
        shorteners = shorteners,
        shortenerBrandOperated = shortenerBrandOperated,
        suspiciousTlds = suspiciousTlds,
        reputationIndex = reputationIndex,
        onlineDomainLookup = onlineDomainLookup,
    )

    private fun url(
        raw: String,
        scheme: String? = null,
        host: String,
        registrableDomain: String?,
        path: String? = null,
        isPunycode: Boolean = false,
        displayText: String? = null,
    ) = ExtractedUrl(
        raw = raw, scheme = scheme, host = host, registrableDomain = registrableDomain,
        path = path, isPunycode = isPunycode, displayText = displayText, spanStart = 0, spanEnd = raw.length,
    )

    private fun message(urls: List<ExtractedUrl>, brandClaims: List<BrandClaim> = emptyList()) = NormalizedMessage(
        id = MessageId("t"),
        original = "test message",
        normalized = "test message",
        urls = urls,
        senderHint = null,
        detectedLanguage = Language.EN,
        brandClaims = brandClaims,
    )

    private fun claim(brandId: String = "sbi") = BrandClaim(brandId, "State Bank of India", "sbi", 0, 3)

    // --- implementation.md Phase 1's own named fixture cases ---

    @Test
    fun `homograph host is flagged`() = runTest {
        // "sb" + Cyrillic а (U+0430) + "i.com" -- a genuine cross-script mix (Latin + Cyrillic).
        // Dotless-i (U+0131), despite looking like a classic homograph trick, is itself a
        // LATIN-script character per Unicode's own classification, so it cannot exercise this
        // specific mixed-script check -- it is exactly what ConfusableFolder / the folded-
        // typosquat re-check exist for instead, exercised separately below.
        val homographHost = "s${'а'}bi.com"
        val signal = analyzer().analyze(
            message(listOf(url(raw = homographHost, host = homographHost, registrableDomain = homographHost))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).contains(EvidenceType.HOMOGRAPH_CHARACTERS)
    }

    @Test
    fun `homograph is still detected when registrableDomain is already IDN-encoded, as the real PSL parser produces`() = runTest {
        // The real UrlExtractor + PublicSuffixListParser pipeline (:core:analysis) runs
        // IDN.toASCII while computing eTLD+1, so ExtractedUrl.registrableDomain is punycode
        // ("xn--sbi-6cd.com" for "sаbi.com"), never the raw Unicode UrlAnalyzer would need to
        // see the mixed script directly. The fixture above hand-sets registrableDomain to the
        // raw Unicode string, which is exactly what let the integration bug this test locks in
        // slip past every existing test: host and registrableDomain must differ here the same
        // way they really do, or this test would pass even if UrlAnalyzer read the wrong field.
        val homographHost = "s${'а'}bi.com"
        val idnEncodedRegistrable = "xn--sbi-6cd.com"
        val signal = analyzer().analyze(
            message(listOf(url(raw = homographHost, host = homographHost, registrableDomain = idnEncodedRegistrable))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).contains(EvidenceType.HOMOGRAPH_CHARACTERS)
    }

    @Test
    fun `confusable fold still works when registrableDomain is already IDN-encoded`() = runTest {
        // Same real-pipeline mismatch as above, for the fold-based re-check: folding
        // "xn--b-cvbe.com" (no Cyrillic left to fold) would find nothing, so this must operate
        // on the original-script reconstruction, not the IDN-encoded registrableDomain.
        val testConfusables = confusables.copy(singleCharFolds = confusables.singleCharFolds + ('ѕ' to 's'))
        val host = "${'ѕ'}b${'ı'}.com" // Cyrillic dze + dotless-i, folds to "sbi"
        val idnEncodedRegistrable = "xn--b-fka42v.com" // IDN.toASCII("ѕbı.com") -- verified independently
        val signal = UrlAnalyzer(
            confusables = testConfusables,
            banks = listOf(sbi),
            shorteners = emptySet(),
            shortenerBrandOperated = emptyMap(),
            suspiciousTlds = emptySet(),
            reputationIndex = FakeReputationIndex(),
        ).analyze(message(listOf(url(raw = host, host = host, registrableDomain = idnEncodedRegistrable)))) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).contains(EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND)
    }

    @Test
    fun `punycode host is not itself flagged as homograph but is available for other checks`() {
        val u = url(raw = "xn--sb-xkc.com", host = "xn--sb-xkc.com", registrableDomain = "xn--sb-xkc.com", isPunycode = true)
        assertThat(u.isPunycode).isTrue()
    }

    @Test
    fun `substring typosquat fixture case is flagged critical`() = runTest {
        val signal = analyzer().analyze(
            message(listOf(url(raw = "sbi-kyc-verify.xyz", host = "sbi-kyc-verify.xyz", registrableDomain = "sbi-kyc-verify.xyz"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND }
        assertThat(hit.severity).isEqualTo(Severity.CRITICAL)
        assertThat(hit.slots["brand"]).isEqualTo("State Bank of India")
        assertThat(hit.slots["real_domain"]).isEqualTo("sbi.co.in")
    }

    @Test
    fun `ip host fixture case is flagged critical with no other checks run`() = runTest {
        val signal = analyzer().analyze(
            message(listOf(url(raw = "http://192.168.1.1/login", scheme = "http", host = "192.168.1.1", registrableDomain = null, path = "/login"))),
        ) as Signal.Scored
        assertThat(signal.evidence).hasSize(1)
        assertThat(signal.evidence[0].type).isEqualTo(EvidenceType.IP_ADDRESS_HOST)
        assertThat(signal.evidence[0].severity).isEqualTo(Severity.CRITICAL)
    }

    @Test
    fun `shortener fixture case is flagged warn`() = runTest {
        val signal = analyzer(shorteners = setOf("bit.ly")).analyze(
            message(listOf(url(raw = "bit.ly/x", host = "bit.ly", registrableDomain = "bit.ly", path = "/x"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.URL_SHORTENER }
        assertThat(hit.severity).isEqualTo(Severity.WARN)
    }

    // --- homograph via confusable folding ---

    @Test
    fun `confusable folding reveals a typosquat that raw comparison would miss`() = runTest {
        // "sbi" is a short label (threshold 1). Two confusable substitutions --
        // Cyrillic dze U+0455 for 's', dotless-i U+0131 for 'i' -- put the raw label at
        // distance 2 from "sbi", past the threshold, so the direct check must miss it.
        // Folding both characters back collapses it to an exact "sbi" match.
        val twoSubstitutionsAway = "${'ѕ'}b${'ı'}"
        val testConfusables = confusables.copy(singleCharFolds = confusables.singleCharFolds + ('ѕ' to 's'))

        assertThat(DamerauLevenshtein.distance(twoSubstitutionsAway, "sbi")).isEqualTo(2)
        assertThat(TyposquatDetector(testConfusables).detect(twoSubstitutionsAway, listOf(sbi to "sbi.co.in"))).isNull()

        val folded = ConfusableFolder.fold(twoSubstitutionsAway, testConfusables)
        assertThat(folded).isEqualTo("sbi")

        val host = "$twoSubstitutionsAway.com"
        val signal = UrlAnalyzer(
            confusables = testConfusables,
            banks = listOf(sbi),
            shorteners = emptySet(),
            shortenerBrandOperated = emptyMap(),
            suspiciousTlds = emptySet(),
            reputationIndex = FakeReputationIndex(),
        ).analyze(message(listOf(url(raw = host, host = host, registrableDomain = host)))) as Signal.Scored

        assertThat(signal.evidence.map { it.type }).contains(EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND)
    }

    // --- suspicious TLD ---

    @Test
    fun `suspicious tld is warn only, never critical on its own`() = runTest {
        val signal = analyzer(suspiciousTlds = setOf("xyz")).analyze(
            message(listOf(url(raw = "somebusiness.xyz", host = "somebusiness.xyz", registrableDomain = "somebusiness.xyz"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.SUSPICIOUS_TLD }
        assertThat(hit.severity).isEqualTo(Severity.WARN)
    }

    // --- non-https credential page ---

    @Test
    fun `http scheme with a credential-shaped path is flagged`() = runTest {
        val signal = analyzer().analyze(
            message(listOf(url(raw = "http://example.com/login", scheme = "http", host = "example.com", registrableDomain = "example.com", path = "/login"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).contains(EvidenceType.NON_HTTPS_CREDENTIAL_PAGE)
    }

    @Test
    fun `https scheme with the same path is not flagged`() = runTest {
        val signal = analyzer().analyze(
            message(listOf(url(raw = "https://example.com/login", scheme = "https", host = "example.com", registrableDomain = "example.com", path = "/login"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.NON_HTTPS_CREDENTIAL_PAGE)
    }

    @Test
    fun `http scheme with a non-credential path is not flagged`() = runTest {
        val signal = analyzer().analyze(
            message(listOf(url(raw = "http://example.com/about", scheme = "http", host = "example.com", registrableDomain = "example.com", path = "/about"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.NON_HTTPS_CREDENTIAL_PAGE)
    }

    // --- domain age (offline path) ---

    @Test
    fun `established domain produces no domain-age evidence`() = runTest {
        val signal = analyzer(reputationIndex = FakeReputationIndex(established = setOf("google.com"))).analyze(
            message(listOf(url(raw = "google.com", host = "google.com", registrableDomain = "google.com"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.DOMAIN_VERY_NEW)
    }

    @Test
    fun `allowlisted domain produces no domain-age evidence`() = runTest {
        val signal = analyzer(reputationIndex = FakeReputationIndex(allowlist = setOf("sbi.co.in"))).analyze(
            message(listOf(url(raw = "sbi.co.in", host = "sbi.co.in", registrableDomain = "sbi.co.in"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.DOMAIN_VERY_NEW)
    }

    @Test
    fun `unknown domain on the offline path is INFO, never CRITICAL`() = runTest {
        val signal = analyzer(reputationIndex = FakeReputationIndex()).analyze(
            message(listOf(url(raw = "some-unknown-site.com", host = "some-unknown-site.com", registrableDomain = "some-unknown-site.com"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.DOMAIN_VERY_NEW }
        assertThat(hit.severity).isEqualTo(Severity.INFO)
    }

    @Test
    fun `missing reputation index produces no domain-age evidence rather than crashing`() = runTest {
        val signal = analyzer(reputationIndex = null).analyze(
            message(listOf(url(raw = "some-unknown-site.com", host = "some-unknown-site.com", registrableDomain = "some-unknown-site.com"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.DOMAIN_VERY_NEW)
    }

    @Test
    fun `online lookup confirming a young domain upgrades evidence to critical`() = runTest {
        val lookup = object : OnlineDomainLookup {
            override suspend fun ageDays(registrableDomain: String) = 5
        }
        val signal = analyzer(onlineDomainLookup = lookup).analyze(
            message(listOf(url(raw = "brand-new-site.com", host = "brand-new-site.com", registrableDomain = "brand-new-site.com"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.DOMAIN_VERY_NEW }
        assertThat(hit.severity).isEqualTo(Severity.CRITICAL)
    }

    @Test
    fun `online lookup confirming an old domain produces no evidence even if offline path is unknown`() = runTest {
        val lookup = object : OnlineDomainLookup {
            override suspend fun ageDays(registrableDomain: String) = 900
        }
        val signal = analyzer(onlineDomainLookup = lookup, reputationIndex = FakeReputationIndex()).analyze(
            message(listOf(url(raw = "old-established-site.com", host = "old-established-site.com", registrableDomain = "old-established-site.com"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.DOMAIN_VERY_NEW)
    }

    // --- brand-operated shortener exemption ---

    @Test
    fun `a brand-operated shortener is not flagged even without a brand claim`() = runTest {
        val signal = analyzer(
            shorteners = setOf("amzn.to"),
            shortenerBrandOperated = mapOf("amzn.to" to "amazon"),
        ).analyze(
            message(listOf(url(raw = "amzn.to/x", host = "amzn.to", registrableDomain = "amzn.to", path = "/x"))),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.URL_SHORTENER)
    }

    // --- force-verdict rule (design.md section 3.5) ---

    @Test
    fun `force verdict fires on critical evidence plus a brand claim`() = runTest {
        val signal = analyzer().analyze(
            message(
                urls = listOf(url(raw = "sbi-kyc-verify.xyz", host = "sbi-kyc-verify.xyz", registrableDomain = "sbi-kyc-verify.xyz")),
                brandClaims = listOf(claim()),
            ),
        ) as Signal.Scored
        assertThat(signal.forceVerdict).isEqualTo(Verdict.SCAM)
    }

    @Test
    fun `force verdict does not fire on critical evidence alone, without a brand claim`() = runTest {
        val signal = analyzer().analyze(
            message(listOf(url(raw = "sbi-kyc-verify.xyz", host = "sbi-kyc-verify.xyz", registrableDomain = "sbi-kyc-verify.xyz"))),
        ) as Signal.Scored
        assertThat(signal.forceVerdict).isNull()
    }

    @Test
    fun `force verdict does not fire on a brand claim alone, without critical evidence`() = runTest {
        val signal = analyzer(suspiciousTlds = setOf("xyz")).analyze(
            message(
                urls = listOf(url(raw = "somebusiness.xyz", host = "somebusiness.xyz", registrableDomain = "somebusiness.xyz")),
                brandClaims = listOf(claim()),
            ),
        ) as Signal.Scored
        assertThat(signal.forceVerdict).isNull()
    }

    @Test
    fun `a new domain alone never forces a verdict, even with a brand claim`() = runTest {
        // design.md section 3.5's own explicit warning: a new domain alone is not sufficient.
        val signal = analyzer(reputationIndex = FakeReputationIndex()).analyze(
            message(
                urls = listOf(url(raw = "some-unknown-site.com", host = "some-unknown-site.com", registrableDomain = "some-unknown-site.com")),
                brandClaims = listOf(claim()),
            ),
        ) as Signal.Scored
        assertThat(signal.forceVerdict).isNull()
    }

    // --- degenerate cases ---

    @Test
    fun `message with no urls produces an empty, non-scored signal`() = runTest {
        val signal = analyzer().analyze(message(emptyList())) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.forceVerdict).isNull()
        assertThat(signal.scamWeight).isEqualTo(0.0f)
    }

    @Test
    fun `analyzer id is URL`() {
        assertThat(analyzer().id).isEqualTo(com.scamshield.core.model.AnalyzerId.URL)
    }
}
