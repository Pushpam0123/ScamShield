package com.scamshield.analyzer.url

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DamerauLevenshteinTest {

    @Test
    fun `identical strings have distance zero`() {
        assertThat(DamerauLevenshtein.distance("sbi", "sbi")).isEqualTo(0)
    }

    @Test
    fun `empty string distance is the other string's length`() {
        assertThat(DamerauLevenshtein.distance("", "sbi")).isEqualTo(3)
        assertThat(DamerauLevenshtein.distance("sbi", "")).isEqualTo(3)
        assertThat(DamerauLevenshtein.distance("", "")).isEqualTo(0)
    }

    @Test
    fun `single substitution is distance one`() {
        assertThat(DamerauLevenshtein.distance("sbi", "sbo")).isEqualTo(1)
    }

    @Test
    fun `single insertion is distance one`() {
        assertThat(DamerauLevenshtein.distance("sbi", "sbix")).isEqualTo(1)
    }

    @Test
    fun `single deletion is distance one`() {
        assertThat(DamerauLevenshtein.distance("hdfc", "hdf")).isEqualTo(1)
    }

    @Test
    fun `adjacent transposition is a single edit, unlike plain levenshtein`() {
        // "sbi" -> "sib" is one adjacent swap. Plain Levenshtein would score this 2.
        assertThat(DamerauLevenshtein.distance("sbi", "sib")).isEqualTo(1)
        assertThat(DamerauLevenshtein.distance("paytm", "paytm")).isEqualTo(0)
        assertThat(DamerauLevenshtein.distance("paytm", "paymt")).isEqualTo(1)
    }

    @Test
    fun `distance is symmetric`() {
        assertThat(DamerauLevenshtein.distance("icici", "icic")).isEqualTo(
            DamerauLevenshtein.distance("icic", "icici"),
        )
    }

    @Test
    fun `unrelated strings have a large distance`() {
        assertThat(DamerauLevenshtein.distance("sbi", "flipkart")).isAtLeast(6)
    }

    @Test
    fun `two substitutions is distance two`() {
        assertThat(DamerauLevenshtein.distance("hdfcbank", "hdgcbenk")).isEqualTo(2)
    }
}
