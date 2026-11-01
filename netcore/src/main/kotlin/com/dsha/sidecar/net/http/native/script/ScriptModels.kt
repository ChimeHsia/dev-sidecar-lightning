// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead

data class ScriptLimits(
    val maximumCatalogEntries: Int = 64,
    val maximumAssetBytes: Int = 262_144,
    val maximumCatalogBytes: Int = 2_097_152,
    val maximumScriptsPerResponse: Int = 32,
    val maximumTagBytes: Int = 65_536,
    val maximumEncodedBytes: Int = 1_048_576,
    val maximumDecodedBytes: Int = 4_194_304,
    val maximumOutputBytes: Int = 4_194_304,
) {
    init {
        require(maximumCatalogEntries in 1..1024 && maximumScriptsPerResponse in 1..128)
        require(maximumAssetBytes in 1..1_048_576 && maximumCatalogBytes in 1..16_777_216)
        require(maximumTagBytes in 1..262_144)
        require(listOf(maximumEncodedBytes, maximumDecodedBytes, maximumOutputBytes).all { it in 1..16_777_216 })
    }
}

enum class ScriptProblem {
    INVALID_CONFIGURATION, INVALID_REFERENCE, UNAUTHORIZED_REFERENCE, UNSAFE_NONCE, MISSING_ASSET,
    CATALOG_FAILURE, INVALID_HEADERS, UNSUPPORTED_CHARSET, UNSUPPORTED_ENCODING, BODY_LIMIT,
    MALFORMED_BODY, MISSING_HEAD, BLOCKING_CSP, CONTENT_POLICY, CANCELLED,
}

/** Messages intentionally exclude script contents, URLs, nonces, and user-controlled configuration. */
class ScriptInjectionException(val problem: ScriptProblem) : IOException("Script processing blocked: ${problem.name}")

enum class ScriptSkip { DISABLED, GITHUB_TURBO_FRAME, NON_HTML, NO_TAGS, EMPTY_ENTITY, NO_ENTITY }
enum class ScriptNotice { MISSING_OPTIONAL_ASSET, EXISTING_CSP_REQUIRES_REVIEW }

sealed interface ScriptTagDecision {
    data class Skipped(val reason: ScriptSkip, val diagnosticHeaders: List<ActionHeader> = emptyList()) : ScriptTagDecision
    data class Blocked(val problem: ScriptProblem) : ScriptTagDecision
    class Ready internal constructor(
        val headText: String,
        notices: List<ScriptNotice>,
        val diagnosticHeaders: List<ActionHeader>,
        internal val binding: String,
        internal val repairCsp: Boolean,
    ) : ScriptTagDecision {
        val notices: List<ScriptNotice> = Collections.unmodifiableList(notices.toList())
    }
}

enum class CatalogFormat { JAVASCRIPT, USER_SCRIPT }
data class CatalogSource(val text: String, val format: CatalogFormat = CatalogFormat.JAVASCRIPT)

/** Immutable byte asset; serving this data never evaluates it on the JVM. */
class CatalogScript internal constructor(private val bytes: ByteArray) {
    val byteSize: Int get() = bytes.size
    fun bytes(): ByteArray = bytes.clone()
}

fun interface ScriptCatalog {
    /** Key is a validated identifier, never a path/URL. Implementations must be read-only and bounded. */
    fun lookup(key: String): CatalogScript?
}

sealed interface ScriptRouteDecision {
    data object NotHandled : ScriptRouteDecision
    data class Blocked(val problem: ScriptProblem) : ScriptRouteDecision
    data class Served(val response: ScriptResponse) : ScriptRouteDecision
}

/**
 * Prepared entity for engine framing. Writer is deliberately HTTP/1.1 only; protocol mapping remains
 * caller-owned. All body decisions complete before writeHttp1; write I/O itself cannot be atomic.
 */
class ScriptResponse internal constructor(val head: ActionResponseHead, private val body: ByteArray) {
    val bodySize: Int get() = body.size
    fun bodyBytes(): ByteArray = body.clone()
    fun writeBodyTo(sink: OutputStream) { sink.write(body) }
    fun writeHttp1(sink: OutputStream) {
        val text = buildString {
            append("HTTP/1.1 ").append(head.status).append(' ').append(head.reason).append("\r\n")
            head.headers.forEach { append(it.name).append(": ").append(it.value).append("\r\n") }
            append("\r\n")
        }
        sink.write(text.toByteArray(Charsets.ISO_8859_1))
        sink.write(body)
    }
}

sealed interface ScriptResponseDecision {
    /** No entity has been consumed. Engine streams its original body/framing unchanged. */
    data class PassThrough(val head: ActionResponseHead, val reason: ScriptSkip) : ScriptResponseDecision
    /** Once consumed, input cannot be reused for fallback pass-through. No sink has been written. */
    data class Blocked(val problem: ScriptProblem, val entityConsumed: Boolean = false) : ScriptResponseDecision
    data class Prepared(val response: ScriptResponse) : ScriptResponseDecision
}
