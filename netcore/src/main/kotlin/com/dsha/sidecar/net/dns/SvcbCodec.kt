// SPDX-License-Identifier: MPL-2.0
// Based on @blue-frontier/mitmproxy 2.3.1 src/lib/dns/util.svcb.js; see docs/migration-wave3/svcb.md.
package com.dsha.sidecar.net.dns

/**
 * Bounded DNS HTTPS/SVCB codec using only common Kotlin. No resolver, randomness or ECH parsing.
 * Input errors throw SvcbFormatException; decoding never returns a partially parsed record.
 * This is a structural codec, not an RFC service-selection or DNS authenticity validator.
 */
object SvcbCodec {
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65
    const val TYPE_CNAME = 5
    const val TYPE_OPT = 41
    const val MAX_PACKET_BYTES = 65535
    const val MAX_QUESTIONS = 256
    const val MAX_RECORDS = 2048
    const val MAX_PARAMS = 1024
    const val MAX_POINTER_HOPS = 128
    const val MAX_TEXT_LENGTH = 65535
    const val MAX_TOKENS = 2048

    private val paramNames = listOf(
        "mandatory", "alpn", "no_default_alpn", "port", "ipv4hint", "ech", "ipv6hint", "dohpath", "ohttp",
    )

    fun typeName(type: Int): String = when (type) {
        TYPE_SVCB -> "SVCB"
        TYPE_HTTPS -> "HTTPS"
        TYPE_CNAME -> "CNAME"
        TYPE_OPT -> "OPT"
        else -> type.toString()
    }

    fun isSvcType(type: Int): Boolean = type == TYPE_SVCB || type == TYPE_HTTPS
    fun isSvcType(type: String): Boolean = type == "SVCB" || type == "HTTPS"
    fun typeCode(type: Int): Int = if (type == TYPE_SVCB) TYPE_SVCB else TYPE_HTTPS
    fun typeCode(type: String): Int = if (type == "SVCB") TYPE_SVCB else TYPE_HTTPS
    fun paramName(key: Int): String = paramNames.getOrNull(key) ?: "key$key"

    /** UTF-8 labels; like upstream, empty labels are skipped. No IDNA or zone-file escaping. */
    fun encodeName(name: String): ByteArray {
        check(name.length <= MAX_TEXT_LENGTH, "name text too long")
        val labels = name.removeSuffix(".").split('.').filter { it.isNotEmpty() }.map { it.encodeToByteArray() }
        check(labels.all { it.size <= 63 }, "DNS label exceeds 63 bytes")
        val size = labels.sumOf { it.size + 1 } + 1
        check(size <= 255, "expanded DNS name exceeds 255 bytes")
        val out = ByteArray(size)
        var pos = 0
        for (label in labels) {
            out[pos++] = label.size.toByte()
            label.copyInto(out, pos)
            pos += label.size
        }
        return out
    }

    /** Explicit unsigned 16-bit ID: caller supplies entropy when it is needed. */
    fun encodeQuery(
        name: String,
        id: Int,
        type: Int = TYPE_HTTPS,
        udpPayloadSize: Int = 1232,
        dnssecOk: Boolean = false,
    ): ByteArray {
        u16Range(id, "query ID")
        u16Range(type, "query type")
        u16Range(udpPayloadSize, "UDP payload size")
        val encoded = encodeName(name)
        val out = ByteArray(12 + encoded.size + 4 + 11)
        put16(out, 0, id)
        put16(out, 2, 0x0100)
        put16(out, 4, 1)
        put16(out, 10, 1)
        encoded.copyInto(out, 12)
        var pos = 12 + encoded.size
        put16(out, pos, type)
        put16(out, pos + 2, 1)
        pos += 4
        put16(out, pos + 1, TYPE_OPT)
        put16(out, pos + 3, udpPayloadSize)
        put16(out, pos + 7, if (dnssecOk) 0x8000 else 0)
        return out
    }

