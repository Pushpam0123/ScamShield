package com.scamshield.core.analysis.ingest

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.analysis.url.PublicSuffixListParser
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.Language
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.MessageSource
import com.scamshield.core.model.RawMessage
import java.time.Instant
import org.junit.jupiter.api.Test

class MessageNormalizerTest {

    private val banks = listOf(
        BankEntry(
            id = "sbi",
            displayName = "State Bank of India",
            aliases = listOf("sbi"),
            domains = listOf("sbi.co.in"),
            dltHeaders = listOf("SBIINB"),
        ),
    )
    private val psl = PublicSuffixListParser.parse(listOf("com", "in", "co.in", "xyz"))
    private val normalizer = MessageNormalizer(UrlExtractor(psl), banks)

    private fun raw(text: String, senderHint: String? = null) = RawMessage(
        id = MessageId("test-id"),
        text = text,
        senderHint = senderHint,
        source = MessageSource.MANUAL,
        receivedAt = Instant.EPOCH,
    )

    @Test
    fun `produces a fully populated NormalizedMessage for a typical scam sms`() {
        val text = "Dear Customer your SBI KYC expire today update sbi-kyc.xyz"
        val result = normalizer.normalize(raw(text, senderHint = "SBIINB"))

        assertThat(result.id).isEqualTo(MessageId("test-id"))
        assertThat(result.original).isEqualTo(text)
        assertThat(result.normalized).isEqualTo(text.lowercase())
        assertThat(result.senderHint).isEqualTo("SBIINB")
        assertThat(result.detectedLanguage).isEqualTo(Language.EN)

        assertThat(result.urls).hasSize(1)
        assertThat(result.urls[0].host).isEqualTo("sbi-kyc.xyz")

        // "sbi" appears twice: the word "SBI" and again as the hyphen-bounded first label of
        // the domain "sbi-kyc.xyz" -- both are genuine standalone-token matches.
        assertThat(result.brandClaims).hasSize(2)
        assertThat(result.brandClaims.all { it.brandId == "sbi" }).isTrue()
    }

    @Test
    fun `original is preserved verbatim even though normalized is transformed`() {
        val text = "  SBI   Alert!!!  "
        val result = normalizer.normalize(raw(text))
        assertThat(result.original).isEqualTo(text)
        assertThat(result.normalized).isEqualTo("sbi alert!!!")
    }

    @Test
    fun `message with no url and no brand claim still normalizes cleanly`() {
        val result = normalizer.normalize(raw("Hey, are we still on for lunch today?"))
        assertThat(result.urls).isEmpty()
        assertThat(result.brandClaims).isEmpty()
        assertThat(result.detectedLanguage).isEqualTo(Language.EN)
    }

    @Test
    fun `empty message normalizes without throwing`() {
        val result = normalizer.normalize(raw(""))
        assertThat(result.normalized).isEqualTo("")
        assertThat(result.urls).isEmpty()
        assertThat(result.brandClaims).isEmpty()
        assertThat(result.detectedLanguage).isEqualTo(Language.UNKNOWN)
    }

    @Test
    fun `hinglish message with a brand claim is detected as HI_LATN`() {
        val result = normalizer.normalize(raw("aapka SBI khata turant band ho jayega"))
        assertThat(result.detectedLanguage).isEqualTo(Language.HI_LATN)
        assertThat(result.brandClaims.map { it.brandId }).contains("sbi")
    }
}
