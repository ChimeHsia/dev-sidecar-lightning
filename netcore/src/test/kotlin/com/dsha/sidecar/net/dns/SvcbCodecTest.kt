// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns

import java.io.File
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SvcbCodecTest {
    @Test
    fun `replay actual pinned Node outputs`() {
        val cases = fixtures()
        assertTrue(cases.size >= 27)
        for (case in cases) {
            val input = case.obj("input")
            val actual = evaluate(input)
            assertEquals(case["name"].toString(), normalize(case["expected"]), normalize(actual))
        }
    }

    @Test
    fun `compressed name returns offset after pointer and copies labels`() {
        val wire = hex("0161000162c000ff")
        val decoded = SvcbCodec.decodeName(wire, 3)
        assertEquals("b.a", decoded.name)
        assertEquals(7, decoded.offset)
        assertEquals(listOf("62", "61"), decoded.labels.map(::toHex))
        wire.fill(0)
        assertEquals(listOf("62", "61"), decoded.labels.map(::toHex))
    }

    @Test
    fun `names reject cycles invalid pointers reserved labels and partial ranges`() {
        for (bytes in listOf("", "c000", "c002c000", "c0", "c0ff", "40", "80", "0261")) {
            reject { SvcbCodec.decodeName(hex(bytes), 0) }
        }
        reject { SvcbCodec.decodeName(hex("00"), -1) }
        reject { SvcbCodec.decodeName(hex("00"), Int.MAX_VALUE) }
        reject { SvcbCodec.decodeName(hex("00"), 0, 2) }
        reject { SvcbCodec.decodeName(hex("016100"), 0, 2) }
        reject { SvcbCodec.decodeName(hex("c00200"), 0, 1) }
    }

    @Test
    fun `compression hops are bounded independently of packet size`() {
        fun chain(hops: Int): ByteArray {
            val bytes = ByteArray(hops * 2 + 1)
            repeat(hops) { i -> put16(bytes, i * 2, 0xc000 or ((i + 1) * 2)) }
            return bytes
        }
        assertEquals("", SvcbCodec.decodeName(chain(128), 0).name)
        reject { SvcbCodec.decodeName(chain(129), 0) }
    }

    @Test
    fun `expanded names enforce DNS size even through compression`() {
        val maximal = List(3) { "a".repeat(63) }.plus("b".repeat(61)).joinToString(".")
        assertEquals(255, SvcbCodec.encodeName(maximal).size)
        assertEquals(maximal, SvcbCodec.decodeName(SvcbCodec.encodeName(maximal), 0).name)
        reject { SvcbCodec.encodeName("$maximal.c") }
        reject { SvcbCodec.encodeName("a".repeat(64)) }
        val large = ByteArray(257)
        for (pos in listOf(0, 64, 128, 192)) {
            large[pos] = 63
            for (i in pos + 1..pos + 63) large[i] = 97
        }
        reject { SvcbCodec.decodeName(large, 0) }
    }

    @Test
    fun `RDATA bounds name headers values and integer overflow`() {
        for (wire in listOf("0001", "0001016100", "00010001", "0001000003000201")) {
            reject { SvcbCodec.decodeSvcRecord(hex(wire), 0, if (wire == "0001016100") 3 else wire.length / 2) }
        }
        reject { SvcbCodec.decodeSvcRecord(hex("000100"), -1, 3) }
        reject { SvcbCodec.decodeSvcRecord(hex("000100"), 0, -1) }
        reject { SvcbCodec.decodeSvcRecord(hex("000100"), 1, Int.MAX_VALUE) }
        reject { SvcbCodec.decodeSvcRecord(hex("0001c00400"), 0, 3) }
    }

    @Test
    fun `RDATA pointer may target packet bytes outside its local boundary`() {
        val wire = hex("0161000001c0000003000201bbff")
        val record = SvcbCodec.decodeSvcRecord(wire, 3, 10)
        assertEquals("a", record.target)
        assertEquals(443, (record.params.single().value as SvcbValue.Number).number)
        assertEquals(7, SvcbCodec.decodeName(wire, 5, 7).offset)
    }

    @Test
    fun `strict parameter failures reject upstream partial decoding`() {
        for ((key, wire) in listOf(
            0 to "00", 1 to "0268", 1 to "00", 2 to "00", 3 to "", 3 to "01", 3 to "01bb00",
            4 to "010203", 6 to "000000000000000000000000000000", 8 to "ff",
        )) reject { SvcbCodec.decodeSvcParam(key, hex(wire)) }
        reject { SvcbCodec.decodeSvcParam(-1, byteArrayOf()) }
        reject { SvcbCodec.decodeSvcParam(65536, byteArrayOf()) }
        reject { SvcbCodec.decodeSvcParam(5, ByteArray(65536)) }
    }

    @Test
    fun `service preserves duplicate order first map value and opaque ECH`() {
        val bytes = hex("0001000003000201bb0005000400fffe0d000300022053fffe00028000")
        val record = SvcbCodec.decodeSvcRecord(bytes, 0, bytes.size)
        assertEquals(".", record.target)
        assertEquals(listOf(3, 5, 3, 65534), record.params.map { it.key })
        assertEquals(SvcbValue.Number(443), record.paramMap["port"])
        assertArrayEquals(hex("00fffe0d"), record.ech)
        assertArrayEquals(hex("8000"), (record.params.last().value as SvcbValue.Bytes).bytes)
        bytes.fill(0)
        assertEquals("01bb", toHex(record.params.first().raw))
        assertArrayEquals(hex("00fffe0d"), record.ech)
    }

    @Test
    fun `response preserves IDs flags sections class unknown data and unsigned TTL`() {
        val input = fixtures().first { it["name"] == "response all sections" }.obj("input")
        val wire = hex(input.text("wire"))
        val response = SvcbCodec.parseResponse(wire)
        assertEquals(0xbeef, response.id)
        assertEquals(0x8383, response.flags)
        assertEquals(3, response.rcode)
        assertTrue(response.truncated)
        assertEquals(0xffffffffL, response.answers.first().ttl)
        assertEquals(listOf(SvcbSection.ANSWER, SvcbSection.ANSWER, SvcbSection.AUTHORITY, SvcbSection.ADDITIONAL),
            response.answers.map { it.section })
        assertEquals("www.example.test", response.answers[1].cname)
        assertEquals(65000, response.answers[2].typeCode)
        assertEquals(3, response.answers[2].klass)
        assertEquals("deadbeef", toHex(response.answers[2].raw))
        val snapshot = wire.copyOf()
        wire.fill(0)
        assertArrayEquals(snapshot, response.raw)
        assertEquals("deadbeef", toHex(response.answers[2].raw))
    }

    @Test
    fun `response rejects every truncation of a complete multi section packet`() {
        val wire = hex(fixtures().first { it["name"] == "response all sections" }.obj("input").text("wire"))
        for (size in wire.indices) reject { SvcbCodec.parseResponse(wire.copyOf(size)) }
        reject { SvcbCodec.parseResponse(wire + byteArrayOf(0)) }
    }

    @Test
    fun `response rejects impossible counts excessive counts and short known RDATA`() {
        for (header in listOf(
            "000080000101000000000000", "000080000001000000000000",
            "000080000000ffff00000000", "0000800000000000ffff0000",
        )) reject { SvcbCodec.parseResponse(hex(header)) }
        for (rdata in listOf("", "00", "0001")) {
            val rr = hex("000041000100000001") + ushort(rdata.length / 2) + hex(rdata)
            reject { SvcbCodec.parseResponse(hex("000080000000000100000000") + rr) }
        }
        reject { SvcbCodec.parseResponse(ByteArray(65536)) }
    }

    @Test
    fun `CNAME must consume exactly its RDATA`() {
        for (data in listOf("016100", "0000", "", "c00c")) {
            // Last case points into its own owner, which is a valid root name.
            val wire = hex("000080000000000100000000000005000100000001") + ushort(data.length / 2) + hex(data)
            if (data == "016100" || data == "c00c") {
                assertNotNull(SvcbCodec.parseResponse(wire).answers.single().cname)
            } else reject { SvcbCodec.parseResponse(wire) }
        }
    }

    @Test
    fun `parameters tokens and text have independent finite limits`() {
        val data = hex("000100") + List(SvcbCodec.MAX_PARAMS + 1) { hex("ffff0000") }.fold(byteArrayOf()) { a, b -> a + b }
        reject { SvcbCodec.decodeSvcRecord(data, 0, data.size) }
        reject { SvcbCodec.tokenize(List(SvcbCodec.MAX_TOKENS + 1) { "x" }.joinToString(" ")) }
        reject { SvcbCodec.tokenize("a".repeat(65536)) }
        reject { SvcbCodec.parsePresentation("1 . " + List(SvcbCodec.MAX_PARAMS + 1) { "unknown" }.joinToString(" ")) }
    }

    @Test
    fun `query validates numeric fields without narrowing wire ID`() {
        for (id in listOf(0, 32768, 65535)) {
            val query = SvcbCodec.encodeQuery(".", id)
            assertEquals(id, SvcbCodec.parseResponse(query).id)
            assertEquals(id ushr 8, query[0].toInt() and 255)
            assertEquals(id and 255, query[1].toInt() and 255)
        }
        for (n in listOf(-1, 65536, Int.MIN_VALUE, Int.MAX_VALUE)) {
            reject { SvcbCodec.encodeQuery(".", n) }
            reject { SvcbCodec.encodeQuery(".", 1, n) }
            reject { SvcbCodec.encodeQuery(".", 1, udpPayloadSize = n) }
        }
    }

    @Test
    fun `presentation rejects incomplete quotes numbers flags and base64`() {
        for (text in listOf(
            "", "1", "garbage .", "-1 .", "65536 .", "1junk .", "1 \"\"",
            "1 . alpn=\"unterminated", "1 . ech=\"abc\\", "1 . port=1junk", "1 . port=65536",
            "1 . alpn", "1 . ech", "1 . no-default-alpn=x", "1 . =x",
            "1 . ech=A", "1 . ech=AA=A", "1 . ech=AA===", "1 . ech=A!==",
            "1 . ech=AB==", "1 . ech=AAA==", "1 . ech=_w==",
        )) reject { SvcbCodec.parsePresentation(text) }
    }

    @Test
    fun `presentation decodes canonical padded and unpadded ECH opaquely`() {
        for ((text, expected) in listOf("" to "", "AA==" to "00", "AA" to "00", "AAA=" to "0000",
            "AAA" to "0000", "/w==" to "ff", "////" to "ffffff")) {
            assertArrayEquals(hex(expected), SvcbCodec.parsePresentation("1 . ech=\"$text\"").ech)
        }
        val parsed = SvcbCodec.parsePresentation("1 . no-default-alpn port=443 port=8443 key123=raw")
        assertEquals(2, parsed.params.first().key)
        assertEquals("no-default-alpn", parsed.params.first().name)
        assertEquals(SvcbValue.Number(443), parsed.paramMap["port"])
        assertEquals(-1, parsed.params.last().key)
    }

    @Test
    fun `tokenizer matches upstream quote concatenation empty tokens and JS whitespace`() {
        assertEquals(listOf("ab cd", "", "x\\y", "z\"q"),
            SvcbCodec.tokenize("a\"b c\"d \"\" x\\y \"z\\\"q\""))
        assertEquals(listOf("1", ".", "ohttp"), SvcbCodec.tokenize("\uFEFF1\u00A0.\u2028ohttp"))
    }

    @Test(timeout = 5000)
    fun `arbitrary small bytes terminate with explicit format failures only`() {
        val random = Random(9460)
        repeat(3000) {
            val bytes = random.nextBytes(random.nextInt(0, 160))
            safe { SvcbCodec.decodeName(bytes, 0) }
            safe { SvcbCodec.decodeSvcRecord(bytes, 0, bytes.size) }
            safe { SvcbCodec.parseResponse(bytes) }
            for (key in 0..9) safe { SvcbCodec.decodeSvcParam(key, bytes) }
        }
    }

    private fun reject(block: () -> Any?) {
        assertThrows(SvcbFormatException::class.java) { block() }
    }

    private fun safe(block: () -> Any?) {
        try { block() } catch (_: SvcbFormatException) { /* Expected explicit rejection. */ }
    }

    private fun fixtures(): List<Map<String, Any?>> {
        val override = System.getProperty("svcb.fixtures")
        val file = if (override != null) File(override) else listOf(
            File("port/tests/fixtures/wave3/svcb.json"),
            File("../port/tests/fixtures/wave3/svcb.json"),
        ).first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        return SvcbFixtureJson(file.readText()).parse() as List<Map<String, Any?>>
    }

    private fun evaluate(i: Map<String, Any?>): Any = when (i.text("op")) {
        "type" -> {
            val type = i["type"]
            mapOf("name" to SvcbCodec.typeName(i.int("code")),
                "isSvc" to if (type is String) SvcbCodec.isSvcType(type) else SvcbCodec.isSvcType((type as Number).toInt()),
                "code" to if (type is String) SvcbCodec.typeCode(type) else SvcbCodec.typeCode((type as Number).toInt()))
        }
        "encodeName" -> toHex(SvcbCodec.encodeName(i.text("text")))
        "query" -> {
            val o = i.obj("options")
            toHex(SvcbCodec.encodeQuery(i.text("name"), o.int("id"), o.numberOr("type", 65),
                o.numberOr("udpPayloadSize", 1232), o["dnssecOk"] == true))
        }
        "decodeName" -> SvcbCodec.decodeName(hex(i.text("wire")), i.int("offset")).let {
            mapOf("name" to it.name, "offset" to it.offset)
        }
        "param" -> project(SvcbCodec.decodeSvcParam(i.int("key"), hex(i.text("wire"))))
        "record" -> project(SvcbCodec.decodeSvcRecord(hex(i.text("wire")), i.int("offset"), i.int("length")))
        "response" -> SvcbCodec.parseResponse(hex(i.text("wire"))).let { r ->
            mapOf("id" to r.id, "flags" to r.flags, "rcode" to r.rcode, "truncated" to r.truncated,
                "questions" to r.questions.map { mapOf("name" to it.name, "type" to it.type, "typeCode" to it.typeCode, "klass" to it.klass) },
                "answers" to r.answers.map { rr ->
                    mutableMapOf<String, Any?>("name" to rr.name, "type" to rr.type, "typeCode" to rr.typeCode,
                        "klass" to rr.klass, "ttl" to rr.ttl, "section" to rr.section.name, "raw" to toHex(rr.raw)).also {
                        rr.service?.let { service -> it["service"] = project(service) }
                        rr.cname?.let { cname -> it["cname"] = cname }
                    }
                })
        }
        "tokenize" -> SvcbCodec.tokenize(i.text("text"))
        "presentation" -> SvcbCodec.parsePresentation(i.text("text")).let { r ->
            mapOf("priority" to r.priority, "target" to r.target, "params" to r.params.map {
                mapOf("key" to it.key, "name" to it.name, "value" to project(it.value))
            })
        }
        else -> error("unknown fixture operation")
    }

    private fun project(r: SvcbRecord): Map<String, Any> =
        mapOf("priority" to r.priority, "target" to r.target, "params" to r.params.map(::project))

    private fun project(p: SvcbParam): Map<String, Any> =
        mapOf("key" to p.key, "name" to p.name, "raw" to toHex(p.raw), "value" to project(p.value))

    private fun project(v: SvcbValue): Any = when (v) {
        is SvcbValue.Strings -> v.items
        is SvcbValue.Number -> v.number
        SvcbValue.Flag -> true
        is SvcbValue.Text -> v.text
        is SvcbValue.Bytes -> mapOf("hex" to toHex(v.bytes))
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Number -> value.toLong()
        is List<*> -> value.map(::normalize)
        is Map<*, *> -> value.mapValues { normalize(it.value) }
        else -> value
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0)
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun ushort(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun put16(bytes: ByteArray, pos: Int, value: Int) { bytes[pos] = (value ushr 8).toByte(); bytes[pos + 1] = value.toByte() }
    private fun Map<String, Any?>.text(key: String): String = this[key] as String
    private fun Map<String, Any?>.int(key: String): Int = (this[key] as Number).toInt()
    private fun Map<String, Any?>.numberOr(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>
}

/** Fixture-only JSON reader: avoids adding dependencies or production JSON machinery. */
private class SvcbFixtureJson(private val text: String) {
    private var pos = 0
    fun parse(): Any? {
        val result = value()
        space()
        require(pos == text.length)
        return result
    }
    private fun space() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
    private fun value(): Any? {
        space()
        return when (text[pos]) {
            '{' -> {
                pos++
                val map = linkedMapOf<String, Any?>()
                space()
                if (text[pos] != '}') while (true) {
                    space()
                    val key = string()
                    space()
                    require(text[pos++] == ':')
                    map[key] = value()
                    space()
                    if (text[pos] == '}') break
                    require(text[pos++] == ',')
                }
                pos++
                map
            }
            '[' -> {
                pos++
                val list = mutableListOf<Any?>()
                space()
                if (text[pos] != ']') while (true) {
                    list.add(value())
                    space()
                    if (text[pos] == ']') break
                    require(text[pos++] == ',')
                }
                pos++
                list
            }
            '"' -> string()
            't' -> { require(text.substring(pos, pos + 4) == "true"); pos += 4; true }
            'f' -> { require(text.substring(pos, pos + 5) == "false"); pos += 5; false }
            'n' -> { require(text.substring(pos, pos + 4) == "null"); pos += 4; null }
            else -> {
                val start = pos
                if (text[pos] == '-') pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
                text.substring(start, pos).toLong()
            }
        }
    }
    private fun string(): String {
        require(text[pos++] == '"')
        val out = StringBuilder()
        while (true) {
            val c = text[pos++]
            if (c == '"') return out.toString()
            if (c != '\\') out.append(c) else when (val escaped = text[pos++]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> { out.append(text.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                else -> error("invalid fixture escape")
            }
        }
    }
}
