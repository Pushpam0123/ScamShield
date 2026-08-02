package com.scamshield.analyzer.classifier

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClassifierMetaTest {

    @Test
    fun `parses temperature and ignores diagnostic fields`() {
        // The exact shape ml/calibrate.py writes, extra keys and all.
        val text =
            """
            {
              "nll_after": 0.2599250376224518,
              "nll_before": 0.4244439899921417,
              "temperature": 0.24920925498008728,
              "val_rows": 27
            }
            """.trimIndent()

        val meta = parseClassifierMeta(text)

        assertThat(meta.temperature).isWithin(1e-6f).of(0.24920925f)
    }

    @Test(expected = Exception::class)
    fun `throws on malformed json rather than defaulting the temperature`() {
        parseClassifierMeta("{ not json")
    }
}
