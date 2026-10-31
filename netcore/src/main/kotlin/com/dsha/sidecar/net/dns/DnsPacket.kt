package com.dsha.sidecar.net.dns

import com.dsha.sidecar.net.util.ByteReader
import com.dsha.sidecar.net.util.ByteWriter

/**
 * 极简 DNS 报文编解码。只覆盖加速场景需要的能力：
 * A / AAAA 查询、EDNS0、应答名称压缩指针、CNAME 链跟随。
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41

    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    private const val FLAG_RD = 0x0100
    private const val FLAG_TC = 0x0200

    data class Question(val name: String, val type: Int)

    data class Record(val name: String, val type: Int, val ttl: Int, val rdataOffset: Int, val rdlen: Int)

    class Response(
        val id: Int,
        val rcode: Int,
        val truncated: Boolean,
        val questions: List<Question>,
        val answers: List<Record>,
        val raw: ByteArray,
    ) {
        /**
         * 返回问题名对应的一组地址（跟随 CNAME 链），每项为 (ip, ttl)。
         * [wantType] 传 [TYPE_A] 或 [TYPE_AAAA]。
         */
        fun addresses(wantType: Int): List<Pair<String, Int>> {
            val owner = questions.firstOrNull()?.name ?: return emptyList()
            val cname = HashMap<String, String>()
            val aRecords = HashMap<String, MutableList<Pair<String, Int>>>()
            for (r in answers) {
                when (r.type) {
                    TYPE_CNAME -> nameAt(r.rdataOffset)?.let { cname[r.name] = it }
                    TYPE_A -> if (r.rdlen == 4 && wantType == TYPE_A) {
                        val o = r.rdataOffset
                        val ip = "${raw[o].toInt() and 0xFF}.${raw[o + 1].toInt() and 0xFF}." +
                            "${raw[o + 2].toInt() and 0xFF}.${raw[o + 3].toInt() and 0xFF}"
                        aRecords.getOrPut(r.name) { ArrayList() }.add(ip to r.ttl)
                    }
                    TYPE_AAAA -> if (r.rdlen == 16 && wantType == TYPE_AAAA) {
                        aRecords.getOrPut(r.name) { ArrayList() }.add(nameOfIpv6(r.rdataOffset) to r.ttl)
                    }
                }
            }
            val out = ArrayList<Pair<String, Int>>()
            var cur: String? = owner
            var hops = 0
            while (cur != null && hops++ < 8) {
                aRecords[cur]?.let { out.addAll(it) }
                cur = cname[cur]
            }
            return out
        }

        private fun nameOfIpv6(offset: Int): String {
            val sb = StringBuilder()
            for (i in 0 until 16 step 2) {
                if (i > 0) sb.append(':')
                val group = ((raw[offset + i].toInt() and 0xFF) shl 8) or (raw[offset + i + 1].toInt() and 0xFF)
                sb.append(Integer.toHexString(group))
            }
            return sb.toString()
        }

        private fun nameAt(offset: Int): String? = try {
            readName(ByteReader(raw, offset), raw).lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /** 构造查询报文；[edns] 时追加 OPT，服务端可返回大于 512 字节的应答。 */
    fun buildQuery(name: String, type: Int, id: Int, edns: Boolean = true): ByteArray {
        val w = ByteWriter(64)
        w.u16(id and 0xFFFF)
            .u16(FLAG_RD)
            .u16(1)
            .u16(0)
            .u16(0)
            .u16(if (edns) 1 else 0)
        writeName(w, name)
        w.u16(type).u16(1)
        if (edns) {
            w.u8(0).u16(TYPE_OPT).u16(1232).u8(0).u8(0).u16(0).u16(0)
        }
        return w.toByteArray()
    }

    fun writeName(w: ByteWriter, name: String) {
        val clean = name.trimEnd('.')
        if (clean.isEmpty()) {
            w.u8(0)
            return
        }
        var total = 0
        for (label in clean.split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "DNS 标签长度非法: $label" }
            total += bytes.size + 1
            require(total <= 253) { "域名过长: $name" }
            w.u8(bytes.size).bytes(bytes)
        }
        w.u8(0)
    }

    fun parse(buf: ByteArray): Response {
        val r = ByteReader(buf)
        val id = r.u16()
        val flags = r.u16()
        val qd = r.u16()
        val an = r.u16()
        r.u16()
        r.u16()
        val questions = ArrayList<Question>(qd)
        repeat(qd) {
            val name = readName(r, buf)
            val type = r.u16()
            r.u16()
            questions.add(Question(name.lowercase(), type))
        }
        val answers = ArrayList<Record>(an)
        repeat(an) {
            val name = readName(r, buf)
            val type = r.u16()
            r.u16()
            val ttl = (r.u16() shl 16) or r.u16()
            val rdlen = r.u16()
            val offset = r.pos
            r.skip(rdlen)
            answers.add(Record(name.lowercase(), type, ttl, offset, rdlen))
        }
        return Response(
            id = id,
            rcode = flags and 0x0F,
            truncated = flags and FLAG_TC != 0,
            questions = questions,
            answers = answers,
            raw = buf,
        )
    }

    /** 读取名称，支持 0xC0 压缩指针（指针相对整报文）。 */
    private fun readName(r: ByteReader, buf: ByteArray): String {
        val sb = StringBuilder()
        var follow: Int? = null
        var guard = 0
        while (true) {
            if (guard++ > 128) throw IllegalStateException("DNS 名称指针成环")
            val len = r.u8()
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or r.u8()
                if (follow == null) follow = ptr
                break
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(r.bytes(len), Charsets.UTF_8))
        }
        follow?.let { ptr ->
            val sub = readName(ByteReader(buf, ptr), buf)
            if (sb.isNotEmpty() && sub.isNotEmpty()) sb.append('.').append(sub)
            else if (sub.isNotEmpty()) sb.append(sub)
        }
        return sb.toString()
    }
}
