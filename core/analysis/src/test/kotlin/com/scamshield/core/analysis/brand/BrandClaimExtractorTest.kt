package com.scamshield.core.analysis.brand

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.BankEntry
import org.junit.jupiter.api.Test

class BrandClaimExtractorTest {

    private val sbi = BankEntry(
        id = "sbi",
        displayName = "State Bank of India",
        aliases = listOf("sbi", "state bank of india", "state bank"),
        domains = listOf("onlinesbi.sbi", "sbi.co.in"),
        dltHeaders = listOf("SBIINB"),
    )
    private val incomeTax = BankEntry(
        id = "income_tax",
        displayName = "Income Tax Department",
        aliases = listOf("income tax", "itr refund"),
        domains = listOf("incometax.gov.in"),
        dltHeaders = listOf("ITDEPT"),
    )
    private val banks = listOf(sbi, incomeTax)

    @Test
    fun `matches a single-word alias case-insensitively`() {
        val claims = BrandClaimExtractor.extract("Your SBI account is blocked", banks)
        assertThat(claims).hasSize(1)
        assertThat(claims[0].brandId).isEqualTo("sbi")
        assertThat(claims[0].matchedAlias).isEqualTo("sbi")
    }

    @Test
    fun `matches a multi-word alias with a single space`() {
        val claims = BrandClaimExtractor.extract("This is from State Bank of India", banks)
        assertThat(claims.map { it.brandId }).contains("sbi")
    }

    @Test
    fun `matches a multi-word alias across irregular whitespace`() {
        val claims = BrandClaimExtractor.extract("From State   Bank\nof   India today", banks)
        assertThat(claims.map { it.matchedAlias }).contains("state bank of india")
    }

    @Test
    fun `does not match inside a longer token like a DLT header`() {
        // "sbi" must not match inside "SBIINB" -- that is a sender ID, not a brand claim.
        val claims = BrandClaimExtractor.extract("Sent from SBIINB-VM", banks)
        assertThat(claims).isEmpty()
    }

    @Test
    fun `does not match a substring inside an unrelated word`() {
        // "sbi" appears as a literal substring of "xsbix", but not as its own token.
        val claims = BrandClaimExtractor.extract("this is an xsbix codeword", banks)
        assertThat(claims).isEmpty()
    }

    @Test
    fun `no claim in a message naming no brand`() {
        assertThat(BrandClaimExtractor.extract("Hey, are we still on for lunch?", banks)).isEmpty()
    }

    @Test
    fun `multiple distinct brand claims in one message are all returned`() {
        val claims = BrandClaimExtractor.extract("SBI KYC pending, contact Income Tax for refund", banks)
        assertThat(claims.map { it.brandId }).containsExactly("sbi", "income_tax")
    }

    @Test
    fun `repeated mentions of the same brand each produce a claim`() {
        val claims = BrandClaimExtractor.extract("SBI SBI urgent SBI action needed", banks)
        assertThat(claims).hasSize(3)
        assertThat(claims.all { it.brandId == "sbi" }).isTrue()
    }

    @Test
    fun `spans index into the original message exactly`() {
        val text = "Please update your SBI account today"
        val claims = BrandClaimExtractor.extract(text, banks)
        assertThat(claims).hasSize(1)
        val claim = claims[0]
        assertThat(text.substring(claim.spanStart, claim.spanEnd)).isEqualTo("SBI")
    }

    @Test
    fun `claims are sorted by position in the message`() {
        val text = "Income Tax refund via SBI account"
        val claims = BrandClaimExtractor.extract(text, banks)
        assertThat(claims.map { it.brandId }).containsExactly("income_tax", "sbi").inOrder()
    }

    @Test
    fun `empty message produces no claims`() {
        assertThat(BrandClaimExtractor.extract("", banks)).isEmpty()
    }
}
