package com.scamshield.core.analysis.language

import com.google.common.truth.Truth.assertThat
import com.scamshield.core.analysis.ingest.TextNormalizer
import com.scamshield.core.model.Language
import org.junit.jupiter.api.Test

class LanguageDetectorTest {

    private fun detect(text: String) = LanguageDetector.detect(TextNormalizer.normalize(text))

    @Test
    fun `plain english message is detected as EN`() {
        assertThat(detect("Your OTP is 445566, do not share it with anyone.")).isEqualTo(Language.EN)
    }

    @Test
    fun `genuine english bank sms is not pulled toward HI_LATN by loanwords`() {
        // "bank", "account", "number" are excluded from the lexicon precisely so this stays EN.
        assertThat(detect("Your bank account number ending 4521 was debited. Call us if this was not you."))
            .isEqualTo(Language.EN)
        assertThat(detect("You have won a lottery! Pay the processing fees to claim your prize."))
            .isEqualTo(Language.EN)
    }

    @Test
    fun `devanagari script message is detected as HI`() {
        assertThat(detect("आपका खाता तुरंत अपडेट करें अन्यथा बंद हो जाएगा")).isEqualTo(Language.HI)
    }

    @Test
    fun `devanagari message with a marathi marker is detected as MR`() {
        assertThat(detect("तुमचे खाते आत्ताच अपडेट करा, नाहीतर बंद होईल")).isEqualTo(Language.MR)
    }

    @Test
    fun `bengali script message is detected as BN`() {
        assertThat(detect("আপনার অ্যাকাউন্ট এখনই আপডেট করুন অন্যথায় বন্ধ হয়ে যাবে")).isEqualTo(Language.BN)
    }

    @Test
    fun `tamil script message is detected as TA`() {
        assertThat(detect("உங்கள் கணக்கை உடனடியாக புதுப்பிக்கவும் இல்லையெனில் நிறுத்தப்படும்")).isEqualTo(Language.TA)
    }

    @Test
    fun `telugu script message is detected as TE`() {
        assertThat(detect("మీ ఖాతాను వెంటనే నవీకరించండి లేకపోతే మూసివేయబడుతుంది")).isEqualTo(Language.TE)
    }

    @Test
    fun `romanized hindi with two or more markers is detected as HI_LATN`() {
        assertThat(detect("aapka khata turant band ho jayega, abhi update karein"))
            .isEqualTo(Language.HI_LATN)
    }

    @Test
    fun `a single romanized hindi word embedded in english is not enough for HI_LATN`() {
        // Only one marker hit ("turant"); the >= 2 threshold exists so a single loanword or
        // coincidental match does not flip an otherwise-English message.
        assertThat(detect("Please respond turant to this message about your delivery"))
            .isEqualTo(Language.EN)
    }

    @Test
    fun `code mixed message with two markers is still HI_LATN, not split`() {
        // design.md section 2.3: language selects the explanation string set only, and a
        // code-mixed message routes as a whole, not per-clause.
        assertThat(detect("Dear customer, aapka account block ho jayega, verify kare turant"))
            .isEqualTo(Language.HI_LATN)
    }

    @Test
    fun `emoji-only message is UNKNOWN`() {
        assertThat(detect("😀😀😀")).isEqualTo(Language.UNKNOWN)
    }

    @Test
    fun `digits-only message is UNKNOWN`() {
        assertThat(detect("445566")).isEqualTo(Language.UNKNOWN)
    }

    @Test
    fun `empty message is UNKNOWN`() {
        assertThat(detect("")).isEqualTo(Language.UNKNOWN)
    }

    @Test
    fun `whitespace-only message is UNKNOWN`() {
        assertThat(detect("   \n\t  ")).isEqualTo(Language.UNKNOWN)
    }

    @Test
    fun `a small devanagari fragment below the 20 percent threshold does not route as HI`() {
        // A single 3-codepoint Devanagari word inside a much longer English sentence stays
        // well under the 20% threshold, so this must not route as HI.
        val text = "Your parcel is on hold at the depot, reply हाँ to confirm delivery today please"
        assertThat(detect(text)).isNotEqualTo(Language.HI)
    }
}
