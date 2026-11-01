// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.schedule

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import com.dsha.sidecar.net.http.native.ActionHeader

internal object ScheduleSupport {
    fun request(input: ScheduledRequest): ScheduledRequest {
        require(input.method.matches(Regex("[A-Z]+")) && input.scheme in listOf("http", "https") &&
            input.host.isNotEmpty() && input.host.length <= 253 && input.host.matches(Regex("[A-Za-z0-9.:%_-]+")) && input.port in 1..65535 &&
            input.path.startsWith("/") && input.path.length <= 8192 && input.path.none { it <= ' ' || it == '\u007f' })
        require(input.privatePartition == null || input.privatePartition.length in 1..1024)
        validateHeaders(input.headers)
        return input.copy(host = input.host.lowercase(Locale.ROOT), headers = Collections.unmodifiableList(input.headers.toList()))
    }

    fun validateHeaders(headers: List<ActionHeader>) {
        require(headers.size <= 256 && headers.sumOf { it.name.length.toLong() + it.value.length + 4 } <= 65_536)
        require(headers.all { (name, value) ->
            name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                value.none { it == '\r' || it == '\n' || it.code > 255 || it.code == 127 || it.code < 32 && it != '\t' }
        }) { "Invalid headers" }
    }

    fun length(headers: List<ActionHeader>): Long? {
        val values = fields(headers, "content-length")
        if (values.isEmpty()) return null
        require(values.size == 1 && values.single().matches(Regex("[0-9]+"))) { "Ambiguous body length" }
        return values.single().toLongOrNull() ?: throw IllegalArgumentException("Invalid body length")
    }

    fun bufferEligible(request: ScheduledRequest, maximum: Int): Boolean {
        require(maximum in 0..1_048_576)
        val input = request(request)
        if (fields(input.headers, "transfer-encoding").isNotEmpty() ||
            fields(input.headers, "expect").any { it.contains("100-continue", true) }) return false
        val length = length(input.headers)
        if (length != null && length > maximum) return false
        return length != null || input.method in listOf("GET", "HEAD", "OPTIONS")
    }

    fun buffer(request: ScheduledRequest, body: InputStream, maximum: Int, cancellation: ScheduleCancellation): ReplayableRequest {
        val input = request(request)
        require(bufferEligible(input, maximum)) { "Request cannot be buffered for replay" }
        val output = ByteArrayOutputStream(minOf(maximum, 8192))
        val bytes = ByteArray(8192)
        while (true) {
            cancellation.throwIfCancelled()
            val count = body.read(bytes)
            if (count < 0) break
            if (count == 0) {
                val byte = body.read()
                if (byte < 0) break
                if (output.size() >= maximum) throw IOException("Replay body limit exceeded")
                output.write(byte)
            } else {
                if (output.size().toLong() + count > maximum) throw IOException("Replay body limit exceeded")
                output.write(bytes, 0, count)
            }
        }
        cancellation.throwIfCancelled()
        val expected = length(input.headers)
        if (expected != null && expected != output.size().toLong()) throw IOException("Replay body length mismatch")
        return BufferedReplay(input, output.toByteArray())
    }

    fun date(millis: Long): String = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))
    fun parseDate(value: String?): Long? = value?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    fun hash(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun set(headers: MutableList<ActionHeader>, name: String, value: String) {
        headers.removeAll { it.name.equals(name, true) }
        headers.add(ActionHeader(name, value))
    }
}