    /**
     * endOffset bounds the bytes consumed at the original location (e.g. RDATA).
     * A compression pointer may follow bytes anywhere in the packet. offset is the
     * byte after the original name/pointer, never the end of the pointed-to name.
     */
    fun decodeName(wire: ByteArray, offset: Int, endOffset: Int = wire.size): SvcbDecodedName {
        packetSize(wire)
        check(endOffset in 0..wire.size && offset in 0 until endOffset, "invalid name boundary", offset)
        var pos = offset
        var next = -1
        var limit = endOffset
        var hops = 0
        var expanded = 1
        val visited = mutableSetOf<Int>()
        val labels = mutableListOf<ByteArray>()
        while (true) {
            need(wire, pos, 1, limit)
            val length = u8(wire, pos)
            if (length == 0) {
                if (next == -1) next = pos + 1
                break
            }
            if (length and 0xC0 == 0xC0) {
                need(wire, pos, 2, limit)
                check(visited.add(pos) && ++hops <= MAX_POINTER_HOPS, "compression cycle or hop limit", pos)
                val pointer = ((length and 63) shl 8) or u8(wire, pos + 1)
                check(pointer < wire.size, "compression pointer outside packet", pos)
                if (next == -1) next = pos + 2
                pos = pointer
                limit = wire.size
                continue
            }
            check(length and 0xC0 == 0, "unsupported DNS label encoding", pos)
            need(wire, pos + 1, length, limit)
            expanded += length + 1
            check(expanded <= 255, "expanded DNS name exceeds 255 bytes", pos)
            labels.add(wire.copyOfRange(pos + 1, pos + 1 + length))
            pos += length + 1
        }
        return SvcbDecodedName(labels.joinToString(".") { it.decodeToString() }, next, labels)
    }

    /** Keeps a copy of every value, including unknown keys, flags and ECH. */
    fun decodeSvcParam(key: Int, bytes: ByteArray): SvcbParam {
        u16Range(key, "parameter key")
        check(bytes.size <= MAX_PACKET_BYTES, "parameter too large")
        val value = when (key) {
            0 -> {
                check(bytes.size % 2 == 0, "partial mandatory key")
                SvcbValue.Strings((bytes.indices step 2).map { paramName(u16(bytes, it)) })
            }
            1 -> {
                val items = mutableListOf<String>()
                var pos = 0
                while (pos < bytes.size) {
                    val size = u8(bytes, pos++)
                    check(size > 0, "empty ALPN ID", pos - 1)
                    need(bytes, pos, size)
                    items.add(bytes.decodeToString(pos, pos + size))
                    pos += size
                }
                SvcbValue.Strings(items)
            }
            2, 8 -> {
                check(bytes.isEmpty(), "flag parameter must be empty")
                SvcbValue.Flag
            }
            3 -> {
                check(bytes.size == 2, "port must contain two bytes")
                SvcbValue.Number(u16(bytes, 0))
            }
            4 -> {
                check(bytes.size % 4 == 0, "partial IPv4 hint")
                SvcbValue.Strings((bytes.indices step 4).map { pos ->
                    (0..3).joinToString(".") { u8(bytes, pos + it).toString() }
                })
            }
            6 -> {
                check(bytes.size % 16 == 0, "partial IPv6 hint")
                SvcbValue.Strings((bytes.indices step 16).map { pos ->
                    (0..7).joinToString(":") { u16(bytes, pos + it * 2).toString(16) }
                })
            }
            7 -> SvcbValue.Text(bytes.decodeToString())
            else -> SvcbValue.Bytes(bytes.copyOf())
        }
        return SvcbParam(key, paramName(key), bytes.copyOf(), value)
    }

    fun decodeSvcRecord(wire: ByteArray, offset: Int, length: Int): SvcbRecord {
        packetSize(wire)
        need(wire, offset, length)
        check(length >= 3, "service RDATA too short", offset)
        val end = offset + length
        val priority = u16(wire, offset)
        val target = decodeName(wire, offset + 2, end)
        var pos = target.offset
        val params = mutableListOf<SvcbParam>()
        while (pos < end) {
            check(params.size < MAX_PARAMS, "too many service parameters", pos)
            need(wire, pos, 4, end)
            val key = u16(wire, pos)
            val size = u16(wire, pos + 2)
            pos += 4
            need(wire, pos, size, end)
            params.add(decodeSvcParam(key, wire.copyOfRange(pos, pos + size)))
            pos += size
        }
        return SvcbRecord(priority, target.name.ifEmpty { "." }, params)
    }

