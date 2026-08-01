package com.scamshield.core.data.rulepack

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.Language
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Severity
import org.junit.Test

class RulePackJsonParserTest {

    @Test
    fun `parses banks json into BankEntry list`() {
        val json = """
            {"schema_version":1,"brands":[
              {"id":"sbi","display_name":"State Bank of India","aliases":["sbi","state bank"],
               "domains":["sbi.co.in"],"dlt_headers":["SBIINB"]}
            ]}
        """.trimIndent()
        val banks = RulePackJsonParser.parseBanks(json)
        assertThat(banks).hasSize(1)
        assertThat(banks[0].id).isEqualTo("sbi")
        assertThat(banks[0].displayName).isEqualTo("State Bank of India")
        assertThat(banks[0].aliases).containsExactly("sbi", "state bank")
        assertThat(banks[0].domains).containsExactly("sbi.co.in")
        assertThat(banks[0].dltHeaders).containsExactly("SBIINB")
    }

    @Test
    fun `parses shorteners json including brand_operated`() {
        val json = """
            {"schema_version":1,"shorteners":["bit.ly","amzn.to"],
             "brand_operated":{"amzn.to":"amazon"}}
        """.trimIndent()
        val (shorteners, brandOperated) = RulePackJsonParser.parseShorteners(json)
        assertThat(shorteners).containsExactly("bit.ly", "amzn.to")
        assertThat(brandOperated).containsExactly("amzn.to", "amazon")
    }

    @Test
    fun `shorteners json without brand_operated defaults to empty`() {
        val json = """{"schema_version":1,"shorteners":["bit.ly"]}"""
        val (_, brandOperated) = RulePackJsonParser.parseShorteners(json)
        assertThat(brandOperated).isEmpty()
    }

    @Test
    fun `parses typosquat json into ConfusableTable and suspicious tlds`() {
        val json = """
            {"schema_version":1,
             "single_char_folds":{"а":"a","і":"i"},
             "sequence_folds":{"rn":"m"},
             "distance":{"short_label_max_length":6,"short_label_distance":1,"long_label_distance":2},
             "suspicious_tlds":["xyz","top"]}
        """.trimIndent()
        val (table, suspiciousTlds) = RulePackJsonParser.parseTyposquat(json)
        assertThat(table.singleCharFolds).containsEntry('а', 'a')
        assertThat(table.singleCharFolds).containsEntry('і', 'i')
        assertThat(table.sequenceFolds).containsEntry("rn", "m")
        assertThat(table.shortLabelMaxLength).isEqualTo(6)
        assertThat(table.shortLabelDistance).isEqualTo(1)
        assertThat(table.longLabelDistance).isEqualTo(2)
        assertThat(suspiciousTlds).containsExactly("xyz", "top")
    }

    @Test
    fun `parses patterns json into PatternRule list with compiled regexes and enum lookups`() {
        val json = """
            {"schema_version":1,"patterns":[
              {"id":"otp_solicit_en","lang":["EN","HI_LATN"],
               "pattern":"share.*otp","suppress_if":"do not share",
               "evidence":"OTP_SOLICITATION","severity":"CRITICAL","weight":0.35,
               "category_hints":{"KYC_PHISHING":0.4,"UPI_COLLECT":0.3}}
            ]}
        """.trimIndent()
        val rules = RulePackJsonParser.parsePatterns(json)
        assertThat(rules).hasSize(1)
        val rule = rules[0]
        assertThat(rule.id).isEqualTo("otp_solicit_en")
        assertThat(rule.languages).containsExactly(Language.EN, Language.HI_LATN)
        assertThat(rule.regex.containsMatchIn("please share your otp")).isTrue()
        assertThat(rule.suppressIf?.containsMatchIn("do not share")).isTrue()
        assertThat(rule.evidence).isEqualTo(EvidenceType.OTP_SOLICITATION)
        assertThat(rule.severity).isEqualTo(Severity.CRITICAL)
        assertThat(rule.weight).isEqualTo(0.35f)
        assertThat(rule.categoryHints).containsEntry(ScamCategory.KYC_PHISHING, 0.4f)
        assertThat(rule.categoryHints).containsEntry(ScamCategory.UPI_COLLECT, 0.3f)
    }

    @Test
    fun `a pattern rule without suppress_if parses with a null suppressIf`() {
        val json = """
            {"schema_version":1,"patterns":[
              {"id":"x","lang":["EN"],"pattern":"x","evidence":"OTP_SOLICITATION",
               "severity":"CRITICAL","weight":0.1,"category_hints":{}}
            ]}
        """.trimIndent()
        assertThat(RulePackJsonParser.parsePatterns(json)[0].suppressIf).isNull()
    }

    @Test
    fun `parses meta json`() {
        val json = """{"pack_version":"v1","schema_version":1,"generated_at":"2026-07-31T23:23:30+00:00"}"""
        val meta = RulePackJsonParser.parseMeta(json)
        assertThat(meta.version).isEqualTo("v1")
        assertThat(meta.schemaVersion).isEqualTo(1)
        assertThat(meta.generatedAt).isEqualTo("2026-07-31T23:23:30+00:00")
    }

    @Test
    fun `unexpected keys in meta json are ignored rather than failing`() {
        // The real emitted meta.json also carries `counts` and `reputation` objects this
        // parser does not model -- see RulePackJsonParser's own doc comment.
        val json = """
            {"pack_version":"v1","schema_version":1,"generated_at":"x",
             "counts":{"brands":150},"reputation":{"bloom_bits":123}}
        """.trimIndent()
        assertThat(RulePackJsonParser.parseMeta(json).version).isEqualTo("v1")
    }

    @Test
    fun `a fold key outside the Basic Multilingual Plane is dropped, not a crash`() {
        // U+1D5EE MATHEMATICAL SANS-SERIF BOLD SMALL A -- one Unicode code point (so Python's
        // `len()` in build_rulepack.py's schema validator accepts it as "one character"), but
        // a UTF-16 surrogate *pair* in Kotlin, so `String.single()` on the raw key throws. This
        // is a real entry in the shipped rulepack/src/typosquat.json; a fixed-length fixture
        // string reproduces the exact failure this parser must survive.
        val supplementaryPlaneA = "𝗮" // "𝗮"
        val json = """
            {"schema_version":1,
             "single_char_folds":{"а":"a","$supplementaryPlaneA":"a"},
             "sequence_folds":{},
             "distance":{"short_label_max_length":6,"short_label_distance":1,"long_label_distance":2},
             "suspicious_tlds":["xyz"]}
        """.trimIndent()
        val (table, _) = RulePackJsonParser.parseTyposquat(json)
        assertThat(table.singleCharFolds).containsEntry('а', 'a')
        assertThat(table.singleCharFolds).hasSize(1) // the supplementary-plane entry was dropped, not crashed on
    }

    @Test(expected = RulePackValidationException::class)
    fun `a schema_version mismatch throws rather than silently proceeding`() {
        RulePackJsonParser.parseBanks("""{"schema_version":2,"brands":[]}""")
    }

    @Test(expected = Exception::class)
    fun `malformed json throws`() {
        RulePackJsonParser.parseBanks("not json at all")
    }
}
