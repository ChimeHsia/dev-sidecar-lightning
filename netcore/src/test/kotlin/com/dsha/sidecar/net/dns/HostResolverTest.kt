package com.dsha.sidecar.net.dns

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostResolverTest {
    @Test
    fun `literal detection validates addresses without resolving them`() {
        for (host in listOf("192.0.2.1", "::1", "[2001:db8::1]", "::ffff:192.0.2.1", "fe80::1%eth0")) {
            assertTrue(HostResolver.isIpLiteral(host), host)
        }
        for (host in listOf(
            "999.999.999.999", "01.2.3.4", "host:443", ":", ":::",
            "[garbage]", "[::1", "::1%", "example.test",
        )) {
            assertFalse(HostResolver.isIpLiteral(host), host)
        }
    }
}