    /** Header bits/IDs are returned verbatim; no QR, expected-ID or DNSSEC validation. */
    fun parseResponse(wire: ByteArray): SvcbResponse {
        packetSize(wire)
        need(wire, 0, 12)
        val qd = u16(wire, 4)
        val an = u16(wire, 6)
        val ns = u16(wire, 8)
        val ar = u16(wire, 10)
        val total = an + ns + ar
        check(qd <= MAX_QUESTIONS, "too many questions", 4)
        check(total <= MAX_RECORDS, "too many resource records", 6)
        check(qd * 5 + total * 11 <= wire.size - 12, "counts cannot fit packet", 4)
        var pos = 12
        val questions = mutableListOf<SvcbQuestion>()
        repeat(qd) {
            val name = decodeName(wire, pos)
            pos = name.offset
            need(wire, pos, 4)
            questions.add(SvcbQuestion(name.name, u16(wire, pos), u16(wire, pos + 2)))
            pos += 4
        }
        val records = mutableListOf<SvcbResourceRecord>()
        repeat(total) { index ->
            val name = decodeName(wire, pos)
            pos = name.offset
            need(wire, pos, 10)
            val type = u16(wire, pos)
            val klass = u16(wire, pos + 2)
            val ttl = (u16(wire, pos + 4).toLong() shl 16) or u16(wire, pos + 6).toLong()
            val size = u16(wire, pos + 8)
            pos += 10
            need(wire, pos, size)
            val section = when {
                index < an -> SvcbSection.ANSWER
                index < an + ns -> SvcbSection.AUTHORITY
                else -> SvcbSection.ADDITIONAL
            }
            val service = if (isSvcType(type)) decodeSvcRecord(wire, pos, size) else null
            val cname = if (type == TYPE_CNAME) {
                val decoded = decodeName(wire, pos, pos + size)
                check(decoded.offset == pos + size, "trailing CNAME data", decoded.offset)
                decoded.name
            } else null
            records.add(
                SvcbResourceRecord(
                    name.name, type, klass, ttl, section, wire.copyOfRange(pos, pos + size), service, cname,
                ),
            )
            pos += size
        }
        check(pos == wire.size, "trailing DNS packet data", pos)
        return SvcbResponse(u16(wire, 0), u16(wire, 2), questions, records, wire.copyOf())
    }

    /** Upstream's quote/backslash grammar, with explicit rejection of unfinished quoted tokens. */
    fun tokenize(text: String): List<String> {
        check(text.length <= MAX_TEXT_LENGTH, "presentation text too long")
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var hasToken = false
        var pos = 0
        fun flush() {
            if (hasToken || current.isNotEmpty()) {
                check(tokens.size < MAX_TOKENS, "too many presentation tokens", pos)
                tokens.add(current.toString())
                current.clear()
                hasToken = false
            }
        }
        while (pos < text.length) {
            val char = text[pos++]
            when {
                char == '\\' && quoted -> {
                    check(pos < text.length, "unfinished quoted escape", pos - 1)
                    current.append(text[pos++])
                }
                char == '"' -> {
                    quoted = !quoted
                    hasToken = true
                }
                !quoted && isTextSpace(char) -> flush()
                else -> current.append(char)
            }
        }
        check(!quoted, "unfinished quoted token", pos)
        flush()
        return tokens
    }

