package com.dsha.sidecar.net.util

/** 大端读写辅助；越界统一抛 [IndexOutOfBoundsException]，由调用方按"报文非法"处理。 */
class ByteReader(private val buf: ByteArray, var pos: Int = 0, private val limit: Int = buf.size) {

    val remaining: Int get() = limit - pos

    fun u8(): Int = buf[pos++].toInt() and 0xFF

    fun u16(): Int {
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u24(): Int {
        val v = ((buf[pos].toInt() and 0xFF) shl 16) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            (buf[pos + 2].toInt() and 0xFF)
        pos += 3
        return v
    }

    fun bytes(n: Int): ByteArray {
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        pos += n
    }

    fun peekU16(at: Int): Int = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)
}

class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size * 2
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int) = apply {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) = apply {
        ensure(2)
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun u24(v: Int) = apply {
        ensure(3)
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) = apply {
        ensure(len)
        b.copyInto(buf, size, off, off + len)
        size += len
    }

    /** 回填已写入位置的 16 位值（用于长度字段）。 */
    fun patchU16(at: Int, v: Int) = apply {
        buf[at] = (v ushr 8).toByte()
        buf[at + 1] = v.toByte()
    }

    fun patchU24(at: Int, v: Int) = apply {
        buf[at] = (v ushr 16).toByte()
        buf[at + 1] = (v ushr 8).toByte()
        buf[at + 2] = v.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
