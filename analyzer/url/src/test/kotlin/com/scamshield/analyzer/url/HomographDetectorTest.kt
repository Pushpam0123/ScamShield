package com.scamshield.analyzer.url

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HomographDetectorTest {

    @Test
    fun `pure latin label has no mixed script`() {
        assertThat(HomographDetector.hasMixedScript("sbi")).isFalse()
    }

    @Test
    fun `pure cyrillic label has no mixed script`() {
        assertThat(HomographDetector.hasMixedScript("сбер")).isFalse()
    }

    @Test
    fun `latin mixed with cyrillic is a mixed script label`() {
        // Cyrillic а (U+0430) inside an otherwise-Latin label.
        assertThat(HomographDetector.hasMixedScript("pаytm")).isTrue()
    }

    @Test
    fun `latin mixed with greek is a mixed script label`() {
        // Greek alpha (U+03B1) standing in for 'a'.
        assertThat(HomographDetector.hasMixedScript("pαytm")).isTrue()
    }

    @Test
    fun `latin mixed with armenian is a mixed script label`() {
        // Armenian letter oh (U+0585, transliterates as 'o') standing in for 'o'.
        assertThat(HomographDetector.hasMixedScript("gօogle")).isTrue()
    }

    @Test
    fun `digits alongside latin are not a mixed script`() {
        assertThat(HomographDetector.hasMixedScript("sbi123")).isFalse()
    }

    @Test
    fun `empty label has no mixed script`() {
        assertThat(HomographDetector.hasMixedScript("")).isFalse()
    }
}