    /** Small DoH JSON presentation grammar; not a general DNS master-file parser. */
    fun parsePresentation(text: String): SvcbPresentation {
        val tokens = tokenize(text)
        check(tokens.size >= 2, "presentation requires priority and target")
        val priority = decimal16(tokens[0], "priority")
        val target = tokens[1]
        check(target.isNotEmpty(), "empty presentation target")
        encodeName(target)
        check(tokens.size - 2 <= MAX_PARAMS, "too many presentation parameters")
        val params = tokens.drop(2).map { token ->
            val split = token.indexOf('=')
            val name = if (split < 0) token else token.substring(0, split)
            val textValue = if (split < 0) null else token.substring(split + 1)
            check(name.isNotEmpty(), "empty presentation parameter name")
            val key = paramNames.indexOf(name.replace('-', '_'))
            // Keep upstream's unknown-name key=-1, including "keyNNN"; wire decoding retains numeric keys.
            val value = when {
                textValue == null -> {
                    check(key == 2 || key == 8 || key == -1, "parameter requires a value")
                    SvcbValue.Flag
                }
                name == "ech" -> SvcbValue.Bytes(decodeBase64(textValue))
                name in listOf("alpn", "ipv4hint", "ipv6hint", "mandatory") -> SvcbValue.Strings(textValue.split(','))
                name == "port" -> SvcbValue.Number(decimal16(textValue, "port"))
                key == 2 || key == 8 -> throw SvcbFormatException("flag parameter cannot have a value")
                else -> SvcbValue.Text(textValue)
            }
            SvcbPresentationParam(key, name, value)
        }
        return SvcbPresentation(priority, target, params)
    }

    private fun decodeBase64(text: String): ByteArray {
        // Standard alphabet, canonical padding or no padding. No Buffer-style ignored garbage.
        val firstPadding = text.indexOf('=').let { if (it < 0) text.length else it }
        val padding = text.length - firstPadding
        check(padding <= 2 && text.substring(firstPadding).all { it == '=' }, "invalid base64 padding")
        check(firstPadding % 4 != 1, "invalid base64 length")
        check(padding == 0 || (text.length % 4 == 0 && padding == (4 - firstPadding % 4) % 4), "invalid base64 padding")
        val out = ByteArray(firstPadding * 6 / 8)
        var acc = 0
        var bits = 0
        var pos = 0
        for (index in 0 until firstPadding) {
            val char = text[index]
            val value = when (char) {
                in 'A'..'Z' -> char.code - 'A'.code
                in 'a'..'z' -> char.code - 'a'.code + 26
                in '0'..'9' -> char.code - '0'.code + 52
                '+' -> 62
                '/' -> 63
                else -> throw SvcbFormatException("invalid base64 character", index)
            }
            acc = (acc shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[pos++] = (acc ushr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        check(acc == 0, "nonzero base64 padding bits")
        return out
    }

    private fun isTextSpace(char: Char): Boolean =
        char in '\t'..'\r' || char == ' ' || char == '\u00A0' || char == '\u1680' ||
            char in '\u2000'..'\u200A' || char == '\u2028' || char == '\u2029' ||
            char == '\u202F' || char == '\u205F' || char == '\u3000' || char == '\uFEFF'

    private fun decimal16(text: String, name: String): Int {
        check(text.isNotEmpty() && text.length <= 5 && text.all { it in '0'..'9' }, "invalid $name")
        val value = text.toInt()
        u16Range(value, name)
        return value
    }

    private fun packetSize(wire: ByteArray) = check(wire.size <= MAX_PACKET_BYTES, "DNS packet too large")
    private fun u16Range(value: Int, name: String) = check(value in 0..65535, "$name outside unsigned 16-bit range")
    private fun check(ok: Boolean, message: String, offset: Int? = null) {
        if (!ok) throw SvcbFormatException(message, offset)
    }

    private fun need(bytes: ByteArray, offset: Int, size: Int, end: Int = bytes.size) {
        check(end in 0..bytes.size && offset in 0..end && size >= 0 && size <= end - offset, "truncated byte range", offset)
    }

    private fun u8(bytes: ByteArray, pos: Int): Int = bytes[pos].toInt() and 255
    private fun u16(bytes: ByteArray, pos: Int): Int = (u8(bytes, pos) shl 8) or u8(bytes, pos + 1)
    private fun put16(bytes: ByteArray, pos: Int, value: Int) {
        bytes[pos] = (value ushr 8).toByte()
        bytes[pos + 1] = value.toByte()
    }
}
