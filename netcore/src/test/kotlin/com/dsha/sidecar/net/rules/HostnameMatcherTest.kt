package com.dsha.sidecar.net.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HostnameMatcherTest {

    @Test
    fun `exact entry wins even when a wildcard was inserted first`() {
        val matcher = HostnameMatcher(linkedMapOf("*" to "all", "example.com" to "exact"))
        assertEquals("exact", matcher.match("example.com"))
        assertEquals("all", matcher.match("other.test"))
    }

    @Test
    fun `leading dot includes apex and descendants but not lookalike domains`() {
        val matcher = HostnameMatcher(mapOf(".example.com" to "site"))
        for (host in listOf("example.com", "www.example.com", "a.b.example.com")) {
            assertEquals("site", matcher.match(host), host)
        }
        for (host in listOf("notexample.com", "example.com.evil.test")) {
            assertNull(matcher.match(host), host)
        }
    }

    @Test
    fun `explicit star dot entry overrides its leading dot alias`() {
        val matcher = HostnameMatcher(
            linkedMapOf(".example.com" to "alias", "*.example.com" to "explicit"),
        )
        assertEquals("explicit", matcher.match("example.com"))
        assertEquals("explicit", matcher.match("www.example.com"))
    }

    @Test
    fun `apex shortcuts prefer star dot over star and general regex`() {
        val matcher = HostnameMatcher(
            linkedMapOf(
                "^.*$" to "regex",
                "*example.com" to "suffix",
                "*.example.com" to "domain",
            ),
        )
        assertEquals("domain", matcher.match("example.com"))
        assertEquals("regex", matcher.match("notexample.com"))
    }

    @Test
    fun `overlapping subdomain patterns retain insertion order`() {
        val matcher = HostnameMatcher(
            linkedMapOf("*.example.com" to "broad", "*.api.example.com" to "specific"),
        )
        assertEquals("broad", matcher.match("v1.api.example.com"))
        // The apex shortcut is evaluated before the regex loop.
        assertEquals("specific", matcher.match("api.example.com"))
    }

    @Test
    fun `wildcards work inside domains and dots stay literal`() {
        val matcher = HostnameMatcher(mapOf("api.*.example.com" to "api"))
        assertEquals("api", matcher.match("api.eu.example.com"))
        assertNull(matcher.match("apiXeuXexampleXcom"))
        assertNull(matcher.match("other.api.eu.example.com"))
    }

    @Test
    fun `raw regex searches rather than requiring a full match`() {
        val matcher = HostnameMatcher(mapOf("^api\\." to "api"))
        assertEquals("api", matcher.match("api.example.com"))
        assertNull(matcher.match("other.api.example.com"))
    }

    @Test
    fun `exact domains neither imply subdomains nor normalize input`() {
        val matcher = HostnameMatcher(mapOf("example.com" to "exact"))
        for (host in listOf("www.example.com", "EXAMPLE.COM", "example.com.", " example.com")) {
            assertNull(matcher.match(host), host)
        }
    }

    @Test
    fun `null exact values allow fallback and false remains a matching value`() {
        val matcher = HostnameMatcher(
            linkedMapOf<String, Boolean?>("*" to true, "example.com" to false, "null.test" to null),
        )
        assertFalse(matcher.match("example.com")!!)
        assertEquals(true, matcher.match("null.test"))
    }

    @Test
    fun `null wildcard stops the regex search as upstream does`() {
        val matcher = HostnameMatcher(
            linkedMapOf<String, String?>("*.example.com" to null, "*" to "fallback"),
        )
        assertNull(matcher.match("www.example.com"))
    }

    @Test
    fun `invalid regex is ignored without discarding later entries`() {
        val matcher = HostnameMatcher(linkedMapOf("^[" to "invalid", "*" to "valid"))
        assertEquals("valid", matcher.match("example.com"))
    }

    @Test
    fun `empty maps and unmatched hosts return null`() {
        assertNull(HostnameMatcher<String>(emptyMap()).match("example.com"))
        assertNull(HostnameMatcher(mapOf("example.com" to "site")).match("other.test"))
    }

    @Test
    fun `caller map mutations do not change the matching snapshot`() {
        val entries = linkedMapOf("example.com" to "original", "*.test" to "wildcard")
        val matcher = HostnameMatcher(entries)
        entries["example.com"] = "changed"
        entries.clear()
        assertEquals("original", matcher.match("example.com"))
        assertEquals("wildcard", matcher.match("a.test"))
    }

    @Test
    fun `duplicate compiled expressions replace the value without moving the pattern`() {
        val matcher = HostnameMatcher(
            linkedMapOf("^.*$" to "first", "*.example.com" to "site", "*" to "last"),
        )
        assertEquals("last", matcher.match("www.example.com"))
    }

    @Test
    fun `null explicit wildcard and leading dot alias retain insertion semantics`() {
        val aliasFirst = HostnameMatcher(
            linkedMapOf<String, String?>(".example.com" to "alias", "*.example.com" to null),
        )
        assertNull(aliasFirst.match("example.com"))
        assertNull(aliasFirst.match("www.example.com"))

        val aliasLast = HostnameMatcher(
            linkedMapOf<String, String?>("*.example.com" to null, ".example.com" to "alias"),
        )
        assertEquals("alias", aliasLast.match("example.com"))
        assertEquals("alias", aliasLast.match("www.example.com"))
    }

    @Test
    fun `JavaScript prototype property names cannot produce phantom matches`() {
        val matcher = HostnameMatcher(mapOf("*" to "fallback"))
        assertEquals("fallback", matcher.match("constructor"))
        assertEquals("fallback", matcher.match("__proto__"))
    }
}
