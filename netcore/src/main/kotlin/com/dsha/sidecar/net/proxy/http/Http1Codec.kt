package com.dsha.sidecar.net.proxy.http

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class ByteBudget(private val maximum: Int) {
    private var used = 0

    fun take() {
        if (used == maximum) throw Http1Exception("Section exceeds byte limit")
        used++
    }
}

/** Reads a CRLF-terminated line, returning null only for a clean EOF before any byte. */
internal fun readLine(input: InputStream, maxLine: Int, budget: ByteBudget): String? {
    val bytes = ByteArrayOutputStream(minOf(maxLine, 256))
    while (true) {
        val b = input.read()
        if (b < 0) {
            if (bytes.size() == 0) return null
            throw Http1Exception("Unexpected EOF in line")
        }
        budget.take()
        if (b == 13) {
            val lf = input.read()
            if (lf < 0) throw Http1Exception("Unexpected EOF after CR")
            budget.take()
            if (lf != 10) throw Http1Exception("Line must end in CRLF")
            return bytes.toString(Charsets.ISO_8859_1.name())
        }
        if (b == 10) throw Http1Exception("Bare LF in line")
        if (bytes.size() >= maxLine) throw Http1Exception("Line exceeds byte limit")
        bytes.write(b)
    }
}

internal fun readFields(
    input: InputStream,
    limits: Http1Limits,
    budget: ByteBudget,
    countLimit: Int,
): Http1Headers {
    val fields = ArrayList<Http1Header>()
    while (true) {
        val line = readLine(input, limits.maxLineBytes, budget)
            ?: throw Http1Exception("Unexpected EOF in fields")
        if (line.isEmpty()) return Http1Headers(fields)
        if (fields.size >= countLimit) throw Http1Exception("Too many fields")
        val colon = line.indexOf(':')
        if (colon <= 0) throw Http1Exception("Invalid field line")
        val field = Http1Header(line.substring(0, colon), line.substring(colon + 1))
        validateField(field)
        fields.add(field)
    }
}

internal fun validateField(field: Http1Header) {
    if (!isToken(field.name) || !validFieldValue(field.rawValue)) {
        throw Http1Exception("Invalid header field")
    }
}

/** Does not wrap or close streams: reuse the same caller-owned input for heads, bodies and handoff. */
object Http1HeadCodec {
    fun readRequest(input: InputStream, limits: Http1Limits = Http1Limits()): Http1Request? {
        val budget = ByteBudget(limits.maxHeadBytes)
        val line = readLine(input, limits.maxLineBytes, budget) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3 || !isToken(parts[0])) throw Http1Exception("Invalid request line")
        validateVersion(parts[2])
        val head = Http1RequestHead(parts[0], parseTarget(parts[0], parts[1]), parts[2],
            readFields(input, limits, budget, limits.maxHeaderCount))
        return Http1Request(head, Http1Framing.request(head, limits))
    }

    fun readResponse(
        input: InputStream,
        requestMethod: String,
        limits: Http1Limits = Http1Limits(),
    ): Http1Response? {
        if (!isToken(requestMethod)) throw Http1Exception("Invalid request method")
        val budget = ByteBudget(limits.maxHeadBytes)
        val line = readLine(input, limits.maxLineBytes, budget) ?: return null
        if (line.length < 13 || line[8] != ' ' || line[12] != ' ') {
            throw Http1Exception("Invalid status line")
        }
        val version = line.substring(0, 8)
        validateVersion(version)
        val statusText = line.substring(9, 12)
        if (statusText.any { it !in '0'..'9' }) throw Http1Exception("Invalid status code")
        val status = statusText.toInt()
        if (status !in 100..599) throw Http1Exception("Invalid status code")
        val reason = line.substring(13)
        if (!validFieldValue(reason)) throw Http1Exception("Invalid reason phrase")
        val head = Http1ResponseHead(version, status, reason,
            readFields(input, limits, budget, limits.maxHeaderCount))
        return Http1Response(head, Http1Framing.response(head, requestMethod, limits))
    }

    fun writeRequest(output: OutputStream, head: Http1RequestHead, limits: Http1Limits = Http1Limits()) {
        if (!isToken(head.method)) throw Http1Exception("Invalid request method")
        validateVersion(head.version)
        if (parseTarget(head.method, head.target.raw) != head.target) throw Http1Exception("Inconsistent target")
        Http1Framing.request(head, limits)
        writeHead(output, "${head.method} ${head.target.raw} ${head.version}", head.headers, limits)
    }

    fun writeResponse(
        output: OutputStream,
        head: Http1ResponseHead,
        requestMethod: String,
        limits: Http1Limits = Http1Limits(),
    ) {
        validateVersion(head.version)
        if (head.status !in 100..599 || !validFieldValue(head.reason)) {
            throw Http1Exception("Invalid status line")
        }
        Http1Framing.response(head, requestMethod, limits)
        writeHead(output, "${head.version} ${head.status} ${head.reason}", head.headers, limits)
    }

    private fun writeHead(output: OutputStream, line: String, headers: Http1Headers, limits: Http1Limits) {
        val buffer = ByteArrayOutputStream()
        writeLine(buffer, line, limits.maxLineBytes)
        if (headers.fields.size > limits.maxHeaderCount) throw Http1Exception("Too many fields")
        for (field in headers.fields) {
            validateField(field)
            writeLine(buffer, field.name + ":" + field.rawValue, limits.maxLineBytes)
            if (buffer.size() > limits.maxHeadBytes) throw Http1Exception("Head exceeds byte limit")
        }
        buffer.write(CRLF)
        if (buffer.size() > limits.maxHeadBytes) throw Http1Exception("Head exceeds byte limit")
        buffer.writeTo(output)
    }
}

