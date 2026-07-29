package com.scamshield.core.analysis.url

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PublicSuffixListParserTest {

    private val rules = listOf(
        "com",
        "co.in",
        "gov.in",
        "in",
        "org",
        "*.kawasaki.jp",
        "!city.kawasaki.jp",
    )
    private val psl = PublicSuffixListParser.parse(rules)

    @Test
    fun `plain rule gives eTLD plus 1`() {
        assertThat(psl.registrableDomain("sbi.com")).isEqualTo("sbi.com")
        assertThat(psl.registrableDomain("www.sbi.com")).isEqualTo("sbi.com")
    }

    @Test
    fun `co in and gov in are two-label suffixes, not just the last label`() {
        // design.md section 2.2 -- the exact case "last two labels" approximation breaks.
        assertThat(psl.registrableDomain("onlinesbi.co.in")).isEqualTo("onlinesbi.co.in")
        assertThat(psl.registrableDomain("incometax.gov.in")).isEqualTo("incometax.gov.in")
        assertThat(psl.registrableDomain("portal.incometax.gov.in")).isEqualTo("incometax.gov.in")
    }

    @Test
    fun `plain in suffix still works alongside the two-label co in and gov in rules`() {
        assertThat(psl.registrableDomain("sbi.in")).isEqualTo("sbi.in")
    }

    @Test
    fun `host that is exactly the public suffix has no registrable domain`() {
        assertThat(psl.registrableDomain("co.in")).isNull()
        assertThat(psl.registrableDomain("com")).isNull()
    }

    @Test
    fun `unlisted tld falls back to the implicit star rule`() {
        // No rule for "xyz" at all -- the algorithm's implicit fallback treats the last
        // label as the suffix, so a brand-new or unlisted TLD still resolves to something.
        assertThat(psl.registrableDomain("sbi-kyc.xyz")).isEqualTo("sbi-kyc.xyz")
        assertThat(psl.registrableDomain("a.b.sbi-kyc.xyz")).isEqualTo("sbi-kyc.xyz")
    }

    @Test
    fun `wildcard rule treats every subdomain label as part of the suffix`() {
        // "*.kawasaki.jp" makes any-single-label.kawasaki.jp itself a public suffix, so a
        // host that IS exactly that (three labels, nothing to spare) has no registrable
        // domain -- the same "host equals the suffix" case as the plain co.in/com rules.
        assertThat(psl.registrableDomain("foo.kawasaki.jp")).isNull()
        // One more label to the left is what makes it registrable -- and with exactly one
        // label to spare, the whole host IS the registrable domain.
        assertThat(psl.registrableDomain("www.foo.kawasaki.jp")).isEqualTo("www.foo.kawasaki.jp")
        // A further subdomain on top of that leaves the registrable domain unchanged.
        assertThat(psl.registrableDomain("mail.www.foo.kawasaki.jp")).isEqualTo("www.foo.kawasaki.jp")
    }

    @Test
    fun `exception rule carves out a registrable domain the wildcard would otherwise consume`() {
        // The point of "!city.kawasaki.jp": without it, the wildcard rule would make
        // city.kawasaki.jp itself an unregistrable suffix. The exception's public suffix is
        // its own labels minus the leftmost ("kawasaki.jp"), so city.kawasaki.jp becomes a
        // registrable domain in its own right, exactly as Kawasaki city needed it to be.
        assertThat(psl.registrableDomain("city.kawasaki.jp")).isEqualTo("city.kawasaki.jp")
        assertThat(psl.registrableDomain("www.city.kawasaki.jp")).isEqualTo("city.kawasaki.jp")
    }

    @Test
    fun `ipv4 literal host has no registrable domain`() {
        // implementation.md Phase 1's own fixture case: http://192.168.1.1/login
        assertThat(psl.registrableDomain("192.168.1.1")).isNull()
        assertThat(psl.registrableDomain("8.8.8.8")).isNull()
    }

    @Test
    fun `ipv6 literal host has no registrable domain`() {
        assertThat(psl.registrableDomain("::1")).isNull()
        assertThat(psl.registrableDomain("[2001:db8::1]")).isNull()
        assertThat(psl.registrableDomain("fe80::1ff:fe23:4567:890a")).isNull()
    }

    @Test
    fun `an out-of-range numeric host is not mistaken for an ipv4 literal`() {
        // Every octet must be 0..255. "999.1.2.3" is not a valid IP, so it is not swallowed
        // by the IP-literal check -- it falls through to ordinary (fallback) suffix matching,
        // same as any other unlisted host, rather than being incorrectly treated as an IP.
        assertThat(psl.registrableDomain("999.1.2.3")).isEqualTo("2.3")
    }

    @Test
    fun `isRecognizedTld is true only for a genuine plain single-label suffix rule`() {
        assertThat(psl.isRecognizedTld("com")).isTrue()
        assertThat(psl.isRecognizedTld("in")).isTrue()
        assertThat(psl.isRecognizedTld("COM")).isTrue() // case-insensitive
        // "co.in" and "gov.in" are two-label rules in this fixture, not single-label ones.
        assertThat(psl.isRecognizedTld("co")).isFalse()
        assertThat(psl.isRecognizedTld("gov")).isFalse()
        // Ordinary words are not suffixes, however domain-shaped the surrounding text looks.
        assertThat(psl.isRecognizedTld("of")).isFalse()
        assertThat(psl.isRecognizedTld("xyz")).isFalse()
    }

    @Test
    fun `punycode host is treated as ascii directly`() {
        assertThat(psl.registrableDomain("xn--sb-xkc.com")).isEqualTo("xn--sb-xkc.com")
    }

    @Test
    fun `matching is case-insensitive`() {
        assertThat(psl.registrableDomain("SBI.COM")).isEqualTo("sbi.com")
        assertThat(psl.registrableDomain("SBI.Co.In")).isEqualTo("sbi.co.in")
    }

    @Test
    fun `trailing dot is tolerated`() {
        assertThat(psl.registrableDomain("sbi.com.")).isEqualTo("sbi.com")
    }

    @Test
    fun `empty host has no registrable domain`() {
        assertThat(psl.registrableDomain("")).isNull()
        assertThat(psl.registrableDomain(".")).isNull()
    }
}
