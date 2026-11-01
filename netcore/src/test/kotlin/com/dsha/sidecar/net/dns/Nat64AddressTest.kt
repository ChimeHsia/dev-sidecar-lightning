package com.dsha.sidecar.net.dns

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nat64AddressTest {
    @Test
    fun `embeds IPv4 in an explicit well known prefix`() {
        for (prefix in listOf("64:ff9b::", "64:ff9b::/96", "0064:FF9B:0:0:0:0:0:0/96")) {
            assertEquals(
                "64:ff9b:0:0:0:0:c000:221",
                Nat64Address.toNat64Address(prefix, "192.0.2.33"),
            )
        }
    }

    @Test
    fun `preserves all six groups of a custom prefix`() {
        val result = Nat64Address.toNat64Address("2a01:4f8:c2c:123f:64:5::/96", "203.0.113.7")
        assertEquals("2a01:4f8:c2c:123f:64:5:cb00:7107", result)
        assertTrue(IpAddress.isIpv6(result))
        assertEquals(
            "2001:db8:0:1:0:2:102:304",
            Nat64Address.toNat64Address("2001:db8:0:1:0:2::", "1.2.3.4"),
        )
    }

    @Test
    fun `supports boundary IPv4 values without sign extension`() {
        assertEquals("64:ff9b:0:0:0:0:0:0", Nat64Address.toNat64Address("64:ff9b::", "0.0.0.0"))
        assertEquals(
            "64:ff9b:0:0:0:0:ffff:ffff",
            Nat64Address.toNat64Address("64:ff9b::", "255.255.255.255"),
        )
        assertEquals(
            "64:ff9b:0:0:0:0:8000:ff01",
            Nat64Address.toNat64Address("64:ff9b::", "128.0.255.1"),
        )
    }

    @Test
    fun `rejects missing zero malformed and non 96 prefixes`() {
        for (prefix in listOf(
            null, "", " ", "::", "::/96", "0:0:0:0:0:0:0:0",
            "0000:0000:0000:0000:0000:0000:0000:0000/96",
            "64:ff9b::/32", "64:ff9b::/64", "64:ff9b::/95", "64:ff9b::/97",
            "64:ff9b::/128", "64:ff9b::/096", "64:ff9b::/+96", "64:ff9b::/",
            "64:ff9b::/96/96", "64:ff9b::1", "64:ff9b::1:0/96",
            "64:ff9b::192.0.2.1", "[64:ff9b::]", "[64:ff9b::]/96",
            "64:ff9b::%eth0", "64:ff9b::%eth0/96", " 64:ff9b::", "64:ff9b::/96 ",
            "64:ff9b", "64:ff9b:0", "64:ff9b:::",
            "64:ff9b::0::", "64:ff9b:0:0:0:0:0:0:0", "64:ff9g::",
            "example.test", "192.0.2.1", "[", "0", "::0.0.0.0/96",
        )) {
            assertNull(Nat64Address.toNat64Address(prefix, "192.0.2.33"), "$prefix")
        }
    }

    @Test
    fun `rejects invalid IPv4 instead of coercing or looking up names`() {
        for (ipv4 in listOf(
            null, "", "256.0.2.1", "192.0.2.999", "192.00.2.1", "192.0.2",
            "192.0.2.1.2", "+192.0.2.1", " 192.0.2.1", "192.0.2.1 ",
            "192.0.2.1\n", "[192.0.2.1]", "192.0.2.1%eth0", "::ffff:192.0.2.1",
            "example.test",
        )) {
            assertNull(Nat64Address.toNat64Address("64:ff9b::/96", ipv4), "$ipv4")
        }
    }

    @Test
    fun `embedding keeps inputs and subsequent results independent`() {
        val prefix = "2a01:4f8:c2c:123f:64:5::/96"
        val ipv4 = "192.0.2.33"
        val originalGroups = assertNotNull(IpAddress.parseIpv6(prefix.removeSuffix("/96")))
        val originalOctets = assertNotNull(IpAddress.parseIpv4(ipv4))
        val first = Nat64Address.toNat64Address(prefix, ipv4)
        Nat64Address.toNat64Address(prefix, "255.255.255.255")
        assertEquals(first, Nat64Address.toNat64Address(prefix, ipv4))
        assertEquals("2a01:4f8:c2c:123f:64:5::/96", prefix)
        assertEquals("192.0.2.33", ipv4)
        assertContentEquals(originalGroups, IpAddress.parseIpv6(prefix.removeSuffix("/96")))
        assertContentEquals(originalOctets, IpAddress.parseIpv4(ipv4))
    }
}
