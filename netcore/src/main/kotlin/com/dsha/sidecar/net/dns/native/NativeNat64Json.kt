// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.SvcbCodec
import com.dsha.sidecar.net.dns.SvcbRecord
import com.dsha.sidecar.net.dns.SvcbValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded RFC 8427-style JSON adapter. No generic JSON dependency or OS name service. */
object NativeNat64Json {
    data class Parsed(
        val value: NativeNat64DohAnswer,
        val followUp: String? = null,
        val aliasTtlMillis: Long = Long.MAX_VALUE,
        val aliases: List<String> = emptyList(),
    )
    private data class Record(val owner: String, val type: Int, val ttl: Long, val data: String)

    fun target(uri: URI, hostname: String, type: NativeNat64Question): String {
        val host = NativeDnsWire.name(hostname)
        val fields = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.filter {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") !in listOf("name", "type")
        }.toMutableList()
        fields.add("name=" + URLEncoder.encode(host, "UTF-8"))
        fields.add("type=${type.name}")
        if (fields.none { URLDecoder.decode(it.substringBefore('='), "UTF-8") == "ct" }) {
            fields.add("ct=application%2Fdns-json")
        }
        return uri.rawPath.orEmpty().ifEmpty { "/" } + "?" + fields.joinToString("&")
    }

    fun parse(
        bytes: ByteArray,
        hostname: String,
        type: NativeNat64Question,
        maxRecords: Int = 128,
        maxAddresses: Int = 32,
        maxAliases: Int = 8,
    ): Parsed {
        try {
            val root = Json(bytes).read() as? Map<*, *> ?: error("JSON object required")
            val status = integer(root["Status"], "Status")
            require(status == 0L || status == 3L) { "DoH DNS status $status" }
            require(root["TC"] != true) { "Truncated JSON DoH reply" }
            // Providers use both the array form and a single-question object.
            val questions = when (val input = root["Question"]) {
                is List<*> -> input
                is Map<*, *> -> listOf(input)
                else -> error("Missing DoH question")
            }
            require(questions.size == 1) { "Expected one DoH question" }
            val question = questions.single() as? Map<*, *> ?: error("Invalid DoH question")
            val host = NativeDnsWire.name(hostname)
            require(NativeDnsWire.name(text(question["name"])) == host &&
                integer(question["type"], "question.type") == type.code.toLong()) { "DoH question mismatch" }
            val input = root["Answer"] ?: emptyList<Any?>()
            require(input is List<*> && input.size <= maxRecords) { "Too many DoH answers" }
            val records = input.map { entry ->
                val value = entry as? Map<*, *> ?: error("Invalid DoH record")
                require(value["class"] == null || integer(value["class"], "class") == 1L) { "Non-IN DoH answer" }
                val rrType = integer(value["type"], "type")
                val ttl = integer(value["TTL"], "TTL")
                require(rrType in 0..65535 && ttl in 0..0xffffffffL) { "Invalid DNS type/TTL" }
                Record(NativeDnsWire.name(text(value["name"])), rrType.toInt(), ttl * 1000, text(value["data"]))
            }
            var owner = host
            var ttl = Long.MAX_VALUE
            val visited = mutableSetOf(host)
            val aliases = mutableListOf<String>()
            while (true) {
                val cname = records.filter { it.owner == owner && it.type == 5 }
                if (cname.isEmpty()) break
                require(records.none { it.owner == owner && it.type == type.code }) { "CNAME and data coexist" }
                val targets = cname.map { NativeDnsWire.name(it.data) }.distinct()
                require(targets.size == 1 && aliases.size < maxAliases && visited.add(targets.single())) {
                    "CNAME cycle/conflict/limit"
                }
                ttl = minOf(ttl, cname.minOf { it.ttl })
                owner = targets.single()
                aliases.add(owner)
            }
            val chosen = records.filter { it.owner == owner && it.type == type.code }
            require(chosen.size <= maxAddresses && (status != 3L || chosen.isEmpty())) { "Invalid DoH result count/status" }
            val negative = negativeTtl(root["Authority"], owner, maxRecords)
            val code = when {
                status == 3L -> NativeDnsAnswerCode.NXDOMAIN
                chosen.isEmpty() -> NativeDnsAnswerCode.NODATA
                else -> NativeDnsAnswerCode.ANSWER
            }
            val answer = if (type == NativeNat64Question.A) {
                val addresses = linkedMapOf<String, Long>()
                for (record in chosen) {
                    require(IpAddress.isIpv4(record.data)) { "Invalid DoH IPv4 address" }
                    addresses[record.data] = minOf(addresses[record.data] ?: Long.MAX_VALUE, ttl, record.ttl)
                }
                NativeNat64DohAnswer.Addresses(NativeDnsAnswer(
                    addresses.map { NativeDnsRecord(it.key, it.value) }, code,
                    if (chosen.isEmpty()) minOf(ttl, negative.second) else 0,
                ))
            } else {
                NativeNat64DohAnswer.Services(HttpsRecordAnswer(chosen.map {
                    HttpsServiceRecord(owner, minOf(ttl, it.ttl), service(it.data))
                }, code, if (chosen.isEmpty()) minOf(ttl, negative.second) else 0, aliases))
            }
            return Parsed(answer,
                owner.takeIf { aliases.isNotEmpty() && chosen.isEmpty() && status == 0L && !negative.first },
                ttl, aliases)
        } catch (error: IllegalArgumentException) {
            throw NativeDnsProtocolException(error.message ?: "Invalid NAT64 DoH JSON")
        } catch (error: IllegalStateException) {
            throw NativeDnsProtocolException(error.message ?: "Invalid NAT64 DoH JSON")
        }
    }

