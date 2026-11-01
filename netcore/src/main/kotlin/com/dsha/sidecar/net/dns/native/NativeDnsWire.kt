// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.SvcbCodec
import com.dsha.sidecar.net.dns.SvcbResourceRecord
import com.dsha.sidecar.net.dns.SvcbSection
import java.io.IOException
import java.util.Locale

class NativeDnsProtocolException(message: String) : IOException(message)

/** Strict transaction validation around the unchanged, bounded SVCB structural codec. */
object NativeDnsWire {
    const val A = 1
    const val AAAA = 28

    data class Question(val id: Int, val hostname: String, val type: Int, val bytes: ByteArray)
    data class Reply(
        val answer: NativeDnsAnswer,
        val truncated: Boolean = false,
        val followUp: String? = null,
        val aliasTtlMillis: Long = Long.MAX_VALUE,
        val aliases: List<String> = emptyList(),
    )

    fun question(hostname: String, type: Int, id: Int, udpPayloadSize: Int = 1232): Question {
        require(type == A || type == AAAA) { "Only A/AAAA questions are supported" }
        val host = name(hostname)
        return Question(id, host, type, SvcbCodec.encodeQuery(host, id, type, udpPayloadSize))
    }

    fun parse(
        bytes: ByteArray,
        expected: Question,
        maxRecords: Int = 128,
        maxAddresses: Int = 32,
        maxAliases: Int = 8,
    ): Reply {
        try {
            return decode(bytes, expected, maxRecords, maxAddresses, maxAliases)
        } catch (error: IllegalArgumentException) {
            throw NativeDnsProtocolException(error.message ?: "Malformed DNS message")
        }
    }

    private fun decode(
        bytes: ByteArray,
        expected: Question,
        maxRecords: Int,
        maxAddresses: Int,
        maxAliases: Int,
    ): Reply {
        check(bytes.size in 12..65535, "Invalid DNS message length")
        check(u16(bytes, 0) == expected.id, "DNS transaction ID mismatch")
        val flags = u16(bytes, 2)
        check(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0040 == 0,
            "Invalid DNS response flags")
        check(u16(bytes, 4) == 1, "Expected exactly one DNS question")
        val decodedQuestion = SvcbCodec.decodeName(bytes, 12)
        val pos = decodedQuestion.offset
        need(bytes, pos, 4)
        check(name(decodedQuestion.name) == expected.hostname &&
            u16(bytes, pos) == expected.type && u16(bytes, pos + 2) == 1, "DNS question mismatch")
        // A truncated UDP response can end partway through an RR. Validate its
        // transaction/question before allowing TCP fallback; do not trust its RDATA.
        if (flags and 0x0200 != 0) {
            check(flags and 15 == 0, "Truncated DNS error response")
            return Reply(NativeDnsAnswer(), truncated = true)
        }
        check((6..10 step 2).sumOf { u16(bytes, it) } <= maxRecords, "Too many DNS records")
        val response = SvcbCodec.parseResponse(bytes)
        check(response.rcode == 0 || response.rcode == 3, "DNS error RCODE ${response.rcode}")
        for (record in response.answers) {
            if (record.name.isNotEmpty()) name(record.name)
            if (record.typeCode == A) check(record.raw.size == 4, "Invalid A RDATA length")
            if (record.typeCode == AAAA) check(record.raw.size == 16, "Invalid AAAA RDATA length")
            if (record.typeCode == SvcbCodec.TYPE_OPT) {
                check(record.ttl ushr 24 == 0L, "Unsupported extended DNS RCODE")
            }
        }
        val answers = response.answers.filter { it.section == SvcbSection.ANSWER && it.klass == 1 }
        val cnames = linkedMapOf<String, SvcbResourceRecord>()
        for (record in answers.filter { it.typeCode == SvcbCodec.TYPE_CNAME }) {
            val owner = name(record.name)
            val previous = cnames.put(owner, record)
            check(previous == null || name(previous.cname!!) == name(record.cname!!),
                "Conflicting CNAME targets")
            if (previous != null) cnames[owner] = record.copy(ttl = minOf(previous.ttl, record.ttl))
            check(answers.none { name(it.name) == owner && it.typeCode in listOf(A, AAAA) },
                "CNAME owner also has address records")
        }
        var owner = expected.hostname
        var ttl = Long.MAX_VALUE
        val visited = linkedSetOf(owner)
        val aliases = mutableListOf<String>()
        while (true) {
            val cname = cnames[owner] ?: break
            check(aliases.size < maxAliases, "CNAME chain exceeds limit")
            ttl = minOf(ttl, cname.ttl * 1000)
            owner = name(requireNotNull(cname.cname))
            check(visited.add(owner), "CNAME cycle")
            aliases.add(owner)
        }
        val addresses = linkedMapOf<String, Long>()
        for (record in answers.filter { it.typeCode == expected.type && name(it.name) == owner }) {
            val address = if (expected.type == A) {
                record.raw.joinToString(".") { (it.toInt() and 255).toString() }
            } else {
                (0..7).joinToString(":") { u16(record.raw, it * 2).toString(16) }
            }
            val lifetime = minOf(ttl, record.ttl * 1000)
            addresses[address] = minOf(addresses[address] ?: Long.MAX_VALUE, lifetime)
            check(addresses.size <= maxAddresses, "Too many DNS addresses")
        }
        val negative = negativeTtl(bytes, owner)
        if (response.rcode == 3) {
            check(addresses.isEmpty(), "NXDOMAIN contains requested address records")
            return Reply(NativeDnsAnswer(code = NativeDnsAnswerCode.NXDOMAIN,
                negativeTtlMillis = minOf(ttl, negative.second)), aliases = aliases)
        }
        val records = addresses.map { (address, lifetime) -> NativeDnsRecord(address, lifetime) }
        val follow = owner.takeIf { aliases.isNotEmpty() && records.isEmpty() && !negative.first }
        return Reply(
            NativeDnsAnswer(records, if (records.isEmpty()) NativeDnsAnswerCode.NODATA else NativeDnsAnswerCode.ANSWER,
                if (records.isEmpty()) minOf(ttl, negative.second) else 0),
            followUp = follow, aliasTtlMillis = ttl, aliases = aliases,
        )
    }

