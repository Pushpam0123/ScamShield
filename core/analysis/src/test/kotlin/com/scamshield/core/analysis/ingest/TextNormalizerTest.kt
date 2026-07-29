package com.scamshield.core.analysis.ingest

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TextNormalizerTest {

    @Test
    fun `lowercases using Locale ROOT semantics`() {
        assertThat(TextNormalizer.normalize("SBI KYC UPDATE")).isEqualTo("sbi kyc update")
    }

    @Test
    fun `strips zero-width characters used to break keyword matching`() {
        // O + ZWSP + T + ZWSP + P, exactly the design.md section 2.1 evasion example.
        val evasive = "O​T​P"
        assertThat(TextNormalizer.normalize(evasive)).isEqualTo("otp")
    }

    @Test
    fun `strips all five zero-width code points`() {
        val text = "a​b‌c‍d⁠e﻿f"
        assertThat(TextNormalizer.normalize(text)).isEqualTo("abcdef")
    }

    @Test
    fun `folds confusable whitespace to a single ascii space`() {
        val text = "share the otp now"
        assertThat(TextNormalizer.normalize(text)).isEqualTo("share the otp now")
    }

    @Test
    fun `collapses runs of whitespace and trims`() {
        assertThat(TextNormalizer.normalize("  too   many    spaces  ")).isEqualTo("too many spaces")
    }

    @Test
    fun `collapses newlines and tabs into a single space`() {
        assertThat(TextNormalizer.normalize("line one\n\nline\ttwo")).isEqualTo("line one line two")
    }

    @Test
    fun `preserves digits punctuation and emoji`() {
        val text = "Pay Rs.500 now!!! 😀"
        assertThat(TextNormalizer.normalize(text)).isEqualTo("pay rs.500 now!!! 😀")
    }

    @Test
    fun `does not use device locale for casing`() {
        // The Turkish-locale trap: Locale.ROOT must map I to i, never dotless-i.
        assertThat(TextNormalizer.normalize("INSTALL")).isEqualTo("install")
    }

    @Test
    fun `NFKC normalizes compatibility forms`() {
        // Fullwidth digits and letters (design.md's own typosquat.json example) collapse
        // to their ASCII compatibility form under NFKC.
        assertThat(TextNormalizer.normalize("ＡＢＣ")).isEqualTo("abc")
    }

    @Test
    fun `empty and whitespace-only input normalize to empty string`() {
        assertThat(TextNormalizer.normalize("")).isEqualTo("")
        assertThat(TextNormalizer.normalize("   \n\t  ")).isEqualTo("")
    }
}