    private fun negativeTtl(input: Any?, host: String, limit: Int): Pair<Boolean, Long> {
        if (input == null) return false to 0L
        require(input is List<*> && input.size <= limit) { "Too many DoH authority records" }
        var minimum = Long.MAX_VALUE
        for (entry in input) {
            val record = entry as? Map<*, *> ?: error("Invalid authority record")
            if (integer(record["type"], "authority.type") != 6L) continue
            val zone = NativeDnsWire.name(text(record["name"]))
            val fields = text(record["data"]).trim().split(Regex("\\s+"))
            require(fields.size == 7)
            val ttl = integer(record["TTL"], "authority.TTL")
            val soa = fields.last().toLongOrNull()
            require(ttl in 0..0xffffffffL && soa != null && soa in 0..0xffffffffL)
            if (host == zone || host.endsWith(".$zone")) minimum = minOf(minimum, ttl * 1000, soa * 1000)
        }
        return (minimum != Long.MAX_VALUE) to if (minimum == Long.MAX_VALUE) 0 else minimum
    }

    private fun service(data: String): SvcbRecord {
        val presentation = SvcbCodec.parsePresentation(data)
        val names = listOf("mandatory", "alpn", "no-default-alpn", "port", "ipv4hint", "ech", "ipv6hint", "dohpath", "ohttp")
        fun key(name: String): Int = names.indexOf(name.replace('_', '-')).takeIf { it >= 0 }
            ?: name.removePrefix("key").toIntOrNull()?.takeIf { name.startsWith("key") && it in 0..65535 }
            ?: throw UnsupportedOperationException("Unsupported JSON SvcParam $name")
        fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply(write)
            return out.toByteArray()
        }
        val params = presentation.params.map { param ->
            val id = key(param.name)
            val raw = when (val value = param.value) {
                is SvcbValue.Bytes -> value.bytes
                is SvcbValue.Number -> bytes { writeShort(value.number) }
                is SvcbValue.Text -> value.text.toByteArray(Charsets.UTF_8)
                SvcbValue.Flag -> byteArrayOf()
                is SvcbValue.Strings -> bytes {
                    for (item in value.items) when (id) {
                        0 -> writeShort(key(item))
                        1 -> {
                            val encoded = item.toByteArray(Charsets.UTF_8)
                            require(encoded.size in 1..255); writeByte(encoded.size); write(encoded)
                        }
                        4, 6 -> {
                            val literal = NativeDnsWire.literalBytes(item)
                            require(literal != null && literal.size == if (id == 4) 4 else 16)
                            write(literal)
                        }
                        else -> error("Unsupported service vector")
                    }
                }
            }
            SvcbCodec.decodeSvcParam(id, raw)
        }.sortedBy { it.key }
        require(params.map { it.key }.distinct().size == params.size) { "Duplicate JSON service keys" }
        return SvcbRecord(presentation.priority, presentation.target, params)
    }

    private fun text(value: Any?): String = value as? String ?: error("Expected DNS JSON string")
    private fun integer(value: Any?, field: String): Long = (value as? Long) ?: error("Expected DNS integer $field")

    private class Json(bytes: ByteArray) {
        private val text: String
        private var pos = 0
        private var nodes = 0
        init {
            require(bytes.size <= 65535)
            text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        fun read(): Any? {
            val result = value(0)
            whitespace()
            require(pos == text.length) { "Trailing JSON" }
            return result
        }
        private fun whitespace() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
        private fun value(depth: Int): Any? {
            require(depth <= 16 && ++nodes <= 8192) { "JSON depth/node limit" }
            whitespace()
            require(pos < text.length)
            return when (text[pos]) {
                '{' -> {
                    pos++; whitespace()
                    val map = linkedMapOf<String, Any?>()
                    if (take('}')) map else {
                        do {
                            whitespace(); val key = string(); whitespace()
                            require(take(':') && !map.containsKey(key)) { "Duplicate/invalid JSON key" }
                            map[key] = value(depth + 1); whitespace()
                        } while (take(','))
                        require(take('}')); map
                    }
                }
                '[' -> {
                    pos++; whitespace()
                    val list = mutableListOf<Any?>()
                    if (take(']')) list else {
                        do { list.add(value(depth + 1)); whitespace() } while (take(','))
                        require(take(']')); list
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = pos
                    if (text[pos] == '-') pos++
                    require(pos < text.length && text[pos] in '0'..'9')
                    if (text[pos] == '0') pos++ else while (pos < text.length && text[pos] in '0'..'9') pos++
                    // Unknown JSON fields may contain fractional/exponent numbers;
                    // retain these as Double, never as DNS integers.
                    if (pos < text.length && text[pos] == '.') {
                        pos++; val digits = pos
                        while (pos < text.length && text[pos] in '0'..'9') pos++
                        require(pos > digits)
                    }
                    if (pos < text.length && text[pos] in "eE") {
                        pos++; if (pos < text.length && text[pos] in "+-") pos++
                        val digits = pos
                        while (pos < text.length && text[pos] in '0'..'9') pos++
                        require(pos > digits)
                    }
                    val number = text.substring(start, pos)
                    number.toLongOrNull() ?: number.toDouble().also { require(it.isFinite()) }
                }
            }
        }
        private fun literal(token: String, result: Any?): Any? {
            require(text.startsWith(token, pos)); pos += token.length; return result
        }
        private fun take(char: Char): Boolean =
            if (pos < text.length && text[pos] == char) { pos++; true } else false
        private fun string(): String {
            require(take('"'))
            val out = StringBuilder()
            while (pos < text.length) {
                val char = text[pos++]
                if (char == '"') return out.toString()
                require(char >= ' ')
                if (char != '\\') out.append(char) else {
                    require(pos < text.length)
                    out.append(when (val escaped = text[pos++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(pos + 4 <= text.length)
                            val code = text.substring(pos, pos + 4).toInt(16); pos += 4; code.toChar()
                        }
                        else -> error("Invalid JSON escape")
                    })
                }
            }
            error("Unterminated JSON string")
        }
    }
}