    /** Only authority SOAs for this name's enclosing zone authorize negative TTLs. */
    private fun negativeTtl(bytes: ByteArray, hostname: String): Pair<Boolean, Long> {
        var pos = SvcbCodec.decodeName(bytes, 12).offset + 4
        val an = u16(bytes, 6)
        val ns = u16(bytes, 8)
        val total = an + ns + u16(bytes, 10)
        var found = false
        var minimum = Long.MAX_VALUE
        repeat(total) { index ->
            val owner = SvcbCodec.decodeName(bytes, pos)
            pos = owner.offset
            val type = u16(bytes, pos)
            val klass = u16(bytes, pos + 2)
            val ttl = u32(bytes, pos + 4)
            val size = u16(bytes, pos + 8)
            pos += 10
            val end = pos + size
            if (type == 6) {
                val primary = SvcbCodec.decodeName(bytes, pos, end)
                val mailbox = SvcbCodec.decodeName(bytes, primary.offset, end)
                check(mailbox.offset + 20 == end, "Invalid SOA RDATA length")
                val zone = if (owner.name.isEmpty()) "" else name(owner.name)
                if (index in an until an + ns && klass == 1 &&
                    (zone.isEmpty() || hostname == zone || hostname.endsWith(".$zone"))
                ) {
                    minimum = minOf(minimum, ttl, u32(bytes, mailbox.offset + 16))
                    found = true
                }
            }
            pos = end
        }
        return found to if (found) minimum * 1000 else 0L
    }

    internal fun name(input: String): String {
        val normalized = input.removeSuffix(".").lowercase(Locale.ROOT)
        require(normalized.length in 1..253 && normalized.split('.').all { label ->
            label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        }) { "Unsupported DNS name" }
        return normalized
    }

    internal fun literalBytes(input: String): ByteArray? {
        if ('%' in input) return null
        IpAddress.parseIpv4(input)?.let { octets -> return octets.map { it.toByte() }.toByteArray() }
        return IpAddress.parseIpv6(input)?.let { groups ->
            ByteArray(16).apply {
                groups.forEachIndexed { index, value ->
                    this[index * 2] = (value ushr 8).toByte()
                    this[index * 2 + 1] = value.toByte()
                }
            }
        }
    }

    private fun need(bytes: ByteArray, pos: Int, size: Int) =
        check(pos >= 0 && size >= 0 && pos <= bytes.size - size, "Truncated DNS byte range")

    private fun u16(bytes: ByteArray, pos: Int): Int {
        need(bytes, pos, 2)
        return ((bytes[pos].toInt() and 255) shl 8) or (bytes[pos + 1].toInt() and 255)
    }

    private fun u32(bytes: ByteArray, pos: Int): Long =
        (u16(bytes, pos).toLong() shl 16) or u16(bytes, pos + 2).toLong()

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw NativeDnsProtocolException(message)
    }
}
