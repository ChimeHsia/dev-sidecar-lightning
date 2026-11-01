// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import com.dsha.sidecar.net.rules.native.TlsPolicy

data class ActionLimits(
    val maximumInputBytes: Int = 1_048_576,
    val maximumDecodedBytes: Int = 4_194_304,
    val maximumOutputBytes: Int = 4_194_304,
    val maximumAppendBytes: Int = 65_536,
    val maximumHeaderBytes: Int = 65_536,
    val maximumHeaderCount: Int = 256,
) {
    init {
        require(listOf(maximumInputBytes, maximumDecodedBytes, maximumOutputBytes).all { it in 0..16_777_216 })
        require(maximumAppendBytes in 0..1_048_576 && maximumHeaderBytes in 1..1_048_576)
        require(maximumHeaderCount in 1..4096)
    }
}

data class ActionHeader(val name: String, val value: String)

/** Final response head, not informational responses or an Upgrade/WebSocket exchange. */
data class ActionResponseHead(
    val status: Int,
    val headers: List<ActionHeader>,
    val reason: String = "",
)

/**
 * Trusted, already resolved literal insertion, not server-side JavaScript or a ScriptInjection executor.
 * The caller must separately provide asset resolution, serving, and authorization for this material.
 */
data class ResolvedHtmlAppend(val head: String = "", val body: String = "")

/** Header-only result: framing/encoding fields are identical to input, and no entity was consumed. */
data class StreamingActionHead(val head: ActionResponseHead)

enum class ActionFailure {
    UNSUPPORTED_ACTION, UNSUPPORTED_ENCODING, INVALID_INPUT, BODY_LIMIT, HEADER_LIMIT, MALFORMED_BODY,
}

class RuleActionException(
    val failure: ActionFailure,
    val action: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class ResolvedRequest(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val method: String,
    val headers: List<ActionHeader>,
    /** Routing/TLS data only; the engine applies its own supported trust/ECH policy. */
    val tls: TlsPolicy,
    val echRequired: Boolean,
)

sealed interface RequestExecution {
    data class Forward(val request: ResolvedRequest) : RequestExecution
    data class Terminal(val response: PreparedActionResponse) : RequestExecution
}

/**
 * Fully prepared bounded entity. All action/framing/body validation completed before this is returned.
 * writeHttp1 does not close/flush its caller-owned sink. Sink failures may still cause partial I/O.
 */
class PreparedActionResponse internal constructor(
    val head: ActionResponseHead,
    private val bytes: ByteArray,
) {
    val bodySize: Int get() = bytes.size
    fun bodyBytes(): ByteArray = bytes.clone()

    fun writeHttp1(sink: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 ").append(head.status).append(' ').append(head.reason).append("\r\n")
            for ((name, value) in head.headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        sink.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        sink.write(bytes)
    }
}
