package com.dsha.sidecar.net.dns

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays committed upstream outputs without Node, network, or a JSON dependency. */
class DnsProviderOracleTest {
    @Test
    fun `actual Kotlin APIs match the independent upstream fixture`() {
        val fixture = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "port/tests/fixtures/wave3/dns-config.json") }.firstOrNull { it.isFile }
            ?: error("Cannot locate port/tests/fixtures/wave3/dns-config.json")
        val cases = JsonReader(fixture.readText()).read() as List<*>
        assertTrue("Expected nonempty oracle coverage", cases.size >= 25)
        for (row in cases) {
            val item = obj(row)
            assertEquals(item["name"] as String, item["expected"], run(obj(item["input"])))
        }
    }

    private fun run(input: Map<String, Any?>): Any? {
        val providers = obj(input["providers"])
        val options = obj(input["options"])
        return when (input["operation"]) {
            "toDohList" -> DnsProviderConfig.toDohList(input["doh"])
            "resolveDohList" -> DnsProviderConfig.resolveDohList(obj(input["config"]), providers)
                .map { mapOf("url" to it.url, "sni" to it.sni) }
            "normalize", "select" -> {
                val catalog = try {
                    DnsProviderConfig.normalize(providers, options)
                } catch (e: IllegalArgumentException) {
                    return mapOf("error" to e.message)
                }
                if (input["operation"] == "normalize") {
                    mapOf(
                        "bindings" to catalog.bindings,
                        "descriptors" to catalog.descriptors.map {
                            mapOf(
                                "key" to it.key, "name" to it.name, "transport" to it.transport,
                                "server" to it.server, "port" to it.port, "family" to it.family,
                                "cacheSize" to it.cacheSize, "sni" to it.sni,
                                "echOptions" to it.echOptions, "nat64Options" to it.nat64Options,
                            )
                        },
                    )
                } else {
                    val ech = input["ech"]?.let(::obj)
                    val selection = DnsProviderSelection(
                        catalog, obj(input["mapping"]), obj(input["preSetIpList"]), ech,
                        nat64Domains = input["nat64Domains"],
                        echDomains = if ("echDomains" in input) input["echDomains"] else ech?.get("domains"),
                        echPreSetIpDomains = if ("echPreSetIpDomains" in input) input["echPreSetIpDomains"]
                            else ech?.get("preSetIpDomains"),
                    )
                    val hostname = input["hostname"] as String
                    mapOf(
                        "choice" to selection.select(hostname)?.let {
                            mapOf("providerKey" to it.providerKey, "family" to it.family)
                        },
                        "isNat64" to selection.isNat64Domain(hostname),
                        "isEch" to selection.isEchDomain(hostname),
                        "ignorePreSet" to selection.isEchIgnorePreSetIp(hostname),
                        "echProviderKey" to selection.getEchProviderKey(),
                    )
                }
            }
            else -> error("Unknown fixture operation: ${input["operation"]}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: emptyMap()

    /** Test-only JSON reader: no production dependency or fixture-specific oracle logic. */
    private class JsonReader(private val text: String) {
        private var cursor = 0
        fun read(): Any? {
            val result = value()
            space()
            check(cursor == text.length) { "Trailing JSON" }
            return result
        }

        private fun space() {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        }

        private fun value(): Any? {
            space()
            return when (text[cursor]) {
                '{' -> objectValue()
                '[' -> listValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = cursor
                    while (cursor < text.length && text[cursor] in "-+0123456789.eE") cursor++
                    val number = text.substring(start, cursor)
                    number.toLongOrNull() ?: number.toDouble()
                }
            }
        }

        private fun literal(token: String, result: Any?): Any? {
            check(text.startsWith(token, cursor))
            cursor += token.length
            return result
        }

        private fun objectValue(): Map<String, Any?> {
            cursor++
            val result = linkedMapOf<String, Any?>()
            space()
            if (text[cursor] == '}') { cursor++; return result }
            while (true) {
                space()
                val key = stringValue()
                space()
                check(text[cursor++] == ':')
                result[key] = value()
                space()
                when (text[cursor++]) {
                    '}' -> return result
                    ',' -> Unit
                    else -> error("Expected comma or brace")
                }
            }
        }

        private fun listValue(): List<Any?> {
            cursor++
            val result = mutableListOf<Any?>()
            space()
            if (text[cursor] == ']') { cursor++; return result }
            while (true) {
                result += value()
                space()
                when (text[cursor++]) {
                    ']' -> return result
                    ',' -> Unit
                    else -> error("Expected comma or bracket")
                }
            }
        }

        private fun stringValue(): String {
            check(text[cursor++] == '"')
            val result = StringBuilder()
            while (true) {
                when (val char = text[cursor++]) {
                    '"' -> return result.toString()
                    '\\' -> result.append(when (val escape = text[cursor++]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'u' -> text.substring(cursor, cursor + 4).toInt(16).toChar().also { cursor += 4 }
                        '"', '/', '\\' -> escape
                        else -> error("Invalid JSON escape")
                    })
                    else -> result.append(char)
                }
            }
        }
    }
}
