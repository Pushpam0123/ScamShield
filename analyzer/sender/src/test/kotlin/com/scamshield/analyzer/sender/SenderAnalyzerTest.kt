package com.scamshield.analyzer.sender

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.AnalyzerId
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.BrandClaim
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.Severity
import com.scamshield.core.model.Signal
import com.scamshield.core.model.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SenderAnalyzerTest {

    private val sbi = BankEntry(
        id = "sbi",
        displayName = "State Bank of India",
        aliases = listOf("sbi"),
        domains = listOf("sbi.co.in"),
        dltHeaders = listOf("SBIINB"),
    )

    // A many-to-many header, matching the real GOOGLE / google+gpay case from banks.json.
    private val google = BankEntry(
        id = "google",
        displayName = "Google",
        aliases = listOf("google"),
        domains = listOf("google.com"),
        dltHeaders = listOf("GOOGLE"),
    )
    private val gpay = BankEntry(
        id = "gpay",
        displayName = "Google Pay",
        aliases = listOf("google pay", "gpay"),
        domains = listOf("pay.google.com"),
        dltHeaders = listOf("GOOGLE"),
    )

    private fun analyzer(banks: List<BankEntry> = listOf(sbi)) = SenderAnalyzer(banks)

    private fun claim(brandId: String = "sbi", displayName: String = "State Bank of India") =
        BrandClaim(brandId, displayName, brandId, 0, brandId.length)

    private fun message(senderHint: String?, brandClaims: List<BrandClaim> = emptyList()) = NormalizedMessage(
        id = MessageId("t"),
        original = "test message",
        normalized = "test message",
        urls = emptyList(),
        senderHint = senderHint,
        detectedLanguage = Language.EN,
        brandClaims = brandClaims,
    )

    @Test
    fun `analyzer id is SENDER`() {
        assertThat(analyzer().id).isEqualTo(AnalyzerId.SENDER)
    }

    // --- no brand claim: senderHint alone is never evidence ---

    @Test
    fun `no brand claim and no sender hint produces no evidence`() = runTest {
        val signal = analyzer().analyze(message(senderHint = null)) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.forceVerdict).isNull()
        assertThat(signal.scamWeight).isEqualTo(0.0f)
    }

    @Test
    fun `no brand claim with a bare numeric sender produces no evidence`() = runTest {
        val signal = analyzer().analyze(message(senderHint = "9876543210")) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
    }

    @Test
    fun `no brand claim with a mismatched DLT header produces no evidence`() = runTest {
        val signal = analyzer().analyze(message(senderHint = "VM-HDFCBK")) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
    }

    // --- BRAND_CLAIM_WITHOUT_DLT_HEADER ---

    @Test
    fun `brand claim with null sender hint is INFO, not higher`() = runTest {
        val signal = analyzer().analyze(message(senderHint = null, brandClaims = listOf(claim()))) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.BRAND_CLAIM_WITHOUT_DLT_HEADER }
        assertThat(hit.severity).isEqualTo(Severity.INFO)
        assertThat(signal.forceVerdict).isNull()
    }

    // --- UNREGISTERED_NUMERIC_SENDER ---

    @Test
    fun `brand claim with a bare 10-digit sender is flagged critical and forces SCAM`() = runTest {
        val signal = analyzer().analyze(
            message(senderHint = "9876543210", brandClaims = listOf(claim())),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.UNREGISTERED_NUMERIC_SENDER }
        assertThat(hit.severity).isEqualTo(Severity.CRITICAL)
        assertThat(signal.forceVerdict).isEqualTo(Verdict.SCAM)
    }

    @Test
    fun `a numeric sender that is not exactly 10 digits is not flagged as unregistered`() = runTest {
        val signal = analyzer().analyze(
            message(senderHint = "98765432", brandClaims = listOf(claim())),
        ) as Signal.Scored
        assertThat(signal.evidence.map { it.type }).doesNotContain(EvidenceType.UNREGISTERED_NUMERIC_SENDER)
    }

    // --- DLT_HEADER_MISMATCH ---

    @Test
    fun `brand claim with a known DLT header belonging to a different known brand mismatches`() = runTest {
        val hdfc = BankEntry(
            id = "hdfc",
            displayName = "HDFC Bank",
            aliases = listOf("hdfc"),
            domains = listOf("hdfcbank.com"),
            dltHeaders = listOf("HDFCBK"),
        )
        val signal = analyzer(banks = listOf(sbi, hdfc)).analyze(
            message(senderHint = "AD-HDFCBK", brandClaims = listOf(claim(brandId = "sbi"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.DLT_HEADER_MISMATCH }
        assertThat(hit.severity).isEqualTo(Severity.CRITICAL)
        assertThat(hit.slots["claimed_brand"]).isEqualTo("State Bank of India")
        assertThat(hit.slots["header_brands"]).isEqualTo("HDFC Bank")
        assertThat(signal.forceVerdict).isEqualTo(Verdict.SCAM)
    }

    @Test
    fun `brand claim with a matching DLT header produces no mismatch evidence`() = runTest {
        val signal = analyzer(banks = listOf(sbi)).analyze(
            message(senderHint = "VM-SBIINB", brandClaims = listOf(claim())),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.forceVerdict).isNull()
    }

    @Test
    fun `an unknown DLT header produces no evidence -- the registry is best-effort`() = runTest {
        val signal = analyzer(banks = listOf(sbi)).analyze(
            message(senderHint = "VM-UNKNOWNBRAND", brandClaims = listOf(claim())),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.forceVerdict).isNull()
    }

    // --- many-to-many header (banks.json's GOOGLE / google+gpay case) ---

    @Test
    fun `a header shared by two brands does not mismatch when either brand is claimed`() = runTest {
        val signal = analyzer(banks = listOf(google, gpay)).analyze(
            message(senderHint = "VM-GOOGLE", brandClaims = listOf(claim(brandId = "gpay", displayName = "Google Pay"))),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.forceVerdict).isNull()
    }

    @Test
    fun `a header shared by two brands still mismatches a third, unrelated claimed brand`() = runTest {
        val signal = analyzer(banks = listOf(google, gpay, sbi)).analyze(
            message(senderHint = "VM-GOOGLE", brandClaims = listOf(claim(brandId = "sbi"))),
        ) as Signal.Scored
        val hit = signal.evidence.first { it.type == EvidenceType.DLT_HEADER_MISMATCH }
        assertThat(hit.slots["header_brands"]).isEqualTo("Google, Google Pay")
    }

    // --- degenerate / non-conforming sender hints ---

    @Test
    fun `a sender hint that is neither a bare number nor a valid DLT header produces no evidence`() = runTest {
        val signal = analyzer().analyze(
            message(senderHint = "RandomText123", brandClaims = listOf(claim())),
        ) as Signal.Scored
        assertThat(signal.evidence).isEmpty()
        assertThat(signal.forceVerdict).isNull()
    }
}
