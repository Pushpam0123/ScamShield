package com.scamshield.core.analysis.url

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UrlExtractorTest {

    private val rules = listOf("com", "in", "co.in", "gov.in", "org", "net", "xyz", "ly", "co")
    private val extractor = UrlExtractor(PublicSuffixListParser.parse(rules))

    @Test
    fun `extracts a plain scheme url`() {
        val urls = extractor.extract("Verify now: https://sbi.co.in/verify?id=1")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].scheme).isEqualTo("https")
        assertThat(urls[0].host).isEqualTo("sbi.co.in")
        assertThat(urls[0].registrableDomain).isEqualTo("sbi.co.in")
        assertThat(urls[0].path).isEqualTo("/verify?id=1")
    }

    @Test
    fun `extracts a www prefixed url with no scheme`() {
        val urls = extractor.extract("Go to www.sbi-verify.xyz/kyc now")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].scheme).isNull()
        assertThat(urls[0].host).isEqualTo("www.sbi-verify.xyz")
        assertThat(urls[0].path).isEqualTo("/kyc")
    }

    @Test
    fun `extracts a bare domain with a path, the implementation md fixture case`() {
        val urls = extractor.extract("Update KYC at sbi-verify.xyz/kyc immediately")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].scheme).isNull()
        assertThat(urls[0].host).isEqualTo("sbi-verify.xyz")
        assertThat(urls[0].path).isEqualTo("/kyc")
        assertThat(urls[0].registrableDomain).isEqualTo("sbi-verify.xyz")
    }

    @Test
    fun `extracts a bare domain with no path`() {
        val urls = extractor.extract("Visit bit.ly to continue")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].host).isEqualTo("bit.ly")
        assertThat(urls[0].path).isNull()
    }

    @Test
    fun `bare shortener with a path splits host and path correctly`() {
        val urls = extractor.extract("bit.ly/x2f9 for your refund")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].host).isEqualTo("bit.ly")
        assertThat(urls[0].path).isEqualTo("/x2f9")
    }

    @Test
    fun `homograph host without a scheme is still extracted for downstream homograph detection`() {
        // "sb" + dotless-i (U+0131) + "i.com" -- design.md section 3.3's own example, and it
        // must survive extraction with the exact original characters so the URL analyzer can
        // detect the homograph. This is a Unicode letter, not ASCII, hence the Unicode LABEL.
        val homographHost = "sbı.com"
        val urls = extractor.extract("Login at $homographHost to fix your account")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].host).isEqualTo(homographHost)
    }

    @Test
    fun `punycode host is extracted and flagged`() {
        val urls = extractor.extract("Visit xn--sb-xkc.com to verify")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].host).isEqualTo("xn--sb-xkc.com")
        assertThat(urls[0].isPunycode).isTrue()
    }

    @Test
    fun `ip literal host is extracted with a null registrable domain`() {
        // implementation.md Phase 1's own fixture case.
        val urls = extractor.extract("Login at http://192.168.1.1/login now")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].host).isEqualTo("192.168.1.1")
        assertThat(urls[0].registrableDomain).isNull()
    }

    @Test
    fun `ordinary prose abbreviations are not extracted as urls`() {
        assertThat(extractor.extract("no.of items ordered: 3")).isEmpty()
        assertThat(extractor.extract("e.g. this is an example")).isEmpty()
        assertThat(extractor.extract("i.e. the total amount")).isEmpty()
    }

    @Test
    fun `a domain embedded in an email address is not extracted`() {
        assertThat(extractor.extract("contact us at support@sbi.com for help")).isEmpty()
    }

    @Test
    fun `a decimal number is not extracted`() {
        assertThat(extractor.extract("the rate is 12.5 percent")).isEmpty()
        assertThat(extractor.extract("version 3.14 released")).isEmpty()
    }

    @Test
    fun `message with no url returns an empty list`() {
        assertThat(extractor.extract("Your OTP is 445566, do not share it")).isEmpty()
    }

    @Test
    fun `spans index into the original string exactly`() {
        val text = "Click http://evil.xyz/x now"
        val urls = extractor.extract(text)
        assertThat(urls).hasSize(1)
        val (start, end) = urls[0].spanStart to urls[0].spanEnd
        assertThat(text.substring(start, end)).isEqualTo("http://evil.xyz/x")
    }

    @Test
    fun `multiple urls in one message are all extracted with correct spans`() {
        val text = "Real site sbi.co.in but fake link is http://sbi-kyc.xyz/verify"
        val urls = extractor.extract(text)
        assertThat(urls).hasSize(2)
        assertThat(urls[0].host).isEqualTo("sbi.co.in")
        assertThat(urls[1].host).isEqualTo("sbi-kyc.xyz")
        for (url in urls) {
            assertThat(text.substring(url.spanStart, url.spanEnd)).isEqualTo(url.raw)
        }
    }

    @Test
    fun `userinfo prefix is stripped from the host`() {
        val urls = extractor.extract("http://user:pass@evil.xyz/login")
        assertThat(urls).hasSize(1)
        assertThat(urls[0].host).isEqualTo("evil.xyz")
    }

    @Test
    fun `credential path is preserved verbatim in the path field`() {
        val urls = extractor.extract("http://sbi-kyc.xyz/login?user=1&otp=2")
        assertThat(urls[0].path).isEqualTo("/login?user=1&otp=2")
    }
}