internal val CRLF = byteArrayOf(13, 10)

internal fun writeLine(output: OutputStream, line: String, maxLine: Int) {
    if (line.length > maxLine || line.any { it.code > 255 || it == '\r' || it == '\n' }) {
        throw Http1Exception("Invalid or oversized line")
    }
    output.write(line.toByteArray(Charsets.ISO_8859_1))
    output.write(CRLF)
}

private fun validateVersion(version: String) {
    if (version != "HTTP/1.0" && version != "HTTP/1.1") throw Http1Exception("Unsupported HTTP version")
}

object Http1Framing {
    fun request(head: Http1RequestHead, limits: Http1Limits = Http1Limits()): Http1BodyFraming {
        val framing = fromHeaders(head.version, head.headers)
        return checkLength(framing ?: Http1BodyFraming.None, limits)
    }

    fun response(
        head: Http1ResponseHead,
        requestMethod: String,
        limits: Http1Limits = Http1Limits(),
    ): Http1BodyFraming {
        if (!isToken(requestMethod)) throw Http1Exception("Invalid request method")
        val framing = fromHeaders(head.version, head.headers)
        if (head.status == 101 || (requestMethod == "CONNECT" && head.status in 200..299)) {
            return Http1BodyFraming.Handoff
        }
        if (requestMethod == "HEAD" || head.status in 100..199 || head.status == 204 || head.status == 304) {
            return Http1BodyFraming.None
        }
        return checkLength(framing ?: Http1BodyFraming.CloseDelimited, limits)
    }

    private fun fromHeaders(version: String, headers: Http1Headers): Http1BodyFraming? {
        val lengths = headers.values("Content-Length")
        val encodings = headers.values("Transfer-Encoding")
        if (lengths.isNotEmpty() && encodings.isNotEmpty()) throw Http1Exception("Transfer-Encoding with Content-Length")
        if (lengths.size > 1) throw Http1Exception("Duplicate Content-Length")
        if (lengths.isNotEmpty()) {
            val value = lengths.single()
            if (value.isEmpty() || value.any { it !in '0'..'9' }) throw Http1Exception("Invalid Content-Length")
            val length = value.toLongOrNull() ?: throw Http1Exception("Content-Length overflow")
            return Http1BodyFraming.Fixed(length)
        }
        if (encodings.isNotEmpty()) {
            if (version != "HTTP/1.1") throw Http1Exception("Transfer-Encoding requires HTTP/1.1")
            val tokens = headers.tokens("Transfer-Encoding")
            if (tokens.size != 1 || !tokens[0].equals("chunked", true)) {
                throw Http1Exception("Unsupported Transfer-Encoding")
            }
            return Http1BodyFraming.Chunked
        }
        return null
    }

    private fun checkLength(framing: Http1BodyFraming, limits: Http1Limits): Http1BodyFraming {
        if (framing is Http1BodyFraming.Fixed && framing.length > limits.maxBodyBytes) {
            throw Http1Exception("Body exceeds byte limit")
        }
        return framing
    }
}

/** One reader owns one input stream; all buffered bytes remain in this reader until handoff. */
class Http1Reader(
    input: InputStream,
    private val limits: Http1Limits = Http1Limits(),
    bufferInput: Boolean = true,
) {
    private val input: InputStream = if (bufferInput) BufferedInputStream(input, limits.bufferBytes) else input
    private var pending: Http1BodyFraming? = null
    private var unavailable = false

    fun readRequest(): Http1Request? = guarded {
        requireReady()
        Http1HeadCodec.readRequest(input, limits)?.also { pending = it.framing }
    }

    fun readResponse(requestMethod: String): Http1Response? = guarded {
        requireReady()
        Http1HeadCodec.readResponse(input, requestMethod, limits)?.also { pending = it.framing }
    }

    fun transferBody(output: OutputStream, mode: Http1ChunkMode = Http1ChunkMode.PRESERVE): Http1BodyResult =
        guarded {
            check(!unavailable) { "Reader is unavailable" }
            val framing = pending ?: throw IllegalStateException("No pending message")
            if (framing == Http1BodyFraming.Handoff) throw IllegalStateException("Use handoff for this response")
            val result = Http1BodyCodec.transfer(input, output, framing, limits, mode)
            pending = null
            if (framing == Http1BodyFraming.CloseDelimited) unavailable = true
            result
        }

    /** Transfers ownership, including any bytes already buffered by this reader. */
    fun handoff(): InputStream {
        check(!unavailable) { "Reader is unavailable" }
        check(pending == null || pending == Http1BodyFraming.Handoff) { "Message body is pending" }
        unavailable = true
        pending = null
        return input
    }

    private fun requireReady() {
        check(!unavailable) { "Reader is unavailable" }
        check(pending == null) { "Consume the previous body first" }
    }

    private inline fun <T> guarded(block: () -> T): T {
        try {
            return block()
        } catch (e: java.io.IOException) {
            unavailable = true
            throw e
        }
    }
}

object Http1Persistence {
    fun isPersistent(version: String, headers: Http1Headers): Boolean {
        validateVersion(version)
        val tokens = headers.tokens("Connection")
        if (tokens.any { it.equals("close", true) }) return false
        return version == "HTTP/1.1" || tokens.any { it.equals("keep-alive", true) }
    }

    fun canReuse(request: Http1Request, response: Http1Response): Boolean =
        response.head.status !in 100..199 &&
            response.framing != Http1BodyFraming.CloseDelimited &&
            response.framing != Http1BodyFraming.Handoff &&
            isPersistent(request.head.version, request.head.headers) &&
            isPersistent(response.head.version, response.head.headers)
}
