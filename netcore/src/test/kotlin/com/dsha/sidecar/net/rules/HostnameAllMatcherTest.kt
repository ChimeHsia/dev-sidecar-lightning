package com.dsha.sidecar.net.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostnameAllMatcherTest {

    @Test
    fun `patterns merge in insertion order before all three hostname overrides`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "^.*$" to mapOf("winner" to "regex", "a" to 1),
                "*example.com" to mapOf("winner" to "suffix", "b" to 2),
                "*.example.com" to mapOf("winner" to "apex", "c" to 3),
                "example.com" to mapOf("winner" to "exact", "d" to 4),
            ),
        )
        assertEquals(
            linkedMapOf("winner" to "exact", "a" to 1, "b" to 2, "c" to 3, "d" to 4),
            matcher.match("example.com"),
        )
        assertEquals("apex", matcher.match("www.example.com")!!["winner"])
        val insertionOrder = HostnameAllMatcher(
            linkedMapOf(
                "*.example.com" to mapOf("winner" to "first"),
                "*.api.example.com" to mapOf("winner" to "last"),
            ),
        )
        assertEquals("last", insertionOrder.match("v1.api.example.com")!!["winner"])
    }

    @Test
    fun `duplicate compiled expression replaces value without changing its position`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "^.*$" to mapOf("winner" to "first"),
                "*.example.com" to mapOf("winner" to "site"),
                "*" to mapOf("winner" to "replacement"),
            ),
        )
        assertEquals(mapOf("winner" to "site"), matcher.match("www.example.com"))
    }

    @Test
    fun `leading dot includes apex and descendants with explicit wildcard suppression`() {
        val alias = HostnameAllMatcher(mapOf(".example.com" to mapOf("alias" to true)))
        assertEquals(mapOf("alias" to true), alias.match("example.com"))
        assertEquals(mapOf("alias" to true), alias.match("a.b.example.com"))
        assertNull(alias.match("notexample.com"))
        val explicit = HostnameAllMatcher(
            linkedMapOf(
                ".example.com" to mapOf("alias" to true),
                "*.example.com" to mapOf("explicit" to true),
            ),
        )
        assertEquals(mapOf("explicit" to true), explicit.match("example.com"))
        val nullLast = HostnameAllMatcher(
            linkedMapOf(".example.com" to mapOf("alias" to true), "*.example.com" to null),
        )
        assertNull(nullLast.match("example.com"))
        val aliasLast = HostnameAllMatcher(
            linkedMapOf("*.example.com" to null, ".example.com" to mapOf("alias" to true)),
        )
        assertEquals(mapOf("alias" to true), aliasLast.match("example.com"))
    }

    @Test
    fun `null configs skip merging while deletion markers survive until the final pass`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "*" to mapOf("nested" to mapOf("old" to 1), "remove" to 1),
                "^example" to mapOf("nested" to null, "remove" to "[delete]"),
                "example.com" to mapOf("nested" to mapOf("new" to 2)),
                "null.test" to null,
            ),
        )
        assertEquals(mapOf("nested" to mapOf("new" to 2)), matcher.match("example.com"))
        assertEquals(
            mapOf("nested" to mapOf("old" to 1), "remove" to 1),
            matcher.match("null.test"),
        )
        assertEquals(
            emptyMap<String, Any?>(),
            HostnameAllMatcher(mapOf("x" to mapOf("remove" to null))).match("x"),
        )
        assertNull(HostnameAllMatcher(mapOf("x" to emptyMap<String, Any?>())).match("x"))
    }

    @Test
    fun `arrays replace and deleted array slots retain their JSON positions`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "*" to mapOf("a" to listOf(1, 2, 3), "b" to listOf(1), "c" to mapOf("old" to 1)),
                "x" to mapOf(
                    "a" to listOf(null, 9, "[delete]", mapOf("gone" to null, "keep" to false)),
                    "b" to mapOf("new" to 2),
                    "c" to listOf(3),
                ),
            ),
        )
        assertEquals(
            mapOf(
                "a" to listOf(null, 9, null, mapOf("keep" to false)),
                "b" to mapOf("new" to 2),
                "c" to listOf(3),
            ),
            matcher.match("x"),
        )
    }

    @Test
    fun `lodash converts an array-like object before merging an incoming array`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "*" to mapOf(
                    "list" to mapOf("0" to mapOf("old" to 1), "1" to "tail", "length" to 3),
                ),
                "x" to mapOf("list" to listOf(mapOf("new" to 2))),
            ),
        )
        assertEquals(
            mapOf("list" to listOf(mapOf("old" to 1, "new" to 2), "tail", null)),
            matcher.match("x"),
        )
        rejects {
            HostnameAllMatcher(
                linkedMapOf(
                    "*" to mapOf("list" to mapOf("length" to HostnameAllMatcher.MAX_JSON_NODES + 1)),
                    "x" to mapOf("list" to listOf(1)),
                ),
            ).match("x")
        }
    }

    @Test
    fun `captures retain first full match and append only subsequent subgroups`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "^([^.]+)\\." to mapOf("first" to 1),
                "^([^.]+)\\.([^.]+)" to mapOf("second" to 2),
            ),
        )
        val result = matcher.matchWithMetadata("api.example.com")!!
        assertEquals(listOf("api.", "api", "api", "example"), result.values["matched"])
        assertEquals(listOf("first", "matched", "second"), result.values.keys.toList())
        assertEquals(HostnameAllMatcher.CaptureMetadata(null, null, null), result.captureMetadata)
    }

    @Test
    fun `named metadata tracks the last capture array and excludes unmatched named groups`() {
        val single = HostnameAllMatcher(
            mapOf("^(?<optional>www\\.)?(?<host>example)\\.com$" to emptyMap<String, Any?>()),
        ).matchWithMetadata("example.com")!!
        assertEquals(listOf("example.com", null, "example"), single.values["matched"])
        assertEquals(
            HostnameAllMatcher.CaptureMetadata(mapOf("host" to "example"), 0, "example.com"),
            single.captureMetadata,
        )
        val two = HostnameAllMatcher(
            linkedMapOf(
                "^(?<first>[^.]+)\\." to emptyMap<String, Any?>(),
                "^(?<second>[^.]+)" to emptyMap<String, Any?>(),
            ),
        ).matchWithMetadata("api.example.com")!!
        assertEquals(mapOf("second" to "api"), two.captureMetadata!!.groups)
        assertNull(two.captureMetadata.index)
        val unnamedLast = HostnameAllMatcher(
            linkedMapOf(
                "^(?<first>[^.]+)\\." to emptyMap<String, Any?>(),
                "^([^.]+)" to emptyMap<String, Any?>(),
            ),
        ).matchWithMetadata("api.example.com")!!
        assertNull(unnamedLast.captureMetadata!!.groups)
    }

    @Test
    fun `captures can originate from null config and exact overlays remove their metadata`() {
        val fromNull = HostnameAllMatcher(mapOf("^([^.]+)\\." to null))
            .matchWithMetadata("api.example.com")!!
        assertEquals(listOf("api.", "api"), fromNull.values["matched"])
        assertEquals(0, fromNull.captureMetadata!!.index)
        for (override in listOf(listOf("exact"), null)) {
            val result = HostnameAllMatcher(
                linkedMapOf(
                    "^(?<sub>[^.]+)\\." to emptyMap<String, Any?>(),
                    "api.example.com" to mapOf("matched" to override),
                ),
            ).matchWithMetadata("api.example.com")!!
            assertNull(result.captureMetadata)
            assertEquals(if (override == null) emptyMap() else mapOf("matched" to override), result.values)
        }
        val configured = HostnameAllMatcher(mapOf("^([^.]+)\\." to mapOf("matched" to listOf("config"))))
        assertEquals(listOf("config", "api"), configured.match("api.example.com")!!["matched"])
    }

    @Test
    fun `wildcard conversion leaves other regex syntax active while exact keys remain literal`() {
        val matcher = HostnameAllMatcher(mapOf("(api|www).*" to mapOf("route" to 1)))
        assertEquals(listOf("api.example.com", "api"), matcher.match("api.example.com")!!["matched"])
        val prefix = HostnameAllMatcher(mapOf("^api\\." to mapOf("route" to 1)))
        assertEquals(mapOf("route" to 1), prefix.match("api.example.com"))
        assertNull(prefix.match("other.api.example.com"))
        val exact = HostnameAllMatcher(mapOf("example.com" to mapOf("route" to 1)))
        for (host in listOf("EXAMPLE.COM", "example.com.", "other.test")) assertNull(exact.match(host))
        assertNull(HostnameAllMatcher(emptyMap()).match("example.com"))
    }

    @Test
    fun `JSON root keys use JavaScript numeric ordering before insertion ordered names`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf(
                "*" to linkedMapOf("10" to "ten", "later" to 1),
                "x" to linkedMapOf("2" to "two", "later" to 2, "tail" to 3),
            ),
        )
        assertEquals(listOf("2", "10", "later", "tail"), matcher.match("x")!!.keys.toList())
    }

    @Test
    fun `snapshots and returned containers are isolated across matches`() {
        val array = mutableListOf<Any?>(null, 2)
        val nested = linkedMapOf<String, Any?>("list" to array)
        val entries = linkedMapOf<String, Any?>("*" to nested)
        val matcher = HostnameAllMatcher(entries)
        entries.clear()
        nested.clear()
        array.clear()
        val first = matcher.match("x")!!
        @Suppress("UNCHECKED_CAST")
        (first["list"] as MutableList<Any?>)[1] = "changed"
        assertEquals(mapOf("list" to listOf(null, 2)), matcher.match("x"))
    }

    @Test
    fun `invalid regex is ignored and prototype keys cannot create phantom matches`() {
        val matcher = HostnameAllMatcher(
            linkedMapOf("^[" to mapOf("invalid" to 1), "*" to mapOf("fallback" to 2)),
        )
        assertEquals(mapOf("fallback" to 2), matcher.match("constructor"))
        assertEquals(mapOf("fallback" to 2), matcher.match("__proto__"))
    }

    @Test
    fun `bounded JSON input rejects unsupported types cycles and excessive sizes`() {
        rejects { HostnameAllMatcher(mapOf("x" to true)) }
        rejects { HostnameAllMatcher(mapOf("x" to mapOf("matched" to "text"))) }
        rejects { HostnameAllMatcher(mapOf("x" to mapOf("number" to Double.NaN))) }
        rejects { HostnameAllMatcher(mapOf("x" to mapOf("other" to Any()))) }
        rejects { HostnameAllMatcher(mapOf("x".repeat(HostnameAllMatcher.MAX_PATTERN_LENGTH + 1) to null)) }
        rejects { HostnameAllMatcher((0..HostnameAllMatcher.MAX_ENTRIES).associate { "$it" to null }) }
        rejects {
            HostnameAllMatcher(emptyMap()).match("x".repeat(HostnameAllMatcher.MAX_HOSTNAME_LENGTH + 1))
        }
        val cyclic = mutableMapOf<String, Any?>()
        cyclic["self"] = cyclic
        rejects { HostnameAllMatcher(mapOf("x" to cyclic)) }
        rejects {
            HostnameAllMatcher(mapOf("x" to mapOf("large" to List(HostnameAllMatcher.MAX_JSON_NODES) { 1 })))
        }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected an IllegalArgumentException", rejected)
    }
}
