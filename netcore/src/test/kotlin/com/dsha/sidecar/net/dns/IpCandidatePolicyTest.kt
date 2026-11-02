package com.dsha.sidecar.net.dns

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class IpCandidatePolicyTest {
    @Test
    fun `replays upstream generated fixtures without Node or network`() {
        val fixtures = fixtures("ip-policy.json")
        assertTrue("Expected substantial upstream coverage", fixtures.size >= 56)
        for (fixture in fixtures) {
            val input = fixture["input"].asObject()
            val actual: Any? = when (input["operation"]) {
                "zero" -> IpCandidatePolicy.isZeroIp(input["value"])
                "ipv6" -> IpCandidatePolicy.isIpv6(input["value"] as String)
                "speed" -> IpCandidatePolicy.speedCandidates(input["candidates"].asStrings())
                "preset" -> IpCandidatePolicy.selectPreset(
                    input["preset"], input["ignore"] == true,
                )?.let { mapOf("addresses" to it.addresses, "isPreset" to it.isPreset) }
                "dns" -> IpCandidatePolicy.dnsCandidates(
                    input["candidates"]?.asStrings(),
                    input["hostname"] as String,
                    input["available"] as Boolean,
                )
                "availability" -> {
                    var state = Ipv6Availability.fromInterfaceAvailability(input["available"] as Boolean)
                    val states = mutableListOf(state.asMap())
                    for (address in input["reports"].asStrings()) {
                        state = state.reportUnreachable(address)
                        states.add(state.asMap())
                    }
                    states
                }
                else -> error("Unknown operation: $input")
            }
            assertEquals(fixture["name"] as String, fixture["expected"], actual)
        }
    }

    @Test
    fun `intentional schema rejections are separate from upstream parity`() {
        for (fixture in fixtures("ip-policy-rejections.json")) {
            assertEquals("unsupported-preset", fixture["expected"])
            val preset = fixture["input"].asObject()["preset"]
            assertThrows(fixture["name"] as String, IllegalArgumentException::class.java) {
                IpCandidatePolicy.selectPreset(preset)
            }
        }
        for (unsupported in listOf(
            mapOf(1 to true), mapOf("ip" to Double.NaN),
            mapOf("ip" to Double.POSITIVE_INFINITY), mapOf("ip" to Any()),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                IpCandidatePolicy.selectPreset(unsupported)
            }
        }
    }

    @Test
    fun `preset result never aliases caller inputs or previous getters`() {
        val input = mutableListOf("192.0.2.1", "::1", "192.0.2.1")
        val selection = IpCandidatePolicy.selectPreset(input)!!
        input.clear()
        assertEquals(listOf("192.0.2.1", "::1", "192.0.2.1"), selection.addresses)
        (selection.addresses as MutableList<String>).clear()
        assertEquals(listOf("192.0.2.1", "::1", "192.0.2.1"), selection.addresses)
        val flags = linkedMapOf<String, Any?>("a" to mutableMapOf("desc" to "enabled"), "b" to false)
        val mapped = IpCandidatePolicy.selectPreset(flags)!!
        flags.clear()
        assertEquals(listOf("a"), mapped.addresses)
    }

    @Test
    fun `JSON truthiness includes empty containers and excludes exact strings only`() {
        val flags = linkedMapOf<String, Any?>(
            "false" to false, "null" to null, "zero" to -0.0, "empty" to "",
            "falseString" to "false", "zeroString" to "0", "upper" to "FALSE",
            "space" to " ", "array" to emptyList<Any?>(), "object" to emptyMap<String, Any?>(),
            "negative" to -1, "fraction" to 0.25, "true" to true,
        )
        assertEquals(
            listOf("upper", "space", "array", "object", "negative", "fraction", "true"),
            IpCandidatePolicy.selectPreset(flags)!!.addresses,
        )
        assertNull(IpCandidatePolicy.selectPreset(emptyMap<String, Any?>()))
        assertNull(IpCandidatePolicy.selectPreset(listOf("::1"), true))
        assertNull(IpCandidatePolicy.selectPreset("unsupported but bypassed", true))
    }

    @Test
    fun `DNS fallback and speed exclusion preserve order and copy caller lists`() {
        val input = mutableListOf("::", "0.0.0.0", "192.0.2.1", "192.0.2.1")
        val dns = IpCandidatePolicy.dnsCandidates(input, "::1", false)
        val speed = IpCandidatePolicy.speedCandidates(input)
        assertEquals(listOf("0.0.0.0", "192.0.2.1", "192.0.2.1", "::1"), dns)
        assertEquals(listOf("192.0.2.1", "192.0.2.1"), speed)
        assertEquals(4, input.size)
        input.clear()
        assertEquals(4, dns.size)
        assertEquals(2, speed.size)
        assertEquals(listOf("example.test"), IpCandidatePolicy.dnsCandidates(null, "example.test", false))
        assertEquals(listOf("same", "same"), IpCandidatePolicy.dnsCandidates(listOf("same"), "same", true))
    }

    @Test
    fun `compatibility adapters intentionally differ from strict literal parsing`() {
        for (value in listOf(" :: ", "[::", "::]", "[0.0.0.0]", "0.0.0.0]", "\uFEFF::")) {
            assertTrue(value, IpCandidatePolicy.isZeroIp(value))
            assertFalse(value, IpAddress.isZeroIp(value))
        }
        for (value in listOf("::0.0.0.0", "::%eth0")) {
            assertFalse(value, IpCandidatePolicy.isZeroIp(value))
            assertTrue(value, IpAddress.isZeroIp(value))
        }
        for (value in listOf("garbage[", "[::1", " ::1 ")) {
            assertTrue(value, IpCandidatePolicy.isIpv6(value))
            assertFalse(value, IpAddress.isIpv6(value))
        }
        assertFalse(IpCandidatePolicy.isIpv6("2001:db8::1%eth0"))
        assertTrue(IpAddress.isIpv6("2001:db8::1%eth0"))
        assertFalse(IpCandidatePolicy.isZeroIp("[[::]]"))
        assertFalse(IpCandidatePolicy.isZeroIp("\u0085::"))
        assertFalse(IpCandidatePolicy.isZeroIp("::ffff:0.0.0.0"))
    }

    private fun Ipv6Availability.asMap(): Map<String, Any> =
        mapOf("available" to available, "errorCount" to errorCount.toLong())

    private fun fixtures(name: String): List<Map<String, Any?>> {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "port/tests/fixtures/wave3/$name").isFile }
            ?: error("Cannot locate wave3 fixture $name from user.dir")
        return (JsonFixtureReader(File(root, "port/tests/fixtures/wave3/$name").readText()).read() as List<*>)
            .map { it.asObject() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(): Map<String, Any?> = this as Map<String, Any?>

    private fun Any?.asStrings(): List<String> = (this as List<*>).map { it as String }

    /** Test-only JSON transport reader; production policy has no serialization dependency. */
    private class JsonFixtureReader(private val text: String) {
        private var offset = 0

        fun read(): Any? {
            val value = value()
            space()
            check(offset == text.length) { "Trailing JSON" }
            return value
        }

        private fun space() {
            while (offset < text.length && text[offset] in " \r\n\t") offset++
        }

        private fun value(): Any? {
            space()
            return when (text[offset]) {
                '"' -> string()
                '[' -> {
                    offset++
                    val result = mutableListOf<Any?>()
                    space()
                    if (text[offset] != ']') {
                        do {
                            result.add(value())
                            space()
                        } while (take(','))
                    }
                    check(take(']'))
                    result
                }
                '{' -> {
                    offset++
                    val result = linkedMapOf<String, Any?>()
                    space()
                    if (text[offset] != '}') {
                        do {
                            space()
                            val key = string()
                            space()
                            check(take(':'))
                            result[key] = value()
                            space()
                        } while (take(','))
                    }
                    check(take('}'))
                    result
                }
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = offset
                    while (offset < text.length && text[offset] in "-+0123456789.eE") offset++
                    val number = text.substring(start, offset)
                    number.toLongOrNull() ?: number.toDouble()
                }
            }
        }

        private fun take(char: Char): Boolean =
            (offset < text.length && text[offset] == char).also { if (it) offset++ }

        private fun literal(token: String, result: Any?): Any? {
            check(text.startsWith(token, offset))
            offset += token.length
            return result
        }

        private fun string(): String {
            check(take('"'))
            val result = StringBuilder()
            while (true) {
                val char = text[offset++]
                if (char == '"') return result.toString()
                if (char != '\\') {
                    result.append(char)
                    continue
                }
                result.append(when (val escaped = text[offset++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> text.substring(offset, offset + 4).toInt(16).toChar().also { offset += 4 }
                    else -> error("Invalid escape: $escaped")
                })
            }
        }
    }
}
