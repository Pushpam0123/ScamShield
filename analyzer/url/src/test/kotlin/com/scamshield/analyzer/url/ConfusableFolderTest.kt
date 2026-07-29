package com.scamshield.analyzer.url

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.ConfusableTable
import org.junit.jupiter.api.Test

class ConfusableFolderTest {

    // Cyrillic а (U+0430) -> a, dotless i (U+0131) -> i -- design.md section 3.3's own examples.
    private val table = ConfusableTable(
        singleCharFolds = mapOf('а' to 'a', 'ı' to 'i'),
        sequenceFolds = mapOf("rn" to "m"),
        shortLabelDistance = 1,
        longLabelDistance = 2,
        shortLabelMaxLength = 6,
    )

    @Test
    fun `folds a single confusable character`() {
        assertThat(ConfusableFolder.fold("sbı", table)).isEqualTo("sbi")
    }

    @Test
    fun `folds a cyrillic look-alike`() {
        assertThat(ConfusableFolder.fold("pаytm", table)).isEqualTo("paytm")
    }

    @Test
    fun `folds a sequence after single-character folding`() {
        assertThat(ConfusableFolder.fold("rn", table)).isEqualTo("m")
    }

    @Test
    fun `leaves ordinary ascii untouched`() {
        assertThat(ConfusableFolder.fold("sbi", table)).isEqualTo("sbi")
    }

    @Test
    fun `empty label folds to empty`() {
        assertThat(ConfusableFolder.fold("", table)).isEqualTo("")
    }
}
