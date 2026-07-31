package com.scamshield.core.data.rulepack

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RulePackLoaderTest {

    private class FakeAssetSource(
        private val text: Map<String, String>,
        private val bytes: Map<String, ByteArray> = emptyMap(),
    ) : RulePackAssetSource {
        override fun readText(fileName: String): String =
            text[fileName] ?: throw java.io.IOException("no such fixture file: $fileName")

        override fun readBytes(fileName: String): ByteArray =
            bytes[fileName] ?: throw java.io.IOException("no such fixture file: $fileName")
    }

    private val validBanks = """{"schema_version":1,"brands":[
        {"id":"sbi","display_name":"State Bank of India","aliases":["sbi"],
         "domains":["sbi.co.in"],"dlt_headers":["SBIINB"]}]}"""
    private val validShorteners = """{"schema_version":1,"shorteners":["bit.ly"]}"""
    private val validTyposquat = """{"schema_version":1,"single_char_folds":{},"sequence_folds":{},
        "distance":{"short_label_max_length":6,"short_label_distance":1,"long_label_distance":2},
        "suspicious_tlds":["xyz"]}"""
    private val validPatterns = """{"schema_version":1,"patterns":[]}"""
    private val validMeta = """{"pack_version":"v1","schema_version":1,"generated_at":"2026-01-01"}"""
    private val validPsl = "com\nco.in\ngov.in\n"

    private fun validFiles() = mapOf(
        "meta.json" to validMeta,
        "banks.json" to validBanks,
        "shorteners.json" to validShorteners,
        "typosquat.json" to validTyposquat,
        "patterns.json" to validPatterns,
        "public_suffix_list.dat" to validPsl,
    )

    @Test
    fun `a fully valid pack loads without falling back`() {
        val loaded = RulePackLoader(FakeAssetSource(validFiles())).load()
        assertThat(loaded.isBundledFallback).isFalse()
        assertThat(loaded.rulePack.banks.map { it.id }).containsExactly("sbi")
        assertThat(loaded.rulePack.meta.version).isEqualTo("v1")
        assertThat(loaded.publicSuffixList.registrableDomain("sbi.co.in")).isEqualTo("sbi.co.in")
        assertThat(loaded.reputationIndex).isNull() // no reputation.bin fixture supplied
    }

    @Test
    fun `a missing banks json falls back to the default pack wholesale`() {
        val files = validFiles() - "banks.json"
        val loaded = RulePackLoader(FakeAssetSource(files)).load()
        assertThat(loaded.isBundledFallback).isTrue()
        assertThat(loaded.rulePack).isEqualTo(DefaultRulePack.pack)
    }

    @Test
    fun `a malformed patterns json falls back to the default pack, not a partial one`() {
        val files = validFiles() + ("patterns.json" to "{ not valid json")
        val loaded = RulePackLoader(FakeAssetSource(files)).load()
        assertThat(loaded.isBundledFallback).isTrue()
        // Falling back is whole-pack: even banks.json, which parsed fine on its own, is not
        // mixed into a partially-loaded result -- architecture.md section 11.
        assertThat(loaded.rulePack.banks).isNotEqualTo(listOf("sbi"))
        assertThat(loaded.rulePack).isEqualTo(DefaultRulePack.pack)
    }

    @Test
    fun `a wrong schema_version falls back to the default pack`() {
        val files = validFiles() + ("banks.json" to """{"schema_version":2,"brands":[]}""")
        val loaded = RulePackLoader(FakeAssetSource(files)).load()
        assertThat(loaded.isBundledFallback).isTrue()
    }

    @Test
    fun `a missing public suffix list degrades to an empty rule set, not a whole-pack fallback`() {
        val files = validFiles() - "public_suffix_list.dat"
        val loaded = RulePackLoader(FakeAssetSource(files)).load()
        assertThat(loaded.isBundledFallback).isFalse()
        // Empty rule set still functions via PSL's own implicit "last label is the suffix"
        // fallback rule -- see PublicSuffixListParser's doc comment.
        assertThat(loaded.publicSuffixList.registrableDomain("example.com")).isEqualTo("example.com")
    }

    @Test
    fun `a missing reputation bin degrades reputationIndex to null, not a whole-pack fallback`() {
        val loaded = RulePackLoader(FakeAssetSource(validFiles())).load()
        assertThat(loaded.isBundledFallback).isFalse()
        assertThat(loaded.reputationIndex).isNull()
    }

    @Test
    fun `a corrupt reputation bin degrades reputationIndex to null rather than throwing`() {
        val files = validFiles()
        val bytes = mapOf("reputation.bin" to byteArrayOf(1, 2, 3))
        val loaded = RulePackLoader(FakeAssetSource(files, bytes)).load()
        assertThat(loaded.isBundledFallback).isFalse()
        assertThat(loaded.reputationIndex).isNull()
    }
}
