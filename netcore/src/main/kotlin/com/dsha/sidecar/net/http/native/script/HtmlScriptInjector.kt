// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import org.brotli.dec.BrotliInputStream
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.RuleRequest

/**
 * Bounded spooling over exactly one framed/dechunked entity. No raw live socket, unbounded buffering,
 * caller-stream closure, or sink access. PassThrough always leaves the entity unread.
 */
class HtmlScriptInjector(private val limits: ScriptLimits = ScriptLimits()) {
    fun prepare(
        request: RuleRequest,
        upstream: ActionResponseHead,
        tags: ScriptTagDecision,
        entity: InputStream,
        cancelled: () -> Boolean = { false },
    ): ScriptResponseDecision {
        var consumed = false
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw ScriptInjectionException(ScriptProblem.CANCELLED)
        }
        try {
            ScriptSafety.validateHead(upstream)
            checkCancelled()
            when (tags) {
                is ScriptTagDecision.Blocked -> return ScriptResponseDecision.Blocked(tags.problem)
                is ScriptTagDecision.Skipped -> {
                    val headers = upstream.headers.toMutableList()
                    tags.diagnosticHeaders.forEach { ScriptSafety.set(headers, it.name, it.value) }
                    return ScriptResponseDecision.PassThrough(ScriptSafety.snapshot(upstream.copy(headers = headers)), tags.reason)
                }
                is ScriptTagDecision.Ready -> Unit
            }
            if (ScriptSafety.binding(request, upstream) != tags.binding) throw ScriptInjectionException(ScriptProblem.INVALID_HEADERS)
            if (request.method == "HEAD" || upstream.status in listOf(204, 205, 304)) {
                return ScriptResponseDecision.PassThrough(ScriptSafety.snapshot(upstream), ScriptSkip.NO_ENTITY)
            }
            if (ScriptSafety.first(upstream.headers, "content-length") == "0") {
                return ScriptResponseDecision.PassThrough(ScriptSafety.snapshot(upstream), ScriptSkip.EMPTY_ENTITY)
            }
            if (upstream.status == 206 || ScriptSafety.values(upstream.headers, "content-range").isNotEmpty() ||
                ScriptSafety.values(upstream.headers, "cache-control").any { it.split(',').any { value -> value.trim().equals("no-transform", true) } }) {
                throw ScriptInjectionException(ScriptProblem.CONTENT_POLICY)
            }
            val type = ScriptSafety.first(upstream.headers, "content-type").orEmpty()
            if (!type.contains("text/html")) throw ScriptInjectionException(ScriptProblem.INVALID_HEADERS)
            val charsets = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .findAll(type).map { it.groupValues[1].lowercase(Locale.ROOT) }.toList()
            if (charsets.size > 1 || charsets.any { it !in listOf("utf-8", "utf8", "us-ascii") } ||
                charsets.firstOrNull() == "us-ascii" && tags.headText.any { it.code > 127 }) {
                throw ScriptInjectionException(ScriptProblem.UNSUPPORTED_CHARSET)
            }
            val encoding = ScriptSafety.first(upstream.headers, "content-encoding")?.lowercase(Locale.ROOT)
            if (encoding !in listOf(null, "", "identity", "gzip", "deflate", "br")) {
                throw ScriptInjectionException(ScriptProblem.UNSUPPORTED_ENCODING)
            }
            val expected = ScriptSafety.first(upstream.headers, "content-length")?.toLong()
            if (expected != null && expected > limits.maximumEncodedBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            val append = ScriptSafety.utf8(tags.headText)
            if (append.size > limits.maximumTagBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            consumed = true
            val encoded = read(entity, limits.maximumEncodedBytes, ::checkCancelled)
            if (expected != null && encoded.size.toLong() != expected) throw ScriptInjectionException(ScriptProblem.MALFORMED_BODY)
            val plain = when (encoding) {
                "gzip" -> GZIPInputStream(ByteArrayInputStream(encoded)).use { read(it, limits.maximumDecodedBytes, ::checkCancelled) }
                "deflate" -> InflaterInputStream(ByteArrayInputStream(encoded)).use { read(it, limits.maximumDecodedBytes, ::checkCancelled) }
                "br" -> BrotliInputStream(ByteArrayInputStream(encoded)).use { read(it, limits.maximumDecodedBytes, ::checkCancelled) }
                else -> {
                    if (encoded.size > limits.maximumDecodedBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
                    encoded
                }
            }
            checkCancelled()
            // Byte insertion is not safe for UTF-16/32, including documents with a contradictory BOM.
            if (plain.size >= 2 && ((plain[0].toInt() and 255) == 255 && (plain[1].toInt() and 255) == 254 ||
                    (plain[0].toInt() and 255) == 254 && (plain[1].toInt() and 255) == 255) ||
                plain.size >= 4 && plain[0] == 0.toByte() && plain[1] == 0.toByte() && plain[2] == 254.toByte() && plain[3] == 255.toByte()) {
                throw ScriptInjectionException(ScriptProblem.UNSUPPORTED_CHARSET)
            }
            val index = HtmlHeadBoundary.find(plain).takeIf { it >= 0 }
                ?: throw ScriptInjectionException(ScriptProblem.MISSING_HEAD)
            val inserted = LimitedOutput(limits.maximumDecodedBytes)
            inserted.write(plain, 0, index)
            inserted.write(append)
            inserted.write(plain, index, plain.size - index)
            val output = LimitedOutput(limits.maximumOutputBytes)
            val body = inserted.bytes()
            when (encoding) {
                "gzip" -> GZIPOutputStream(output).use { writeChecked(it, body, ::checkCancelled) }
                "deflate" -> DeflaterOutputStream(output).use { writeChecked(it, body, ::checkCancelled) }
                else -> writeChecked(output, body, ::checkCancelled)
            }
            checkCancelled()
            val headers = upstream.headers.toMutableList()
            val nominated = ScriptSafety.values(headers, "connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
            if (nominated.any { it in listOf("content-encoding", "content-type", "content-security-policy") }) {
                throw ScriptInjectionException(ScriptProblem.INVALID_HEADERS)
            }
            val remove = setOf(
                "content-length", "transfer-encoding", "connection", "keep-alive", "proxy-connection", "upgrade", "te", "trailer",
                "http2-settings", "etag", "last-modified", "content-md5", "digest", "content-digest", "repr-digest", "accept-ranges",
            ) + nominated
            headers.removeAll { it.name.lowercase(Locale.ROOT) in remove }
            // Brotli decoding is pure Java; send the modified representation with identity encoding.
            if (encoding == "br") headers.removeAll { it.name.equals("content-encoding", true) }
            if (tags.repairCsp) {
                ScriptSafety.first(headers, "content-security-policy")?.let { ScriptSafety.set(headers, "content-security-policy", patchCsp(it)) }
            }
            tags.diagnosticHeaders.forEach { ScriptSafety.set(headers, it.name, it.value) }
            ScriptSafety.set(headers, "Content-Length", output.size.toString())
            val head = upstream.copy(headers = headers)
            ScriptSafety.validateHead(head)
            return ScriptResponseDecision.Prepared(ScriptResponse(ScriptSafety.snapshot(head), output.bytes()))
        } catch (failure: ScriptInjectionException) {
            return ScriptResponseDecision.Blocked(failure.problem, consumed)
        } catch (_: java.io.IOException) {
            return ScriptResponseDecision.Blocked(ScriptProblem.MALFORMED_BODY, consumed)
        }
    }

    private fun read(input: InputStream, maximum: Int, check: () -> Unit): ByteArray {
        val output = LimitedOutput(maximum)
        val buffer = ByteArray(8192)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                check()
                val byte = input.read()
                if (byte < 0) break
                output.write(byte)
            } else output.write(buffer, 0, count)
        }
        return output.bytes()
    }

    private fun writeChecked(output: OutputStream, body: ByteArray, check: () -> Unit) {
        var offset = 0
        while (offset < body.size) {
            check()
            val count = minOf(8192, body.size - offset)
            output.write(body, offset, count)
            offset += count
        }
    }

    private fun patchCsp(policy: String): String {
        val has = Regex("script-src(-elem)?\\s+", RegexOption.IGNORE_CASE).containsMatchIn(policy)
        val result = Regex("script-src(-elem)?\\s+([^;]*)", RegexOption.IGNORE_CASE).replace(policy) { match ->
            val values = match.groupValues[2].trim()
            if (values.contains("'self'")) match.value else "script-src${match.groupValues[1]} 'self' $values"
        }
        return if (has) result else "script-src 'self'; $result"
    }

    private class LimitedOutput(private val maximum: Int) : OutputStream() {
        private val output = ByteArrayOutputStream(minOf(maximum, 8192))
        val size: Int get() = output.size()
        override fun write(value: Int) {
            if (output.size() >= maximum) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            output.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (output.size().toLong() + length > maximum) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            output.write(bytes, offset, length)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }
}
