package com.dsha.sidecar.net.dns

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IpAddressTest {
    @Test
    fun `accepts strict decimal IPv4 octets`() {
        for (address in listOf("0.0.0.0", "1.2.3.4", "192.0.2.1", "255.255.255.255")) {
            assertTrue(IpAddress.isIpv4(address), address)
            assertTrue(IpAddress.isIpLiteral(address), address)
            assertFalse(IpAddress.isIpv6(address), address)
        }
    }

    @Test
    fun `rejects invalid IPv4 syntax and ranges`() {
        for (address in listOf(
            null, "", "256.0.0.1", "1.256.3.4", "1.2.256.4", "1.2.3.256",
            "999.999.999.999", "01.2.3.4", "1.2.3.00", "000.0.0.0",
            "1.2.3", "1.2.3.4.5", "127.1", "2130706433", "0x7f.0.0.1",
            "-1.2.3.4", "+1.2.3.4", "1..3.4", ".1.2.3.4", "1.2.3.4.",
            " 1.2.3.4", "1.2.3.4 ", "1.2.3.4\n", "[1.2.3.4]", "1.2.3.4%eth0",
            "1.2.3.4:80", "example.test", "\uFF11.2.3.4",
        )) {
            assertFalse(IpAddress.isIpv4(address), "$address")
            assertFalse(IpAddress.isIpLiteral(address), "$address")
        }
    }

    @Test
    fun `accepts complete compressed bracketed and mixed IPv6`() {
        for (address in listOf(
            "::", "::1", "2001:db8::", "2001:DB8:0:1:2:3:4:5",
            "1:2:3:4:5:6:7:8", "1::2:3:4:5:6:7", "1:2:3:4:5:6:7::",
            "[::1]", "[2001:db8::1]", "::ffff:192.0.2.1", "::192.0.2.1",
            "1:2:3:4:5:6:192.0.2.1", "[::ffff:192.0.2.1]",
            "fe80::1%eth0", "[fe80::1%eth0]", "fe80::1%12", "fe80::1%en_0.1-2",
        )) {
            assertTrue(IpAddress.isIpv6(address), address)
            assertTrue(IpAddress.isIpLiteral(address), address)
            assertFalse(IpAddress.isIpv4(address), address)
        }
    }

    @Test
    fun `rejects malformed IPv6 brackets scopes groups and compression`() {
        for (address in listOf(
            null, "", ":", ":::",
            "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::",
            "1::2::3", "2001:db8:::1", "2001:db8::1:", ":2001:db8::1",
            "12345::", "gggg::", "0x1::", "+1::", "-1::",
            "[", "]", "[]", "[garbage]", "[::1", "::1]", "[[::1]]",
            "[::1]:443", "[::1]garbage", "::1%eth0]", "[::1]%eth0",
            "fe80::1%", "fe80::1%eth0%1", "fe80::1%eth 0", "fe80::1%eth/0",
            "fe80::1%eth:0", "fe80::1%\u00E9", "example%eth0", ":%eth0",
            " ::1", "::1 ", "::1\n", ":: 1", "[ ::1]", "\uFF11::",
            "::ffff:256.0.0.1", "::ffff:192.00.2.1", "::ffff:192.0.2",
            "192.0.2.1::", "1:2:3:4:5:192.0.2.1", "1:2:3:4:5:6::192.0.2.1",
            "::192.0.2.1:1", "::192.0.2.1:192.0.2.2", "::ffff:[192.0.2.1]",
        )) {
            assertFalse(IpAddress.isIpv6(address), "$address")
            assertFalse(IpAddress.isIpLiteral(address), "$address")
        }
    }

    @Test
    fun `recognizes zero bits only after successful parsing`() {
        for (address in listOf(
            "0.0.0.0", "::", "0::", "::0", "0000:0:00:000:0:0:0:0000",
            "[::]", "::0.0.0.0", "0:0:0:0:0:0:0.0.0.0", "[::0.0.0.0]", "::%eth0",
        )) {
            assertTrue(IpAddress.isZeroIp(address), address)
        }
        for (address in listOf(
            null, "", "0", "0.0.0", "00.0.0.0", ":::", "0:0:0:0:0:0:0",
            "0:0:0:0:0:0:0:0:0", "0::0::0", "[::", "::]", "[[::]]", "::%",
            "[::]junk", " ::", ":: ", "::0.0.0.256", "::000.0.0.0",
            "::ffff:0.0.0.0", "::1", "::0.0.0.1", "1.0.0.0",
        )) {
            assertFalse(IpAddress.isZeroIp(address), "$address")
        }
    }

    @Test
    fun `parsed arrays never share mutable state`() {
        val ipv4 = "192.0.2.1"
        val ipv6 = "[2001:db8::192.0.2.1%eth0]"
        assertNotNull(IpAddress.parseIpv4(ipv4)).fill(0)
        assertNotNull(IpAddress.parseIpv6(ipv6)).fill(0)
        assertContentEquals(intArrayOf(192, 0, 2, 1), IpAddress.parseIpv4(ipv4))
        assertContentEquals(
            intArrayOf(0x2001, 0xdb8, 0, 0, 0, 0, 0xc000, 0x201),
            IpAddress.parseIpv6(ipv6),
        )
        assertFalse(IpAddress.isZeroIp(ipv4))
        assertFalse(IpAddress.isZeroIp(ipv6))
    }
}
