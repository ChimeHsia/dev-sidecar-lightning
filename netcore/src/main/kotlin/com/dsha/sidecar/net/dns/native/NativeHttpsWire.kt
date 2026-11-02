// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.SvcbCodec
import com.dsha.sidecar.net.dns.SvcbSection

/** Service-record validation kept separate so accepted A/AAAA wire behavior stays frozen. */
object NativeHttpsWire {
    data class Reply(
        val answer: HttpsRecordAnswer,
        val truncated: Boolean = false,
        val followUp: String? = null,
        val aliasTtlMillis: Long = Long.MAX_VALUE,
    )

    fun question(hostname: String, type: HttpsRecordType, id: Int, udpPayloadSize: Int = 1232): NativeDnsWire.Question {
        val host = NativeDnsWire.name(hostname)
        return NativeDnsWire.Question(id, host, type.code, SvcbCodec.encodeQuery(host, id, type.code, udpPayloadSize))
    }

    fun parse(
        bytes: ByteArray,
        expected: NativeDnsWire.Question,
        maxRecords: Int = 128,
        maxServices: Int = 32,
        maxAliases: Int = 8,
    ): Reply {
        require(expected.type in listOf(64, 65))
        try {
            check(bytes.size in 12..65535, "Invalid service DNS length")
            check(u16(bytes, 0) == expected.id, "Service DNS ID mismatch")
            val flags = u16(bytes, 2)
            check(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0040 == 0,
                "Invalid service DNS flags")
            check(u16(bytes, 4) == 1, "Expected one service DNS question")
            val question = SvcbCodec.decodeName(bytes, 12)
            check(NativeDnsWire.name(question.name) == expected.hostname &&
                u16(bytes, question.offset) == expected.type && u16(bytes, question.offset + 2) == 1,
                "Service DNS question mismatch")
            if (flags and 0x0200 != 0) {
                check(flags and 15 == 0, "Truncated service DNS error")
                return Reply(HttpsRecordAnswer(), truncated = true)
            }
            check((6..10 step 2).sumOf { u16(bytes, it) } <= maxRecords, "Too many service DNS records")
            val response = SvcbCodec.parseResponse(bytes)
            check(response.rcode == 0 || response.rcode == 3, "Service DNS RCODE ${response.rcode}")
            response.answers.forEach {
                if (it.name.isNotEmpty()) NativeDnsWire.name(it.name)
                if (it.typeCode == 41) check(it.ttl ushr 24 == 0L, "Extended service DNS error")
                if (it.service != null) {
                    val keys = it.service.params.map { param -> param.key }
                    check(keys.zipWithNext().all { (a, b) -> a < b }, "Service parameter keys must increase")
                }
            }
            val answers = response.answers.filter { it.klass == 1 && it.section == SvcbSection.ANSWER }
            var owner = expected.hostname
            var ttl = Long.MAX_VALUE
            val aliases = mutableListOf<String>()
            val visited = mutableSetOf(owner)
            while (true) {
                val cname = answers.filter { it.name.equals(owner, true) && it.typeCode == 5 }
                if (cname.isEmpty()) break
                check(answers.none { it.name.equals(owner, true) && it.service != null },
                    "Service owner has both CNAME and service records")
                val targets = cname.map { NativeDnsWire.name(requireNotNull(it.cname)) }.distinct()
                check(targets.size == 1, "Conflicting service CNAMEs")
                check(aliases.size < maxAliases, "Service CNAME limit")
                owner = targets.single()
                check(visited.add(owner), "Service CNAME cycle")
                aliases.add(owner)
                ttl = minOf(ttl, cname.minOf { it.ttl * 1000 })
            }
            val services = answers.filter { it.name.equals(owner, true) && it.typeCode == expected.type }
            check(services.size <= maxServices, "Too many service answers")
            val records = services.map {
                HttpsServiceRecord(owner, minOf(ttl, it.ttl * 1000), requireNotNull(it.service))
            }
            val negative = negativeTtl(bytes, owner)
            check(response.rcode != 3 || records.isEmpty(), "NXDOMAIN has service records")
            val code = when {
                response.rcode == 3 -> NativeDnsAnswerCode.NXDOMAIN
                records.isEmpty() -> NativeDnsAnswerCode.NODATA
                else -> NativeDnsAnswerCode.ANSWER
            }
            return Reply(
                HttpsRecordAnswer(records, code, if (records.isEmpty()) minOf(ttl, negative.second) else 0, aliases),
                followUp = owner.takeIf { aliases.isNotEmpty() && records.isEmpty() && response.rcode == 0 && !negative.first },
                aliasTtlMillis = ttl,
            )
        } catch (error: IllegalArgumentException) {
            throw NativeDnsProtocolException(error.message ?: "Malformed service DNS")
        }
    }

    private fun negativeTtl(bytes: ByteArray, host: String): Pair<Boolean, Long> {
        var pos = SvcbCodec.decodeName(bytes, 12).offset + 4
        val an = u16(bytes, 6)
        val ns = u16(bytes, 8)
        var minimum = Long.MAX_VALUE
        repeat(an + ns + u16(bytes, 10)) { index ->
            val owner = SvcbCodec.decodeName(bytes, pos)
            pos = owner.offset
            val type = u16(bytes, pos)
            val klass = u16(bytes, pos + 2)
            val ttl = (u16(bytes, pos + 4).toLong() shl 16) or u16(bytes, pos + 6).toLong()
            val size = u16(bytes, pos + 8)
            pos += 10
            val end = pos + size
            if (type == 6) {
                val primary = SvcbCodec.decodeName(bytes, pos, end)
                val mailbox = SvcbCodec.decodeName(bytes, primary.offset, end)
                check(mailbox.offset + 20 == end, "Invalid service SOA")
                if (index in an until an + ns && klass == 1 &&
                    (owner.name.isEmpty() || host.equals(owner.name, true) || host.endsWith(".${owner.name}", true))
                ) {
                    val soaMinimum = (u16(bytes, mailbox.offset + 16).toLong() shl 16) or
                        u16(bytes, mailbox.offset + 18).toLong()
                    minimum = minOf(minimum, ttl * 1000, soaMinimum * 1000)
                }
            }
            pos = end
        }
        return (minimum != Long.MAX_VALUE) to if (minimum == Long.MAX_VALUE) 0L else minimum
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        check(offset >= 0 && offset <= bytes.size - 2, "Truncated service DNS range")
        return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    }

    private fun check(ok: Boolean, message: String) {
        if (!ok) throw NativeDnsProtocolException(message)
    }
}
