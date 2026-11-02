package com.dsha.sidecar.net.dns

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EchConfigListTest {
    @Test
    fun `local safety fixtures match explicit error schema`() {
        val file = listOf(
            File("port/tests/fixtures/wave3/ech-config.safety.json"),
            File("../port/tests/fixtures/wave3/ech-config.safety.json"),
        ).first { it.isFile }
        val cases = JsonReader(file.readText()).read() as List<*>
        for (entry in cases) {
            val case = entry as Map<*, *>
            val input = case["input"] as Map<*, *>
            val result = EchConfigList.parseEchConfigList(hex(input["value"] as String))
            assertTrue(case["name"].toString(), result is EchConfigListResult.Failure)
            val error = (result as EchConfigListResult.Failure).error
            assertEquals(
                case["name"].toString(),
                case["expected"],
                mapOf("error" to error.code.name, "offset" to error.offset, "field" to error.field),
            )
        }
    }

    @Test
    fun `actual upstream fixtures match parser and both metadata selection policies`() {
        val file = listOf(
            File("port/tests/fixtures/wave3/ech-config.valid.json"),
            File("../port/tests/fixtures/wave3/ech-config.valid.json"),
        ).first { it.isFile }
        val cases = JsonReader(file.readText()).read() as List<*>
        assertEquals(13, cases.size)
        for (entry in cases) {
            val case = entry as Map<*, *>
            val name = case["name"] as String
            val input = case["input"] as Map<*, *>
            assertEquals(name, "wireHex", input["kind"])
            val expected = case["expected"] as Map<*, *>
            val configs = parseSuccess(hex(input["value"] as String))
            val expectedConfigs = expected["configs"] as List<*>
            assertEquals(name, expectedConfigs.size, configs.size)
            configs.zip(expectedConfigs).forEach { (config, value) ->
                val target = value as Map<*, *>
                val contents = config.contents!!
                assertEquals(name, target["versionCode"], config.versionCode)
                assertEquals(name, target["length"], config.length)
                assertEquals(name, target["rawHex"], config.raw.toHex())
                assertEquals(name, target["configId"], contents.configId)
                assertEquals(name, target["kemId"], contents.kemId)
                assertEquals(name, target["publicKeyHex"], contents.publicKey.toHex())
                assertEquals(name, target["cipherSuites"], contents.cipherSuites.flatMap { listOf(it.kdfId, it.aeadId) })
                assertEquals(name, target["maximumNameLength"], contents.maximumNameLength)
                assertEquals(name, target["publicName"], contents.publicName)
                assertEquals(name, target["extensionsLength"], contents.extensions.sumOf { 4 + it.data.size })
            }
            assertEquals(name, expected["pickIndex"], indexOf(configs, EchConfigList.pickEchConfig(configs)))
            assertEquals(name, expected["selectIndex"], indexOf(configs, EchConfigList.selectEchConfig(configs)))
            val supported = configs.map { config ->
                config.contents!!.cipherSuites.firstOrNull { it.kdfId == 1 && it.aeadId == 1 }
                    ?.let { mapOf("kdfId" to it.kdfId, "aeadId" to it.aeadId) }
            }
            assertEquals(name, expected["supportedCipherSuites"], supported)
        }
    }

    @Test
    fun `rejects truncated outer and nested vectors and extra bytes`() {
        val valid = list(config())
        for (length in 0 until valid.size) {
            val result = EchConfigList.parseEchConfigList(valid.copyOf(length))
            assertTrue("truncation at $length", result is EchConfigListResult.Failure)
            if (length >= 2) {
                val framedPrefix = valid.copyOf(length)
                framedPrefix[0] = ((length - 2) ushr 8).toByte()
                framedPrefix[1] = (length - 2).toByte()
                assertTrue(
                    "nested truncation at $length",
                    EchConfigList.parseEchConfigList(framedPrefix) is EchConfigListResult.Failure,
                )
            }
        }
        reject(valid + byteArrayOf(0), EchConfigErrorCode.LENGTH_MISMATCH)
        reject(byteArrayOf(0, 0), EchConfigErrorCode.EMPTY_VECTOR)
        reject(ByteArray(EchConfigList.MAX_WIRE_BYTES + 1), EchConfigErrorCode.INPUT_TOO_LARGE)
        reject(list(byteArrayOf(0xfe.toByte(), 0x0d, 0, 2, 1)), EchConfigErrorCode.TRUNCATED)
        reject(list(config() + byteArrayOf(1)), EchConfigErrorCode.TRUNCATED)
        // An advertised list boundary cannot be crossed by an otherwise complete config.
        val crossing = valid.copyOf()
        crossing[1] = (crossing[1] - 1).toByte()
        reject(crossing, EchConfigErrorCode.LENGTH_MISMATCH)
        val contentExtra = config()
        contentExtra[3] = (contentExtra[3] + 1).toByte()
        reject(list(contentExtra + byteArrayOf(0)), EchConfigErrorCode.LENGTH_MISMATCH)
    }

    @Test
    fun `rejects invalid keys suites names and extensions`() {
        reject(list(config(key = ByteArray(0))), EchConfigErrorCode.INVALID_PUBLIC_KEY_LENGTH)
        reject(list(config(key = ByteArray(31))), EchConfigErrorCode.INVALID_PUBLIC_KEY_LENGTH)
        reject(list(config(kem = 0xbeef, key = ByteArray(0))), EchConfigErrorCode.INVALID_PUBLIC_KEY_LENGTH)
        reject(list(config(suites = byteArrayOf())), EchConfigErrorCode.INVALID_CIPHER_VECTOR)
        reject(list(config(suites = byteArrayOf(0, 1))), EchConfigErrorCode.INVALID_CIPHER_VECTOR)
        reject(list(config(suites = byteArrayOf(0, 1, 0, 1, 0))), EchConfigErrorCode.INVALID_CIPHER_VECTOR)
        for (name in listOf("", ".bad", "bad.", "bad..name", "-bad.example", "bad_.example", "a".repeat(64))) {
            reject(list(config(name = name)), EchConfigErrorCode.INVALID_PUBLIC_NAME)
        }
        reject(list(config(name = "a".repeat(254))), EchConfigErrorCode.INVALID_PUBLIC_NAME)
        reject(list(config(nameBytes = byteArrayOf(0xff.toByte()))), EchConfigErrorCode.INVALID_PUBLIC_NAME)
        reject(list(config(extensions = byteArrayOf(0, 1, 0))), EchConfigErrorCode.TRUNCATED)
        reject(list(config(extensions = byteArrayOf(0, 1, 0, 2, 1))), EchConfigErrorCode.TRUNCATED)
        reject(list(config(extensions = byteArrayOf(0, 1, 0, 0, 0, 1, 0, 0))), EchConfigErrorCode.DUPLICATE_EXTENSION)
    }

    @Test
    fun `declared field vectors cannot read beyond config contents`() {
        val keyOverrun = list(config())
        keyOverrun[9] = 0x7f
        keyOverrun[10] = 0xff.toByte()
        reject(keyOverrun, EchConfigErrorCode.TRUNCATED)
        val suitesOverrun = list(config())
        suitesOverrun[43] = 0x7f
        suitesOverrun[44] = 0xfc.toByte()
        reject(suitesOverrun, EchConfigErrorCode.TRUNCATED)
        val nameOverrun = list(config())
        nameOverrun[50] = 0xff.toByte()
        reject(nameOverrun, EchConfigErrorCode.TRUNCATED)
        val extensionsOverrun = list(config())
        extensionsOverrun[extensionsOverrun.lastIndex] = 1
        reject(extensionsOverrun, EchConfigErrorCode.TRUNCATED)
        val missingExtensionsLength = config().copyOf(config().size - 2)
        missingExtensionsLength[3] = (missingExtensionsLength[3] - 2).toByte()
        reject(list(missingExtensionsLength), EchConfigErrorCode.TRUNCATED)
    }

    @Test
    fun `unknown versions are opaque and unknown algorithms remain inspectable`() {
        val opaque = u16(0xfe0c) + u16(0) + byteArrayOf()
        val unknownKem = config(kem = 0x9999, key = byteArrayOf(1, 2), suites = byteArrayOf(0x12, 0x34, 0x56, 0x78))
        val configs = parseSuccess(list(opaque, unknownKem))
        assertNull(configs[0].contents)
        assertEquals(0, configs[0].raw.size)
        assertEquals(0x9999, configs[1].contents!!.kemId)
        assertEquals(listOf(EchCipherSuite(0x1234, 0x5678)), configs[1].contents!!.cipherSuites)
        assertSame(configs[1], EchConfigList.pickEchConfig(configs))
        assertNull(EchConfigList.selectEchConfig(configs))
        assertNull(EchConfigList.pickEchConfig(configs.take(1)))
    }

    @Test
    fun `unknown mandatory extension excludes config but optional is retained`() {
        val optional = byteArrayOf(0x12, 0x34, 0, 2, 5, 6)
        val mandatory = byteArrayOf(0x80.toByte(), 1, 0, 1, 7)
        val configs = parseSuccess(list(config(id = 1, extensions = mandatory), config(id = 2, extensions = optional)))
        assertTrue(configs[0].contents!!.extensions.single().isMandatory)
        assertFalse(configs[1].contents!!.extensions.single().isMandatory)
        assertArrayEquals(byteArrayOf(5, 6), configs[1].contents!!.extensions.single().data)
        assertSame(configs[1], EchConfigList.pickEchConfig(configs))
        assertSame(configs[1], EchConfigList.selectEchConfig(configs))
        assertNull(EchConfigList.pickEchConfig(configs.take(1)))
        assertNull(EchConfigList.selectEchConfig(configs.take(1)))
    }

    @Test
    fun `returned arrays and collections cannot mutate stored results`() {
        val input = list(config(extensions = byteArrayOf(0, 1, 0, 1, 42)))
        val success = EchConfigList.parseEchConfigList(input) as EchConfigListResult.Success
        val config = success.configs.single()
        val original = config.raw
        input.fill(0)
        config.raw.fill(0)
        config.contents!!.publicKey.fill(0)
        config.contents!!.extensions.single().data.fill(0)
        @Suppress("UNCHECKED_CAST")
        (success.configs as? MutableList<EchConfig>)?.clear()
        @Suppress("UNCHECKED_CAST")
        (config.contents!!.cipherSuites as? MutableList<EchCipherSuite>)?.clear()
        @Suppress("UNCHECKED_CAST")
        (config.contents!!.extensions as? MutableList<EchConfigExtension>)?.clear()
        assertEquals(1, success.configs.size)
        assertArrayEquals(original, config.raw)
        assertEquals(32, config.contents!!.publicKey.size)
        assertEquals(1, config.contents!!.publicKey[0].toInt())
        assertEquals(1, config.contents!!.cipherSuites.size)
        assertEquals(42, config.contents!!.extensions.single().data.single().toInt())
    }

    @Test
    fun `deterministic malformed input corpus never escapes parser`() {
        var state = 0x12345678
        repeat(10000) {
            state = state * 1664525 + 1013904223
            val size = (state ushr 24) and 127
            val bytes = ByteArray(size) {
                state = state * 1664525 + 1013904223
                (state ushr 24).toByte()
            }
            EchConfigList.parseEchConfigList(bytes)
            if (size >= 2) {
                bytes[0] = 0
                bytes[1] = (size - 2).toByte()
                EchConfigList.parseEchConfigList(bytes)
            }
        }
    }

    @Test
    fun `maximum wire list is bounded and malformed siblings invalidate whole list`() {
        val opaque = u16(0x1234) + vector(ByteArray(65531) { 42 })
        val maximum = list(opaque)
        assertEquals(EchConfigList.MAX_WIRE_BYTES, maximum.size)
        assertEquals(65531, parseSuccess(maximum).single().raw.size)
        reject(list(config(), config(key = ByteArray(31))), EchConfigErrorCode.INVALID_PUBLIC_KEY_LENGTH)
        reject(list(config(key = ByteArray(31)), config()), EchConfigErrorCode.INVALID_PUBLIC_KEY_LENGTH)
    }

    private fun reject(bytes: ByteArray, expected: EchConfigErrorCode) {
        val result = EchConfigList.parseEchConfigList(bytes)
        assertTrue("expected $expected, got $result", result is EchConfigListResult.Failure)
        assertEquals(expected, (result as EchConfigListResult.Failure).error.code)
        assertTrue(result.error.offset in 0..bytes.size)
    }

    private fun parseSuccess(bytes: ByteArray): List<EchConfig> {
        val result = EchConfigList.parseEchConfigList(bytes)
        assertTrue("expected success, got $result", result is EchConfigListResult.Success)
        return (result as EchConfigListResult.Success).configs
    }

    private fun indexOf(configs: List<EchConfig>, selected: EchConfig?): Int? =
        selected?.let { configs.indexOf(it) }

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun vector(bytes: ByteArray) = u16(bytes.size) + bytes
    private fun list(vararg configs: ByteArray) = vector(configs.fold(byteArrayOf()) { acc, bytes -> acc + bytes })
    private fun config(
        id: Int = 1,
        kem: Int = 0x20,
        key: ByteArray = ByteArray(32) { 1 },
        suites: ByteArray = byteArrayOf(0, 1, 0, 1),
        name: String = "public.example",
        nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8),
        extensions: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(nameBytes.size <= 255)
        val contents = byteArrayOf(id.toByte()) + u16(kem) + vector(key) + vector(suites) +
            byteArrayOf(0, nameBytes.size.toByte()) + nameBytes + vector(extensions)
        return u16(0xfe0d) + vector(contents)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Minimal JSON reader for the fixture schema, avoiding test-only dependencies. */
    private class JsonReader(private val text: String) {
        private var pos = 0
        fun read(): Any? {
            val value = value()
            whitespace()
            check(pos == text.length)
            return value
        }
        private fun whitespace() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        private fun value(): Any? {
            whitespace()
            return when (text[pos]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                'n' -> { check(text.substring(pos, pos + 4) == "null"); pos += 4; null }
                else -> {
                    val start = pos
                    while (pos < text.length && text[pos].isDigit()) pos++
                    text.substring(start, pos).toInt()
                }
            }
        }
        private fun objectValue(): Map<String, Any?> {
            pos++
            val result = linkedMapOf<String, Any?>()
            whitespace()
            while (text[pos] != '}') {
                val key = stringValue()
                whitespace()
                check(text[pos++] == ':')
                result[key] = value()
                whitespace()
                if (text[pos] != ',') break
                pos++
                whitespace()
            }
            check(text[pos++] == '}')
            return result
        }
        private fun arrayValue(): List<Any?> {
            pos++
            val result = mutableListOf<Any?>()
            whitespace()
            while (text[pos] != ']') {
                result.add(value())
                whitespace()
                if (text[pos] != ',') break
                pos++
            }
            check(text[pos++] == ']')
            return result
        }
        private fun stringValue(): String {
            check(text[pos++] == '"')
            val start = pos
            while (text[pos] != '"') {
                check(text[pos] != '\\') // The deterministic fixture schema uses no escaped strings.
                pos++
            }
            return text.substring(start, pos++)
        }
    }
}
