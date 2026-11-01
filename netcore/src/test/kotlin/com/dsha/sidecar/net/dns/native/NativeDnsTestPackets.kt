// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.SvcbCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Independent packet fixtures; deliberately includes malformed and unsolicited data. */
internal object NativeDnsTestPackets {
    data class Rr(val owner: String, val type: Int, val data: ByteArray, val ttl: Long = 60, val klass: Int = 1)
    data class Question(val id: Int, val host: String, val type: Int)

    fun question(bytes: ByteArray): Question {
        val name = SvcbCodec.decodeName(bytes, 12)
        return Question(u16(bytes, 0), name.name, u16(bytes, name.offset))
    }

    fun name(host: String): ByteArray = output {
        if (host.isNotEmpty()) host.removeSuffix(".").split('.').forEach {
            writeByte(it.length)
            write(it.toByteArray(Charsets.US_ASCII))
        }
        writeByte(0)
    }

    fun a(owner: String, ttl: Long = 60, last: Int = 7) =
        Rr(owner, 1, byteArrayOf(192.toByte(), 0, 2, last.toByte()), ttl)
    fun aaaa(owner: String, ttl: Long = 120) =
        Rr(owner, 28, byteArrayOf(0x20, 1, 0x0d, 0xb8.toByte()) + ByteArray(11) + byteArrayOf(9), ttl)
    fun cname(owner: String, target: String, ttl: Long = 10) = Rr(owner, 5, name(target), ttl)
    fun soa(owner: String, ttl: Long = 30, minimum: Long = 20) = Rr(owner, 6, output {
        write(name("ns.$owner"))
        write(name("hostmaster.$owner"))
        writeInt(1)
        writeInt(3600)
        writeInt(600)
        writeInt(86400)
        writeInt(minimum.toInt())
    }, ttl)

    fun response(
        query: ByteArray,
        answers: List<Rr> = emptyList(),
        authority: List<Rr> = emptyList(),
        additional: List<Rr> = emptyList(),
        flags: Int = 0x8180,
        id: Int = question(query).id,
        hostname: String = question(query).host,
        type: Int = question(query).type,
        klass: Int = 1,
    ): ByteArray = output {
        writeShort(id)
        writeShort(flags)
        writeShort(1)
        writeShort(answers.size)
        writeShort(authority.size)
        writeShort(additional.size)
        write(name(hostname))
        writeShort(type)
        writeShort(klass)
        for (rr in answers + authority + additional) {
            write(name(rr.owner))
            writeShort(rr.type)
            writeShort(rr.klass)
            writeInt(rr.ttl.toInt())
            writeShort(rr.data.size)
            write(rr.data)
        }
    }

    fun u16(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun output(write: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply(write)
        return bytes.toByteArray()
    }
}
