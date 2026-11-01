package com.dsha.sidecar.net.rules

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathRuleMatcherTest {

    @Test
    fun `special patterns preserve entire input including newline and query`() {
        val path = "/api?a=1\nnext"
        for (pattern in listOf(true, "true", "*", ".*")) {
            assertEquals(listOf(path), PathRuleMatcher.isMatched(path, pattern))
        }
        assertEquals(listOf(""), PathRuleMatcher.isMatched("", true))
    }

    @Test
    fun `regex searches full path and preserves captures including missing groups`() {
        assertEquals(
            listOf("q=42", "42", null),
            PathRuleMatcher.isMatched("/search?q=42", "q=(\\d+)(?:&(.*))?"),
        )
        assertEquals(listOf("foo", "foo"), PathRuleMatcher.isMatched("/foo?q=x", "(foo)"))
        assertNull(PathRuleMatcher.isMatched("/foo?q=x", "^/foo$"))
        assertEquals(listOf(""), PathRuleMatcher.isMatched("/foo", ""))
        assertEquals(listOf(""), PathRuleMatcher.isMatched("/foo", "^"))
    }

    @Test
    fun `prefix quantifiers receive one leading dot rather than glob conversion`() {
        assertEquals(listOf("/abc"), PathRuleMatcher.isMatched("/abc", "*abc"))
        assertEquals(listOf("/abc"), PathRuleMatcher.isMatched("/abc", "?abc"))
        assertEquals(listOf("abc"), PathRuleMatcher.isMatched("abc", "?abc"))
        assertEquals(listOf("/abc"), PathRuleMatcher.isMatched("/abc", "+abc"))
        assertNull(PathRuleMatcher.isMatched("abc", "+abc"))
        assertNull(PathRuleMatcher.isMatched("/api/file.json", "/api/*.json"))
        assertEquals(listOf("/api/file.json"), PathRuleMatcher.isMatched("/api/file.json", "/api/.*\\.json"))
    }

    @Test
    fun `invalid and unsupported patterns do not throw or poison later matches`() {
        for (pattern in listOf("[", "(", "\\", null, false, 42, emptyList<String>(), emptyMap<String, Any?>())) {
            assertNull(PathRuleMatcher.isMatched("/false42", pattern), pattern.toString())
        }
        assertEquals(listOf("/ok"), PathRuleMatcher.isMatched("/ok", "^/ok$"))
        assertNull(PathRuleMatcher.isMatched("/ABC", "abc"))
    }

    @Test
    fun `exclusions support arrays and object keys regardless of values`() {
        assertTrue(PathRuleMatcher.isExcluded("/api?q=secret", listOf("[", "secret")))
        assertTrue(PathRuleMatcher.isExcluded("/api?q=secret", mapOf("secret" to false)))
        assertTrue(PathRuleMatcher.isExcluded("/private", mapOf("^/private" to null)))
        assertFalse(PathRuleMatcher.isExcluded("/false", mapOf("^/other" to true)))
        assertFalse(PathRuleMatcher.isExcluded("/secret", "secret"))
        assertFalse(PathRuleMatcher.isExcluded("/secret", listOf("[", false, null)))
        assertNull(PathRuleMatcher.getExclusionArray(emptyList<String>()))
        assertNull(PathRuleMatcher.getExclusionArray(emptyMap<String, Any?>()))
        assertNull(PathRuleMatcher.getExclusionArray(null))
    }

    @Test
    fun `exclusion normalization copies containers and leaves inputs alone`() {
        val list = mutableListOf<Any?>("a", true)
        val normalized = PathRuleMatcher.getExclusionArray(list)
        list.clear()
        assertEquals(listOf("a", true), normalized)
        val map = linkedMapOf<String, Any?>("a" to false, "b" to null)
        assertEquals(listOf("a", "b"), PathRuleMatcher.getExclusionArray(map))
        assertEquals(linkedMapOf<String, Any?>("a" to false, "b" to null), map)
    }

    @Test
    fun `highest order wins per name with first tie and negative orders preserved`() {
        val rules = linkedMapOf<String, Any?>(
            "*" to mapOf("a" to true, "b" to true, "order" to -2),
            "^/" to mapOf("a" to true, "order" to -1),
            "api" to mapOf("a" to true, "order" to -1),
            "q=" to mapOf("b" to true, "order" to 0.5),
        )
        val selected = PathRuleMatcher.select("/api?q=x", rules, listOf(impl("a"), impl("b")))
        assertEquals(listOf("a", "b"), selected.map { it.name })
        assertEquals(listOf("^/", "q="), selected.map { it.key })
        assertEquals(listOf(-1.0, 0.5), selected.map { it.order })
        assertEquals(listOf("q="), selected[1].matched)
    }

    @Test
    fun `ascending priority sort is stable and replacements retain original slot`() {
        val rules = linkedMapOf<String, Any?>(
            "*" to mapOf("a" to true, "b" to true, "c" to true),
            "^/" to mapOf("a" to true, "order" to 10),
        )
        val selected = PathRuleMatcher.select(
            "/api", rules, listOf(impl("a", 5), impl("b", 5), impl("c", -1)),
        )
        assertEquals(listOf("c", "a", "b"), selected.map { it.name })
        assertEquals(listOf("*", "^/", "*"), selected.map { it.key })
    }

    @Test
    fun `reserved metadata excluded paths disabled keys and malformed rules are skipped`() {
        val rules = linkedMapOf<String, Any?>(
            "matched" to mapOf("a" to true, "order" to 100),
            "[" to mapOf("a" to true),
            "*" to mapOf("a" to true, "exclusions" to mapOf("matched" to false)),
            "^" to false,
            ".*" to mapOf("a" to false),
        )
        assertTrue(PathRuleMatcher.select("/matched", rules, listOf(impl("a"))).isEmpty())
        assertTrue(PathRuleMatcher.select("/matched", emptyMap(), listOf(impl("a"))).isEmpty())
        assertTrue(PathRuleMatcher.select("/matched", rules, emptyList()).isEmpty())
    }

    @Test
    fun `enabled key descriptors use JSON truthiness with any key enabling`() {
        val config = mapOf(
            "bool" to true, "false" to false, "zero" to 0, "empty" to "",
            "text" to "false", "array" to emptyList<Any?>(), "object" to emptyMap<String, Any?>(),
        )
        val descriptors = listOf("bool", "false", "zero", "empty", "text", "array", "object", "absent")
            .map { impl(it) } + PathRuleMatcher.Implementation("alias", 0, listOf("absent", "bool"))
        val selected = PathRuleMatcher.select("/", mapOf("*" to config), descriptors)
        assertEquals(listOf("bool", "text", "array", "object", "alias"), selected.map { it.name })
    }

    @Test
    fun `selection neither annotates config nor sorts or edits caller inputs`() {
        val exclusions = mutableListOf<Any?>("^/private")
        val config = linkedMapOf<String, Any?>("a" to true, "exclusions" to exclusions)
        val rules = linkedMapOf<String, Any?>("^/(\\w+)\\?q=(\\w+)$" to config)
        val keys = mutableListOf("a")
        val descriptors = mutableListOf(PathRuleMatcher.Implementation("a", 3, keys))
        val selected = PathRuleMatcher.select("/public?q=x", rules, descriptors)
        assertEquals(listOf("/public?q=x", "public", "x"), selected.single().matched)
        assertFalse(config.containsKey("key"))
        assertFalse(rules.containsKey("_pathCache"))
        assertEquals(listOf<Any?>("^/private"), exclusions)
        assertEquals(listOf("a"), keys)
        config.clear()
        rules.clear()
        descriptors.clear()
        assertEquals("^/(\\w+)\\?q=(\\w+)$", selected.single().key)
        assertEquals(listOf("/public?q=x", "public", "x"), selected.single().matched)
    }

    @Test
    fun `unsupported orders fail explicitly rather than inventing coercion policy`() {
        for (order in listOf("2", false, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                PathRuleMatcher.select("/", mapOf("*" to mapOf("a" to true, "order" to order)), listOf(impl("a")))
            }
        }
        assertEquals(
            0.0,
            PathRuleMatcher.select("/", mapOf("*" to mapOf("a" to true, "order" to null)), listOf(impl("a")))
                .single().order,
        )
    }

    private fun impl(name: String, priority: Int = 0) =
        PathRuleMatcher.Implementation(name, priority, listOf(name))
}
