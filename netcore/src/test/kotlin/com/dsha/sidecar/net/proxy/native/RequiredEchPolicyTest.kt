package com.dsha.sidecar.net.proxy.native

import java.net.Socket
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredEchPolicyTest {
    @Test
    fun ordinaryConnectorRejectsRequiredEchBeforeTlsAndClosesTransport() {
        val raw = Socket()
        assertThrows(SSLHandshakeException::class.java) {
            NativeTlsConnector().connect(raw, NativeTlsPolicy("origin.test", echRequired = true), 100)
        }
        assertTrue(raw.isClosed)
    }

    @Test
    fun compositeConnectorCannotOverrideRequiredEchWithOrdinaryTls() {
        val raw = Socket()
        val dialer = ConscryptNativeTlsDialer(NativeEchConfigResolver { NativeEchSelection.NotRequired })
        val failure = assertThrows(NativeEchDialException::class.java) {
            dialer.connect(raw, NativeTlsPolicy("origin.test", echRequired = true), 100)
        }
        assertEquals(NativeEchDialFailure.REQUIRED_CONFIG_UNAVAILABLE, failure.failure)
        assertTrue(raw.isClosed)
    }
}
