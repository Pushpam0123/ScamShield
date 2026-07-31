package com.scamshield.analyzer.sender

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DltHeaderFormatTest {

    @ParameterizedTest
    @ValueSource(strings = ["VM-SBIINB", "AD-HDFCBK", "TM-ICICIB", "VK-XYZ", "VM-ABCDEFGHI"])
    fun `well-formed DLT headers are valid`(header: String) {
        assertThat(DltHeaderFormat.isValid(header)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "SBIINB", // no prefix at all
            "V-SBIINB", // prefix too short
            "VMX-SBIINB", // prefix too long
            "VM-AB", // body too short (< 3)
            "VM-ABCDEFGHIJ", // body too long (> 9)
            "vm-sbiinb", // lowercase
            "9876543210", // bare mobile number, not a header
            "",
        ],
    )
    fun `malformed strings are not valid DLT headers`(header: String) {
        assertThat(DltHeaderFormat.isValid(header)).isFalse()
    }

    @Test
    fun `body strips the operator prefix`() {
        assertThat(DltHeaderFormat.body("VM-SBIINB")).isEqualTo("SBIINB")
    }
}
