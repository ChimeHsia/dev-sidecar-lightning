package com.dsha.sidecar.net.proxy

/**
 * TLS ClientHello 解析与 SNI 改写（纯字节操作，不解密、不碰证书）。
 *
 * ⚠️ 重要结论：**实际链路中不要调用 [rewriteSni]**。
 * ClientHello 参与 TLS 握手转录，改动任何一个字节都会让客户端与服务端的 transcript 不一致，
 * TLS 1.3 下必在 Finished 校验时报 tag mismatch（见 TlsThroughProxyTest 的回归用例）。
 * 加速的正确做法是"连更快的 IP，SNI 与证书保持原域名"，即本类只用于诊断/测试。
 *
 * 保留 [rewriteSni] 的意义：验证解析与长度回填逻辑无误，且为将来可能出现的
 * "客户端侧自建连接"场景（由我们发起 TLS，而不是透传）留出入口。
 */
object TlsClientHello {

    private const val RECORD_HANDSHAKE = 0x16
    private const val HANDSHAKE_CLIENT_HELLO = 0x01
    private const val EXT_SERVER_NAME = 0x0000

    private class SniLocation(
        val name: String,
        val nameOffset: Int,
        val nameLenOffset: Int,
        val nameLen: Int,
        val listLenOffset: Int,
        val extLenOffset: Int,
        val extBlockLenOffset: Int,
        val handshakeLenOffset: Int,
        val recordLenOffset: Int,
    )

    fun looksLikeTls(data: ByteArray): Boolean =
        data.isNotEmpty() && (data[0].toInt() and 0xFF) == RECORD_HANDSHAKE

    /** 取 SNI；无法解析时返回 null。 */
    fun sniOf(record: ByteArray): String? = locate(record)?.name

    /**
     * 返回改写后的 record。参数非法或结构不认识时原样返回（[record] 同一实例）。
     */
    fun rewriteSni(record: ByteArray, newSni: String): ByteArray {
        val loc = locate(record) ?: return record
        val newBytes = newSni.lowercase().toByteArray(Charsets.US_ASCII)
        if (newBytes.size > 255) return record
        if (loc.name == newSni.lowercase()) return record
        val delta = newBytes.size - loc.nameLen
        val out = ByteArray(record.size + delta)
        record.copyInto(out, 0, 0, loc.nameOffset)
        newBytes.copyInto(out, loc.nameOffset)
        record.copyInto(out, loc.nameOffset + newBytes.size, loc.nameOffset + loc.nameLen, record.size)

        patchU16(out, loc.nameLenOffset, newBytes.size)
        patchU16(out, loc.listLenOffset, u16(record, loc.listLenOffset) + delta)
        patchU16(out, loc.extLenOffset, u16(record, loc.extLenOffset) + delta)
        patchU16(out, loc.extBlockLenOffset, u16(record, loc.extBlockLenOffset) + delta)
        val hsLen = u24(record, loc.handshakeLenOffset) + delta
        out[loc.handshakeLenOffset] = (hsLen ushr 16).toByte()
        out[loc.handshakeLenOffset + 1] = (hsLen ushr 8).toByte()
        out[loc.handshakeLenOffset + 2] = hsLen.toByte()
        patchU16(out, loc.recordLenOffset, u16(record, loc.recordLenOffset) + delta)
        return out
    }

    private fun locate(record: ByteArray): SniLocation? {
        if (record.size < 45) return null
        if ((record[0].toInt() and 0xFF) != RECORD_HANDSHAKE) return null
        val recordLenOffset = 3
        val recordLen = u16(record, recordLenOffset)
        if (5 + recordLen > record.size) return null          // record 不完整，等下次
        val hsStart = 5
        if ((record[hsStart].toInt() and 0xFF) != HANDSHAKE_CLIENT_HELLO) return null
        val handshakeLenOffset = hsStart + 1
        val hsLen = u24(record, handshakeLenOffset)
        if (hsStart + 4 + hsLen > record.size) return null   // 跨 record，放弃

        var p = hsStart + 4
        p += 2                       // client_version
        p += 32                      // random
        val sidLen = record[p].toInt() and 0xFF
        p += 1 + sidLen
        val csLen = u16(record, p)
        p += 2 + csLen
        val compLen = record[p].toInt() and 0xFF
        p += 1 + compLen
        if (p + 2 > record.size) return null
        val extBlockLenOffset = p
        val extBlockLen = u16(record, p)
        p += 2
        val extEnd = minOf(p + extBlockLen, record.size)

        while (p + 4 <= extEnd) {
            val type = u16(record, p)
            val len = u16(record, p + 2)
            val dataStart = p + 4
            if (dataStart + len > extEnd) return null
            if (type == EXT_SERVER_NAME) {
                var q = dataStart
                if (len < 5) return null
                val listLenOffset = q
                q += 2
                val nameType = record[q].toInt() and 0xFF
                if (nameType != 0) return null   // 只处理 host_name
                q += 1
                val nameLenOffset = q
                val nameLen = u16(record, q)
                q += 2
                if (nameLen == 0 || q + nameLen > dataStart + len) return null
                return SniLocation(
                    name = String(record, q, nameLen, Charsets.US_ASCII).lowercase(),
                    nameOffset = q,
                    nameLenOffset = nameLenOffset,
                    nameLen = nameLen,
                    listLenOffset = listLenOffset,
                    extLenOffset = p + 2,
                    extBlockLenOffset = extBlockLenOffset,
                    handshakeLenOffset = handshakeLenOffset,
                    recordLenOffset = recordLenOffset,
                )
            }
            p = dataStart + len
        }
        return null
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)

    private fun patchU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }
}
