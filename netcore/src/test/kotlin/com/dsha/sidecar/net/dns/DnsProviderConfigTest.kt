package com.dsha.sidecar.net.dns

import org.junit.Assert.*
import org.junit.Test

class DnsProviderConfigTest {
    @Test
    fun `empty catalog has preset and SNI fallback without default hostname DNS`() {
        val catalog = DnsProviderConfig.normalize()
        assertEquals(listOf("builtin:PreSet"), catalog.descriptors.map { it.key })
        assertEquals("builtin:PreSet", catalog.providerKey("ForSNI"))
        assertNull(catalog.providerKey("unknown"))
        assertNull(DnsProviderSelection(catalog).select("example.com"))
    }

    @Test
    fun `constructor arguments preserve raw family ports and ECH flags`() {
        val catalog = DnsProviderConfig.normalize(
            linkedMapOf(
                "doh" to mapOf("server" to "dns.test", "type" to "doh", "family" to "6", "cacheSize" to 12),
                "tls" to mapOf("server" to "tls://[2001:db8::1]:8853", "servername" to "front", "ech" to false),
                "tcp" to mapOf("host" to "2001:db8::1", "type" to "tcp", "port" to 5353),
            ),
            mapOf("ech" to mapOf("cacheSize" to 99), "nat64" to mapOf("enabled" to true)),
        )
        val (doh, tls, tcp) = catalog.descriptors
        assertEquals("https://dns.test/dns-query", doh.server)
        assertEquals("6", doh.family)
        assertEquals(12, doh.cacheSize)
        assertEquals("2001:db8::1", tls.server)
        assertEquals("8853", tls.port)
        assertEquals("front", tls.sni)
        assertEquals(mapOf("cacheSize" to 99, "enabled" to false), tls.echOptions)
        assertEquals(5353, tcp.port)
        assertEquals("builtin:Nat64", catalog.providerKey("Nat64"))
    }

    @Test
    fun `forSNI last wins and reserved aliases preserve object identity`() {
        val catalog = DnsProviderConfig.normalize(
            linkedMapOf(
                "10" to mapOf("server" to "a", "forSNI" to true),
                "2" to mapOf("server" to "b", "forSni" to true),
                "PreSet" to mapOf("server" to "c", "forSni" to true),
            ),
        )
        assertEquals(listOf("2", "10", "PreSet", "PreSet"), catalog.descriptors.map { it.name })
        assertEquals("provider:PreSet", catalog.providerKey("ForSNI"))
        assertEquals("builtin:PreSet", catalog.providerKey("PreSet"))
    }

    @Test
    fun `upstream quirks are retained before constructor defaults`() {
        val catalog = DnsProviderConfig.normalize(
            linkedMapOf(
                "spaces" to mapOf("server" to " dns.test ", "type" to " DoH "),
                "brackets" to mapOf("server" to "[::1]:53", "port" to 54),
                "http" to mapOf("server" to "http://dns.test"),
                "blank" to mapOf("server" to "", "host" to "fallback:9"),
            ),
        )
        assertEquals("udp", catalog.descriptors[0].transport)
        assertEquals("dns.test ", catalog.descriptors[0].server)
        assertEquals("::1:53", catalog.descriptors[1].server)
        assertEquals("http://dns.test", catalog.descriptors[2].server)
        assertEquals("9", catalog.descriptors[3].port)
    }

    @Test
    fun `doh lists retain order array commas and first SNI on duplicate URLs`() {
        assertEquals(listOf("a", "b", "a"), DnsProviderConfig.toDohList(" a, ,b,a "))
        assertEquals(listOf("a,b"), DnsProviderConfig.toDohList(listOf(" a,b ", "")))
        val entries = DnsProviderConfig.resolveDohList(
            mapOf("dns" to " selected ", "doh" to "https://dns.google/resolve,https://extra/query"),
            mapOf("selected" to mapOf(
                "host" to " dns.google/resolve ", "type" to " d o h ", "sni" to "front",
            )),
        )
        assertEquals(
            listOf(
                DnsProviderDohEntry("dns.google/resolve", "front"),
                DnsProviderDohEntry("https://dns.google/resolve"),
                DnsProviderDohEntry("https://extra/query"),
                DnsProviderDohEntry("https://dns.alidns.com/resolve"),
                DnsProviderDohEntry("https://cloudflare-dns.com/dns-query"),
            ),
            entries,
        )
        val duplicate = DnsProviderConfig.resolveDohList(
            mapOf("dns" to "a", "doh" to "https://dns.google/resolve"),
            mapOf("a" to mapOf("server" to "https://dns.google/resolve", "sni" to "first")),
        )
        assertEquals(1, duplicate.count { it.url == "https://dns.google/resolve" })
        assertEquals("first", duplicate.first().sni)
        assertEquals(DnsProviderConfig.resolveDohList(), DnsProviderConfig.resolveDohList(mapOf("dns" to "missing")))
    }

