package com.dsha.sidecar.net.dns

import org.junit.Assert.*
import org.junit.Test

class DnsProviderSelectionTest {
    private val providers = linkedMapOf<String, Any?>(
        "cloudflare" to mapOf("server" to "1.1.1.1", "family" to 6, "forSNI" to true),
        "local" to mapOf("server" to "127.0.0.1"),
    )

    @Test
    fun `selection priority is NAT64 preset ECH then mapped DNS`() {
        val catalog = DnsProviderConfig.normalize(providers, mapOf("nat64" to mapOf("enabled" to true)))
        val selection = DnsProviderSelection(
            catalog,
            mapping = mapOf("*" to mapOf("dnsName" to "local", "family" to 4)),
            preSetIpList = mapOf("*" to listOf("1.2.3.4")),
            ech = mapOf(
                "dns" to "cloudflare",
                "domains" to listOf("*.ech.test"),
                "preSetIpDomains" to listOf("preset.ech.test"),
            ),
            nat64Domains = listOf("nat.ech.test"),
        )
        assertEquals(DnsProviderChoice("builtin:Nat64"), selection.select("nat.ech.test"))
        assertEquals(DnsProviderChoice("builtin:PreSet"), selection.select("preset.ech.test"))
        assertEquals(DnsProviderChoice("provider:cloudflare"), selection.select("normal.ech.test"))
        assertEquals(DnsProviderChoice("builtin:PreSet"), selection.select("other.test"))
        assertTrue(selection.isEchDomain("nat.ech.test"))
        assertTrue(selection.isEchIgnorePreSetIp("nat.ech.test"))
        assertFalse(selection.isEchIgnorePreSetIp("preset.ech.test"))
    }

    @Test
    fun `mapping family is raw override and usa alias applies only when missing`() {
        val catalog = DnsProviderConfig.normalize(providers)
        val selection = DnsProviderSelection(
            catalog,
            mapping = linkedMapOf(
                "*" to mapOf("dnsName" to "local"),
                ".example.com" to mapOf("dnsName" to "usa", "family" to "4"),
                "example.com" to mapOf("dnsName" to "ForSNI", "family" to 6),
            ),
        )
        assertEquals(DnsProviderChoice("provider:cloudflare", 6), selection.select("example.com"))
        // The first wildcard wins for descendants; the apex shortcut is separate.
        assertEquals(DnsProviderChoice("provider:local"), selection.select("x.example.com"))
        assertEquals(
            DnsProviderChoice("provider:cloudflare", "4"),
            DnsProviderSelection(catalog, mapOf("*" to mapOf("dnsName" to "usa", "family" to "4"))).select("x"),
        )
        val explicit = DnsProviderConfig.normalize(providers + ("usa" to mapOf("server" to "8.8.8.8")))
        assertEquals(
            DnsProviderChoice("provider:usa"),
            DnsProviderSelection(explicit, mapOf("*" to mapOf("dnsName" to "usa"))).select("x"),
        )
    }

    @Test
    fun `missing ECH provider falls back and disabled ECH does not bypass preset`() {
        val catalog = DnsProviderConfig.normalize(providers)
        val mapping = mapOf("*" to mapOf("dnsName" to "local", "family" to 6))
        val missing = DnsProviderSelection(
            catalog, mapping, mapOf("*" to true),
            ech = mapOf("domains" to listOf("*"), "dns" to "unknown"),
        )
        assertNull(missing.getEchProviderKey())
        assertEquals(DnsProviderChoice("provider:local", 6), missing.select("x"))
        for (disabled in listOf("enabled", "use")) {
            val selection = DnsProviderSelection(
                catalog, mapping, mapOf("*" to true),
                ech = mapOf("domains" to listOf("*"), "dns" to "cloudflare", disabled to false),
            )
            assertFalse(selection.isEchDomain("x"))
            assertEquals(DnsProviderChoice("builtin:PreSet"), selection.select("x"))
        }
        val noMapping = DnsProviderSelection(catalog, mapOf("*" to mapOf("dnsName" to "unknown")))
        assertNull(noMapping.select("x"))
    }

    @Test
    fun `domain object values follow ECH inclusion rather than JS truthiness`() {
        val selection = DnsProviderSelection(
            DnsProviderConfig.normalize(providers),
            ech = mapOf(
                "dns" to "cloudflare",
                "domains" to mapOf(
                    "zero" to 0, "empty" to "", "false" to false, "null" to null,
                    ".example.com" to true,
                ),
            ),
        )
        assertTrue(selection.isEchDomain("zero"))
        assertTrue(selection.isEchDomain("empty"))
        assertTrue(selection.isEchDomain("example.com"))
        assertFalse(selection.isEchDomain("false"))
        assertFalse(selection.isEchDomain("null"))
        assertFalse(selection.isEchDomain(""))
        assertFalse(selection.isEchDomain("EXAMPLE.COM"))
    }

    @Test
    fun `selection snapshots inputs and respects false preset exact entry`() {
        val entry = mutableMapOf<String, Any?>("dnsName" to "local", "family" to 4)
        val selection = DnsProviderSelection(
            DnsProviderConfig.normalize(providers),
            mapOf("*" to entry),
            linkedMapOf("x" to false, "*" to true),
        )
        entry["dnsName"] = "cloudflare"
        assertEquals(DnsProviderChoice("provider:local", 4), selection.select("x"))
        assertEquals(DnsProviderChoice("builtin:PreSet"), selection.select("other"))
    }
}
