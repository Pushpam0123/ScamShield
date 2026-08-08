package com.scamshield.core.model.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IpLiteralTest {

    @Test
    fun `recognises dotted-quad IPv4`() {
        assertThat(isIpLiteralHost("192.168.1.1")).isTrue()
        assertThat(isIpLiteralHost("8.8.8.8")).isTrue()
    }

    @Test
    fun `rejects out-of-range octets`() {
        assertThat(isIpLiteralHost("999.1.1.1")).isFalse()
        assertThat(isIpLiteralHost("256.0.0.1")).isFalse()
    }

    @Test
    fun `recognises bracketed and bare IPv6`() {
        assertThat(isIpLiteralHost("[::1]")).isTrue()
        assertThat(isIpLiteralHost("fe80::1")).isTrue()
    }

    @Test
    fun `treats ordinary hostnames as non-literals`() {
        assertThat(isIpLiteralHost("example.com")).isFalse()
        assertThat(isIpLiteralHost("sbi.co.in")).isFalse()
        // A numeric-looking label that isn't a full dotted-quad is still a hostname.
        assertThat(isIpLiteralHost("192.168.1")).isFalse()
    }
}
