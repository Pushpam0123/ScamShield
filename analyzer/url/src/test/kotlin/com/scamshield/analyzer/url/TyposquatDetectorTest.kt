package com.scamshield.analyzer.url

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.ConfusableTable
import org.junit.jupiter.api.Test

class TyposquatDetectorTest {

    private val sbi = BankEntry("sbi", "State Bank of India", listOf("sbi"), listOf("sbi.co.in"), listOf("SBIINB"))
    private val hdfc = BankEntry("hdfc", "HDFC Bank", listOf("hdfc"), listOf("hdfcbank.com"), listOf("HDFCBK"))
    private val knownDomains = listOf(sbi to "sbi.co.in", hdfc to "hdfcbank.com")

    private val distanceConfig = ConfusableTable(
        singleCharFolds = emptyMap(),
        sequenceFolds = emptyMap(),
        shortLabelDistance = 1,
        longLabelDistance = 2,
        shortLabelMaxLength = 6,
    )
    private val detector = TyposquatDetector(distanceConfig)

    @Test
    fun `exact match on the real domain is not a hit`() {
        assertThat(detector.detect("sbi", knownDomains)).isNull()
    }

    @Test
    fun `distance within the short-label threshold hits`() {
        // "sbi" is length 3, threshold 1. "sb1"/"sib" are distance 1 away.
        val hit = detector.detect("sib", knownDomains)
        assertThat(hit).isNotNull()
        assertThat(hit!!.brand.id).isEqualTo("sbi")
    }

    @Test
    fun `distance beyond the short-label threshold does not hit`() {
        assertThat(detector.detect("xyz", knownDomains)).isNull()
    }

    @Test
    fun `long label gets the wider threshold`() {
        // "hdfcbank" is length 8, threshold 2. "hdfcbnak" is one adjacent transposition away.
        val hit = detector.detect("hdfcbnak", knownDomains)
        assertThat(hit?.brand?.id).isEqualTo("hdfc")
    }

    @Test
    fun `substring rule catches the design md fixture case`() {
        // implementation.md Phase 1's own example.
        val hit = detector.detect("sbi-kyc-verify", knownDomains)
        assertThat(hit).isNotNull()
        assertThat(hit!!.brand.id).isEqualTo("sbi")
        assertThat(hit.matchedDomain).isEqualTo("sbi.co.in")
    }

    @Test
    fun `substring rule does not fire when the label equals the brand`() {
        assertThat(detector.detect("sbi", knownDomains)).isNull()
    }

    @Test
    fun `squash rule strips punctuation and digits before comparing`() {
        val hit = detector.detect("s-b-i", knownDomains)
        assertThat(hit?.brand?.id).isEqualTo("sbi")
    }

    @Test
    fun `squash rule catches an inserted digit`() {
        val hit = detector.detect("sb1i", knownDomains)
        assertThat(hit?.brand?.id).isEqualTo("sbi")
    }

    @Test
    fun `unrelated label is not a hit`() {
        assertThat(detector.detect("flipkart", knownDomains)).isNull()
    }

    @Test
    fun `empty label is not a hit`() {
        assertThat(detector.detect("", knownDomains)).isNull()
    }

    @Test
    fun `squash strips hyphens dots underscores and digits`() {
        assertThat(TyposquatDetector.squash("s-b.i_2")).isEqualTo("sbi")
    }
}
