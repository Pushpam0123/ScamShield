package com.scamshield.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * These are contract tests, not coverage. Each one pins a number that something outside
 * this module depends on, so that changing it fails here loudly rather than silently
 * somewhere expensive.
 */
class TaxonomyTest {

    @Test
    fun `category count matches the classifier head width`() {
        // design.md §6.1: category_logits is [1, 13]. Adding a category without retraining
        // and re-exporting the model would silently misalign every argmax.
        assertThat(ScamCategory.entries).hasSize(13)
    }

    @Test
    fun `NOT_SCAM is the last category`() {
        // The fusion policy maps a SAFE verdict onto NOT_SCAM directly (design.md §7 step 4);
        // the model's category head is trained with NOT_SCAM in the final position.
        assertThat(ScamCategory.entries.last()).isEqualTo(ScamCategory.NOT_SCAM)
    }

    @Test
    fun `every analyzer id is distinct and there are exactly four`() {
        assertThat(AnalyzerId.entries).hasSize(4)
    }

    @Test
    fun `severity orders from least to most serious`() {
        // Evidence ordering (design.md §7 step 5) sorts on ordinal descending.
        assertThat(Severity.INFO.ordinal).isLessThan(Severity.WARN.ordinal)
        assertThat(Severity.WARN.ordinal).isLessThan(Severity.CRITICAL.ordinal)
    }

    @Test
    fun `verdict orders from safest to most dangerous`() {
        assertThat(Verdict.SAFE.ordinal).isLessThan(Verdict.SUSPICIOUS.ordinal)
        assertThat(Verdict.SUSPICIOUS.ordinal).isLessThan(Verdict.SCAM.ordinal)
    }
}
