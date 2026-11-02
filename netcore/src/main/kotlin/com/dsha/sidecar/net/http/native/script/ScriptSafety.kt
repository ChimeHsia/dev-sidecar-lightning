// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.util.Collections
import java.security.MessageDigest
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.RuleRequest

/** Exact URL/path allowlist. No network access or broad scheme/origin trust is implied. */
class ScriptReferencePolicy(allowedReferences: Set<String> = emptySet()) {
    private val allowed = Collections.unmodifiableSet(allowedReferences.toSet())
    init {
        require(allowed.size <= 1024)
        allowed.forEach { ScriptSafety.reference(it) }
    }
    internal fun authorize(value: String) {
        ScriptSafety.reference(value)
        if (value !in allowed) throw ScriptInjectionException(ScriptProblem.UNAUTHORIZED_REFERENCE)
    }
}

internal object ScriptSafety {
    fun key(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
        value !in setOf("__proto__", "constructor", "prototype")

    fun reference(value: String) {
        if (value.length !in 1..4096 || value.any { it.code !in 33..126 || it in "\"'<>`\\&" } ||
            Regex("%(?:2e|2f|5c|00|0[ad]|22|27|3c|3e)", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
            throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
        }
        val uri = try { URI(value) } catch (_: Exception) {
            throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
        }
        if (uri.rawFragment != null || uri.rawUserInfo != null || uri.rawPath.orEmpty().split('/').any { it == "." || it == ".." }) {
            throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
        }
        if (value.startsWith("/")) {
            if (value.startsWith("//") || uri.rawAuthority != null || uri.scheme != null) {
                throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
            }
        } else if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.port !in listOf(-1, 443)) {
            throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
        }
    }

    fun utf8(value: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
    }

    fun validateHead(head: ActionResponseHead) {
        if (head.status !in 200..599 || head.reason.length > 1024 ||
            head.reason.any { it.code !in 32..255 || it.code == 127 } || head.headers.size > 256 ||
            head.headers.sumOf { it.name.length.toLong() + it.value.length + 4 } > 65_536 ||
            head.headers.any { (name, value) ->
                !name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) ||
                    value.any { it == '\r' || it == '\n' || it.code > 255 || it.code == 127 || it.code < 32 && it != '\t' }
            }) throw ScriptInjectionException(ScriptProblem.INVALID_HEADERS)
        val length = values(head.headers, "content-length")
        val transfer = values(head.headers, "transfer-encoding")
        if (length.size > 1 || length.any { !it.matches(Regex("[0-9]+")) || it.toLongOrNull() == null } ||
            transfer.isNotEmpty() && (length.isNotEmpty() || transfer.size != 1 || !transfer.single().equals("chunked", true)) ||
            values(head.headers, "trailer").isNotEmpty() || values(head.headers, "content-encoding").size > 1 ||
            values(head.headers, "content-type").size > 1) {
            throw ScriptInjectionException(ScriptProblem.INVALID_HEADERS)
        }
    }

    fun values(headers: List<ActionHeader>, name: String): List<String> =
        headers.filter { it.name.equals(name, true) }.map { it.value }

    fun first(headers: List<ActionHeader>, name: String): String? = values(headers, name).firstOrNull()
    fun set(headers: MutableList<ActionHeader>, name: String, value: String) {
        headers.removeAll { it.name.equals(name, true) }
        headers.add(ActionHeader(name, value))
    }
    fun snapshot(head: ActionResponseHead): ActionResponseHead =
        head.copy(headers = Collections.unmodifiableList(head.headers.toList()))

    fun binding(request: RuleRequest, head: ActionResponseHead): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val values = listOf(request.host, request.path, request.method, request.scheme, request.port.toString(),
            request.headers["turbo-frame"]?.toString().orEmpty(), head.status.toString(), head.reason) +
            head.headers.flatMap { listOf(it.name, it.value) }
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
