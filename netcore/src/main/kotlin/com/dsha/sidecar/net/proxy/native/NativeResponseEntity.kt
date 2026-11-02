package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.Http1BodyFraming
import com.dsha.sidecar.net.proxy.http.Http1ChunkMode
import com.dsha.sidecar.net.proxy.http.Http1Exception
import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.http.Http1ResponseHead
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Synchronous, single-use access to exactly one dechunked entity; no socket or downstream sink. */
fun interface NativeResponseEntity {
    fun read(maximumBytes: Int): ByteArray
}

class NativePreparedResponse(val head: Http1ResponseHead, body: ByteArray) {
    init { require(body.size <= 16_777_216) { "Prepared response exceeds 16 MiB" } }
    internal val body: ByteArray = body.copyOf()
}

internal class NativeResponseEntityReader(
    private val reader: Http1Reader,
    private val framing: Http1BodyFraming,
    private val maximumBodyBytes: Long,
) : NativeResponseEntity {
    var consumed = false
        private set
    var complete = false
        private set

    override fun read(maximumBytes: Int): ByteArray {
        check(!consumed) { "Response entity already consumed" }
        require(maximumBytes in 1..16_777_216) { "Entity buffer must be bounded to 16 MiB" }
        val maximum = minOf(maximumBytes.toLong(), maximumBodyBytes)
        if (framing is Http1BodyFraming.Fixed && framing.length > maximum) {
            throw Http1Exception("Response entity exceeds preparation quota")
        }
        consumed = true
        val bytes = ByteArrayOutputStream(minOf(maximum, 8192).toInt())
        val result = reader.transferBody(object : OutputStream() {
            override fun write(value: Int) {
                if (bytes.size() >= maximum) throw Http1Exception("Response entity exceeds preparation quota")
                bytes.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (bytes.size().toLong() + length > maximum) {
                    throw Http1Exception("Response entity exceeds preparation quota")
                }
                bytes.write(buffer, offset, length)
            }
        }, Http1ChunkMode.DECODE)
        if (result.trailers.fields.isNotEmpty()) throw Http1Exception("Entity preparation does not support trailers")
        complete = true
        return bytes.toByteArray()
    }
}
