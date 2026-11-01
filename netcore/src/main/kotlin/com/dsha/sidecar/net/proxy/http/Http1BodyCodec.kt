package com.dsha.sidecar.net.proxy.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class Http1ChunkMode { PRESERVE, DECODE }

data class Http1BodyResult(val bodyBytes: Long, val trailers: Http1Headers)

object Http1BodyCodec {
    /**
     * Streams one body without closing or flushing either stream. PRESERVE retains chunk framing;
     * DECODE writes only chunk data and returns trailers separately.
     */
    fun transfer(
        input: InputStream,
        output: OutputStream,
        framing: Http1BodyFraming,
        limits: Http1Limits = Http1Limits(),
        mode: Http1ChunkMode = Http1ChunkMode.PRESERVE,
    ): Http1BodyResult {
        val buffer = ByteArray(limits.bufferBytes)
        val empty = Http1Headers(emptyList())
        return when (framing) {
            Http1BodyFraming.None -> Http1BodyResult(0, empty)
            Http1BodyFraming.Handoff -> throw IllegalArgumentException("Handoff is not a body")
            is Http1BodyFraming.Fixed -> {
                if (framing.length < 0 || framing.length > limits.maxBodyBytes) {
                    throw Http1Exception("Invalid body length")
                }
                copyExactly(input, output, framing.length, buffer)
                Http1BodyResult(framing.length, empty)
            }
            Http1BodyFraming.CloseDelimited ->
                Http1BodyResult(copyToEof(input, output, limits.maxBodyBytes, buffer), empty)
            Http1BodyFraming.Chunked -> transferChunked(input, output, limits, mode, buffer)
        }
    }

    /** Encodes a finite, EOF-terminated source as chunked, with separate trailer fields. */
    fun writeChunked(
        input: InputStream,
        output: OutputStream,
        trailers: Http1Headers = Http1Headers(emptyList()),
        limits: Http1Limits = Http1Limits(),
    ): Http1BodyResult {
        val trailerBytes = serializeTrailers(trailers, limits)
        val buffer = ByteArray(limits.bufferBytes)
        var total = 0L
        while (true) {
            val count = readSome(input, buffer, buffer.size)
            if (count < 0) break
            if (count.toLong() > limits.maxBodyBytes - total) throw Http1Exception("Body exceeds byte limit")
            writeLine(output, count.toString(16), limits.maxLineBytes)
            output.write(buffer, 0, count)
            output.write(CRLF)
            total += count
        }
        writeLine(output, "0", limits.maxLineBytes)
        output.write(trailerBytes)
        return Http1BodyResult(total, trailers)
    }

    private fun transferChunked(
        input: InputStream,
        output: OutputStream,
        limits: Http1Limits,
        mode: Http1ChunkMode,
        buffer: ByteArray,
    ): Http1BodyResult {
        var total = 0L
        while (true) {
            val line = readLine(input, limits.maxLineBytes, ByteBudget(limits.maxLineBytes + 2))
                ?: throw Http1Exception("Unexpected EOF before chunk")
            val size = parseChunkSize(line)
            if (size > limits.maxBodyBytes - total) throw Http1Exception("Body exceeds byte limit")
            if (mode == Http1ChunkMode.PRESERVE) writeLine(output, line, limits.maxLineBytes)
            if (size == 0L) {
                val trailers = readFields(input, limits, ByteBudget(limits.maxTrailerBytes), limits.maxTrailerCount)
                val trailerBytes = serializeTrailers(trailers, limits)
                if (mode == Http1ChunkMode.PRESERVE) output.write(trailerBytes)
                return Http1BodyResult(total, trailers)
            }
            copyExactly(input, output, size, buffer)
            if (input.read() != 13 || input.read() != 10) throw Http1Exception("Invalid chunk terminator")
            if (mode == Http1ChunkMode.PRESERVE) output.write(CRLF)
            total += size
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long, buffer: ByteArray) {
        var remaining = length
        while (remaining > 0) {
            val count = readSome(input, buffer, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) throw Http1Exception("Unexpected EOF in body")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun copyToEof(input: InputStream, output: OutputStream, max: Long, buffer: ByteArray): Long {
        var total = 0L
        while (true) {
            val remaining = max - total
            val wanted = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
            val count = readSome(input, buffer, wanted)
            if (count < 0) return total
            if (count.toLong() > max - total) throw Http1Exception("Body exceeds byte limit")
            output.write(buffer, 0, count)
            total += count
        }
    }

    private fun readSome(input: InputStream, buffer: ByteArray, length: Int): Int {
        val count = input.read(buffer, 0, length)
        if (count != 0) return count
        val byte = input.read()
        if (byte < 0) return -1
        buffer[0] = byte.toByte()
        return 1
    }
}

private fun parseChunkSize(line: String): Long {
    var index = 0
    var size = 0L
    while (index < line.length) {
        val digit = when (val c = line[index]) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> break
        }
        if (size > (Long.MAX_VALUE - digit) / 16) throw Http1Exception("Chunk size overflow")
        size = size * 16 + digit
        index++
    }
    if (index == 0) throw Http1Exception("Invalid chunk size")
    fun skipWhitespace() {
        while (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
    }
    fun token() {
        val start = index
        while (index < line.length && isToken(line[index].toString())) index++
        if (index == start) throw Http1Exception("Invalid chunk extension")
    }
    while (index < line.length) {
        skipWhitespace()
        if (index >= line.length || line[index++] != ';') throw Http1Exception("Invalid chunk extension")
        skipWhitespace()
        token()
        val afterToken = index
        skipWhitespace()
        if (index < line.length && line[index] == '=') {
            index++
            skipWhitespace()
            if (index < line.length && line[index] == '"') {
                index++
                var closed = false
                while (index < line.length) {
                    val c = line[index++]
                    if (c == '"') {
                        closed = true
                        break
                    }
                    if (c == '\\') {
                        if (index == line.length || !validFieldValue(line[index].toString())) {
                            throw Http1Exception("Invalid chunk extension escape")
                        }
                        index++
                    } else if (!validFieldValue(c.toString()) || c == '\\') {
                        throw Http1Exception("Invalid chunk extension value")
                    }
                }
                if (!closed) throw Http1Exception("Unterminated chunk extension")
            } else {
                token()
            }
        } else {
            index = afterToken
        }
    }
    return size
}

private val forbiddenTrailers = setOf(
    "content-length", "transfer-encoding", "host", "connection", "trailer", "upgrade", "proxy-connection",
)

internal fun serializeTrailers(trailers: Http1Headers, limits: Http1Limits): ByteArray {
    if (trailers.fields.size > limits.maxTrailerCount) throw Http1Exception("Too many trailer fields")
    val output = ByteArrayOutputStream()
    for (field in trailers.fields) {
        validateField(field)
        if (forbiddenTrailers.any { field.name.equals(it, true) }) throw Http1Exception("Forbidden trailer field")
        writeLine(output, field.name + ":" + field.rawValue, limits.maxLineBytes)
        if (output.size() > limits.maxTrailerBytes) throw Http1Exception("Trailers exceed byte limit")
    }
    output.write(CRLF)
    if (output.size() > limits.maxTrailerBytes) throw Http1Exception("Trailers exceed byte limit")
    return output.toByteArray()
}
