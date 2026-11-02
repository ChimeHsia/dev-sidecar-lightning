// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderTransformsTest {
    @Test
    fun upstreamHeaderFixtures() {
        for (operation in listOf("request", "response", "cors")) {
            for (case in HttpPolicyFixtures.cases(operation)) {
                val input = case.input
                @Suppress("UNCHECKED_CAST")
                val actual = when (operation) {
                    "request" -> HeaderTransforms.replaceRequestHeaders(
                        input["headers"] as Map<String, Any?>,
                        input["replacements"] as Map<String, Any?>,
                    )
                    "response" -> HeaderTransforms.replaceResponseHeaders(
                        input["rawHeaders"] as List<Any?>,
                        input["replacements"] as Map<String, Any?>,
                    ).json()
                    else -> HeaderTransforms.afterOptionsHeaders(
                        input["method"],
                        input["requestHeaders"] as Map<String, Any?>,
                        input["rawHeaders"] as List<Any?>,
                        input["optionsCrossPolicy"],
                    ).json()
                }
                HttpPolicyFixtures.assertJson(case.name, case.expected, actual)
            }
        }
    }

    @Test
    fun requestDeepCopiesInputsAndEachInvocation() {
        val child = linkedMapOf<String, Any?>("value" to 1)
        val input = linkedMapOf<String, Any?>("kept" to mutableListOf(child), "old" to "value")
        val replacementList = mutableListOf<Any?>(linkedMapOf("value" to 2))
        val changes = linkedMapOf<String, Any?>("X" to replacementList, "Old" to "[remove]")
        val result = HeaderTransforms.replaceRequestHeaders(input, changes)
        val second = HeaderTransforms.replaceRequestHeaders(input, changes)
        assertNotSame(result, second)
        assertNotSame(input["kept"], result["kept"])
        assertNotSame(child, (result["kept"] as List<*>)[0])
        assertNotSame(replacementList, result["x"])
        assertEquals("value", input["old"])
        assertEquals("[remove]", changes["Old"])
        child["value"] = 10
        replacementList.clear()
        assertEquals(listOf(mapOf("value" to 1)), result["kept"])
        assertEquals(listOf(mapOf("value" to 2)), result["x"])
    }

    @Test
    fun responseDeepCopiesRawAndReplacementContainers() {
        val previous = mutableListOf<Any?>("one", "two")
        val raw = mutableListOf<Any?>("Set-Cookie", previous, "Set-Cookie", "three")
        val replacement = mutableListOf<Any?>("new")
        val added = mutableListOf<Any?>("added")
        val changes = linkedMapOf<String, Any?>("Set-Cookie" to replacement, "Added" to added)
        val result = HeaderTransforms.replaceResponseHeaders(raw, changes)
        assertEquals(listOf("Set-Cookie", listOf("one", "two"), "Set-Cookie", "three"), raw)
        assertEquals(setOf("Set-Cookie", "Added"), changes.keys)
        assertNotSame(replacement, result.rawHeaders[1])
        assertNotSame(added, result.setHeaders["added"])
        replacement.clear()
        added.clear()
        previous.clear()
        raw.clear()
        assertEquals(listOf("Set-Cookie", listOf("new"), "Set-Cookie", listOf("new")), result.rawHeaders)
        assertEquals(listOf("added"), result.setHeaders["added"])
        assertEquals("three", result.previousHeaders?.get("set-cookie"))
    }

    @Test
    fun outputsDoNotAffectLaterResults() {
        val input = linkedMapOf<String, Any?>("x" to mutableListOf<Any?>("original"))
        val first = HeaderTransforms.replaceRequestHeaders(input, emptyMap())
        @Suppress("UNCHECKED_CAST")
        (first["x"] as MutableList<Any?>).clear()
        assertEquals(input, HeaderTransforms.replaceRequestHeaders(input, emptyMap()))
        val raw = listOf<Any?>("Vary", "Origin")
        val result = HeaderTransforms.afterOptionsHeaders("OPTIONS", emptyMap(), raw)
        assertNotSame(raw, result.rawHeaders)
        assertEquals(emptyMap<String, Any?>(), result.setHeaders)
    }

    @Test
    fun rejectsCyclesNonJsonValuesAndAmbiguousBoundaries() {
        val cycle = linkedMapOf<String, Any?>()
        cycle["self"] = cycle
        val listCycle = mutableListOf<Any?>()
        listCycle.add(listCycle)
        for (invalid in listOf(cycle, listCycle, Any(), Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE, setOf("x"))) {
            assertThrows(IllegalArgumentException::class.java) {
                HeaderTransforms.replaceRequestHeaders(mapOf("bad" to invalid), emptyMap())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeaderTransforms.replaceRequestHeaders(mapOf("bad" to mapOf(1 to "value")), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeaderTransforms.replaceResponseHeaders(listOf("X"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeaderTransforms.replaceResponseHeaders(listOf(1, "value"), emptyMap())
        }
        for (key in listOf("__proto__", "constructor", "ToString", "\u0130")) {
            assertThrows(IllegalArgumentException::class.java) {
                HeaderTransforms.replaceResponseHeaders(emptyList(), mapOf(key to "value"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeaderTransforms.afterOptionsHeaders("GET", mapOf("origin" to "site"), listOf("Vary", listOf("Origin")))
        }
    }

    @Test
    fun permitsAcyclicSharedContainersButCopiesEveryOccurrence() {
        val child = mutableListOf<Any?>("value")
        val result = HeaderTransforms.replaceRequestHeaders(mapOf("a" to child, "b" to child), emptyMap())
        assertNotSame(child, result["a"])
        assertNotSame(result["a"], result["b"])
        assertEquals(result["a"], result["b"])
    }

    private fun ResponseHeadersResult.json(): Map<String, Any?> = mapOf(
        "rawHeaders" to rawHeaders,
        "setHeaders" to setHeaders,
        "previousHeaders" to previousHeaders,
        "remainingReplacements" to remainingReplacements,
    )
}

/** Dependency-free fixture reader; production code has no JSON parser or I/O. */
internal object HttpPolicyFixtures {
    data class Case(val name: String, val input: Map<String, Any?>, val expected: Any?)

    fun assertJson(name: String, expected: Any?, actual: Any?) {
        assertEquals(name, expected, actual)
        if (expected is Map<*, *> && actual is Map<*, *>) {
            assertEquals("$name object key order", expected.keys.toList(), actual.keys.toList())
            for (key in expected.keys) assertJson("$name.$key", expected[key], actual[key])
        } else if (expected is List<*> && actual is List<*>) {
            for (index in expected.indices) assertJson("$name[$index]", expected[index], actual[index])
        }
    }

    fun cases(operation: String): List<Case> {
        val explicit = System.getProperty("http.policy.fixtures")
        val fixture = if (explicit != null) File(explicit) else {
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "port/tests/fixtures/wave3/http-policy.json") }
                .firstOrNull { it.isFile } ?: error("Cannot locate http-policy.json; set -Dhttp.policy.fixtures")
        }
        val parsed = Parser(fixture.readText()).parse() as List<*>
        @Suppress("UNCHECKED_CAST")
        val cases = parsed.map {
            val item = it as Map<String, Any?>
            Case(item["name"] as String, item["input"] as Map<String, Any?>, item["expected"])
        }.filter { it.input["operation"] == operation }
        assertTrue("No upstream fixtures for $operation", cases.isNotEmpty())
        return cases
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Any? {
            val result = value()
            space()
            check(index == text.length) { "Trailing fixture content" }
            return result
        }

        private fun space() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        private fun take(char: Char): Boolean {
            space()
            if (index < text.length && text[index] == char) {
                index++
                return true
            }
            return false
        }

        private fun value(): Any? {
            space()
            return when (text[index]) {
                '{' -> {
                    index++
                    val result = linkedMapOf<String, Any?>()
                    if (!take('}')) {
                        do {
                            space()
                            val key = string()
                            check(take(':'))
                            result[key] = value()
                        } while (take(','))
                        check(take('}'))
                    }
                    result
                }
                '[' -> {
                    index++
                    val result = mutableListOf<Any?>()
                    if (!take(']')) {
                        do { result.add(value()) } while (take(','))
                        check(take(']'))
                    }
                    result
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = index
                    while (index < text.length && text[index] in "-+0123456789.eE") index++
                    text.substring(start, index).toDouble()
                }
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            check(text.startsWith(word, index))
            index += word.length
            return result
        }

        private fun string(): String {
            check(text[index++] == '"')
            val result = StringBuilder()
            while (true) {
                val char = text[index++]
                if (char == '"') return result.toString()
                if (char != '\\') {
                    result.append(char)
                } else {
                    result.append(when (val escape = text[index++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                        else -> error("Unsupported fixture escape")
                    })
                }
            }
        }
    }
}