    @Test
    fun `supported input boundary rejects JS coercions and malformed shapes`() {
        fun rejects(block: () -> Unit) {
            assertThrows(IllegalArgumentException::class.java, block)
        }
        rejects { DnsProviderConfig.normalize(mapOf("bad" to null)) }
        rejects { DnsProviderConfig.normalize(mapOf("bad" to mapOf("server" to 123))) }
        rejects { DnsProviderConfig.normalize(mapOf("bad" to mapOf("server" to "quic://host"))) }
        rejects { DnsProviderConfig.toDohList(listOf(null)) }
        rejects { DnsProviderConfig.toDohList(123) }
        rejects { DnsProviderConfig.normalize(mapOf("bad" to mapOf("server" to "dns", "port" to true))) }
        rejects { DnsProviderConfig.normalize(options = mapOf("ech" to true)) }
    }

    @Test
    fun `catalog owns copies of nested options and providers`() {
        val domains = mutableListOf<Any?>("example.com")
        val provider = mutableMapOf<String, Any?>("server" to "dns.test")
        val providers = mutableMapOf<String, Any?>("a" to provider)
        val catalog = DnsProviderConfig.normalize(
            providers,
            mapOf("ech" to mapOf("domains" to domains), "nat64" to mapOf("enabled" to true)),
        )
        provider["server"] = "changed"
        providers.clear()
        domains.clear()
        assertEquals(listOf("example.com"), catalog.descriptors.first().echOptions?.get("domains"))
        assertEquals(
            mapOf("a" to mapOf("server" to "dns.test")),
            catalog.descriptors.last().nat64Options?.get("providers"),
        )
    }

    @Test
    fun `JSON cycles are cleanly rejected while shared acyclic containers are copied`() {
        val cycle = mutableMapOf<String, Any?>()
        cycle["self"] = cycle
        val listCycle = mutableListOf<Any?>()
        listCycle.add(listCycle)
        for (operation in listOf<() -> Unit>(
            { DnsProviderConfig.normalize(options = cycle) },
            { DnsProviderConfig.resolveDohList(config = cycle) },
            { DnsProviderConfig.resolveDohList(providers = mapOf("unused" to cycle)) },
            { DnsProviderConfig.toDohList(listCycle) },
            { DnsProviderSelection(DnsProviderConfig.normalize(), nat64Domains = cycle) },
        )) {
            val error = assertThrows(IllegalArgumentException::class.java, operation)
            assertEquals("Cyclic JSON container", error.message)
        }
        val shared = mapOf("one" to listOf("value"))
        DnsProviderConfig.normalize(options = mapOf("first" to shared, "second" to shared))
    }

    @Test
    fun `JSON depth budget counts root as zero and rejects before stack exhaustion`() {
        fun tree(depth: Int): Map<String, Any?> {
            var value: Any? = "leaf"
            repeat(depth) { value = mapOf("child" to value) }
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
        DnsProviderConfig.normalize(options = tree(64))
        val excessive = tree(65)
        for (operation in listOf<() -> Unit>(
            { DnsProviderConfig.normalize(options = excessive) },
            { DnsProviderConfig.resolveDohList(config = excessive) },
            { DnsProviderSelection(DnsProviderConfig.normalize(), echDomains = excessive) },
        )) {
            assertEquals(
                "JSON depth exceeds 64",
                assertThrows(IllegalArgumentException::class.java, operation).message,
            )
        }
    }

    @Test
    fun `JSON node budget rejects oversized input and counts shared subtrees per occurrence`() {
        assertEquals(99_999, DnsProviderConfig.toDohList(List(99_999) { "url" }).size)
        val oversized = List(100_000) { "url" }
        assertEquals(
            "JSON node count exceeds 100000",
            assertThrows(IllegalArgumentException::class.java) {
                DnsProviderConfig.toDohList(oversized)
            }.message,
        )
        val shared = List(50_000) { 0 }
        assertEquals(
            "JSON node count exceeds 100000",
            assertThrows(IllegalArgumentException::class.java) {
                DnsProviderConfig.normalize(options = mapOf("a" to shared, "b" to shared))
            }.message,
        )
    }

    @Test
    fun `resolve validates unused input JSON but keeps unconsumed JSON field shapes inert`() {
        for (operation in listOf<() -> Unit>(
            { DnsProviderConfig.resolveDohList(mapOf("unused" to Any())) },
            { DnsProviderConfig.resolveDohList(providers = mapOf("unused" to Double.NaN)) },
        )) {
            assertThrows(IllegalArgumentException::class.java, operation)
        }
        assertEquals(
            DnsProviderConfig.resolveDohList(),
            DnsProviderConfig.resolveDohList(mapOf("unused" to mapOf("server" to 123))),
        )
    }
}
