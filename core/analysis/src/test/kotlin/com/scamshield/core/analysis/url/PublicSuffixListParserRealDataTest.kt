package com.scamshield.core.analysis.url

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runs the parser against the real bundled Public Suffix List snapshot, not a hand-built
 * subset -- [PublicSuffixListParserTest] proves the algorithm is correct in isolation, this
 * proves the actual asset the app ships behaves the same way for the cases design.md and
 * implementation.md name explicitly.
 *
 * The asset is a build product (`rulepack/build_rulepack.py`, gitignored) rather than
 * something this test module depends on directly, so a checkout that has not run
 * `./gradlew buildRulepack` yet must skip rather than fail -- this is a real-data
 * confirmation, not the source of correctness truth.
 */
class PublicSuffixListParserRealDataTest {

    private lateinit var psl: PublicSuffixListParser

    @BeforeEach
    fun setUp() {
        val assetFile = locateAsset()
        assumeTrue(assetFile != null && assetFile.exists(), "rulepack asset not built; run ./gradlew buildRulepack")
        psl = PublicSuffixListParser.parse(assetFile!!.readLines())
    }

    private fun locateAsset(): File? {
        // Walk upward from the working directory to the repo root regardless of whether
        // Gradle invoked this from the module dir or the root.
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/assets/rulepack/v1/public_suffix_list.dat")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `co in and gov in resolve as two-label suffixes in the real snapshot`() {
        assertThat(psl.registrableDomain("onlinesbi.co.in")).isEqualTo("onlinesbi.co.in")
        assertThat(psl.registrableDomain("portal.incometax.gov.in")).isEqualTo("incometax.gov.in")
        assertThat(psl.registrableDomain("sbi.co.in")).isEqualTo("sbi.co.in")
    }

    @Test
    fun `ordinary com domain resolves correctly`() {
        assertThat(psl.registrableDomain("www.google.com")).isEqualTo("google.com")
    }

    @Test
    fun `unlisted-looking scam domain still resolves via fallback`() {
        assertThat(psl.registrableDomain("sbi-kyc-verify.xyz")).isEqualTo("sbi-kyc-verify.xyz")
    }

    @Test
    fun `ip literal has no registrable domain`() {
        assertThat(psl.registrableDomain("192.168.1.1")).isNull()
    }

    @Test
    fun `punycode host resolves as ascii`() {
        assertThat(psl.registrableDomain("xn--sb-xkc.com")).isEqualTo("xn--sb-xkc.com")
    }
}
