package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.*
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import org.bouncycastle.util.encoders.Base64

internal class NativeClientFailure(val status: Int, cause: Throwable? = null) : IOException(cause)
internal class NativeUpstreamFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

internal data class NativeUpgradeOffer(
    val protocols: List<String>,
    val websocketKey: String?,
    val websocketProtocols: List<String>,
)

internal data class NativeHttpNegotiation(val expectContinue: Boolean, val upgrade: NativeUpgradeOffer?) {
    companion object {
        fun request(request: Http1Request): NativeHttpNegotiation {
            val head = request.head
            val expectations = head.headers.values("Expect").flatMap { it.split(',') }.map { it.trim() }
            if (expectations.isNotEmpty() &&
                (expectations.size != 1 || !expectations.single().equals("100-continue", true))
            ) throw NativeClientFailure(417)
            val expect = expectations.isNotEmpty() && head.version == "HTTP/1.1"
            val hasUpgrade = head.headers.values("Upgrade").isNotEmpty()
            val connectionUpgrade = head.headers.hasToken("Connection", "upgrade")
            if (hasUpgrade != connectionUpgrade) throw NativeClientFailure(400)
            if (!hasUpgrade) return NativeHttpNegotiation(expect, null)
            if (head.version != "HTTP/1.1" || head.method == "CONNECT" ||
                head.headers.hasToken("Connection", "close")
            ) throw NativeClientFailure(400)
            val protocols = upgradeProtocols(head.headers)
            val websocket = protocols.any { it == "websocket" }
            val key = if (websocket) {
                if (head.method != "GET" || hasRequestBody(request.framing) ||
                    head.headers.values("Sec-WebSocket-Version") != listOf("13")
                ) throw NativeClientFailure(400)
                val keys = head.headers.values("Sec-WebSocket-Key")
                if (keys.size != 1 || !keys.single().matches(Regex("[A-Za-z0-9+/]{22}==")) ||
                    Base64.decode(keys.single()).size != 16
                ) throw NativeClientFailure(400)
                keys.single()
            } else null
            val subprotocols = if (websocket) head.headers.tokens("Sec-WebSocket-Protocol") else emptyList()
            return NativeHttpNegotiation(expect, NativeUpgradeOffer(protocols, key, subprotocols))
        }
    }
}

internal fun hasRequestBody(framing: Http1BodyFraming): Boolean =
    framing != Http1BodyFraming.None && framing != Http1BodyFraming.Fixed(0)

private fun upgradeProtocols(headers: Http1Headers): List<String> {
    val protocols = headers.values("Upgrade").flatMap { it.split(',') }.map { it.trim(' ', '\t') }
    if (protocols.isEmpty() || protocols.any { protocol ->
        val parts = protocol.split('/')
        parts.size !in 1..2 || parts.any { !isToken(it) }
    }) throw Http1Exception("Invalid Upgrade protocol")
    return protocols.map { protocol ->
        val name = protocol.substringBefore('/').lowercase(Locale.ROOT)
        name + if ('/' in protocol) "/" + protocol.substringAfter('/') else ""
    }
}

/** Validate before sending a 101 or giving either buffered stream to an opaque pump. */
internal fun validateUpstreamUpgrade(response: Http1Response, offer: NativeUpgradeOffer?) {
    try {
        val head = response.head
        if (offer == null || head.version != "HTTP/1.1" ||
            !head.headers.hasToken("Connection", "upgrade") || head.headers.hasToken("Connection", "close") ||
            head.headers.values("Content-Length").isNotEmpty() ||
            head.headers.values("Transfer-Encoding").isNotEmpty()
        ) throw NativeUpstreamFailure("Invalid or unsolicited upstream 101")
        val selected = upgradeProtocols(head.headers)
        if (selected.any { it !in offer.protocols } || selected.distinct().size != selected.size) {
            throw NativeUpstreamFailure("Upstream selected an unoffered Upgrade protocol")
        }
        if ("websocket" in selected) {
            if (selected.size != 1 || offer.websocketKey == null) {
                throw NativeUpstreamFailure("Invalid WebSocket protocol selection")
            }
            val digest = MessageDigest.getInstance("SHA-1").digest(
                (offer.websocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII),
            )
            if (head.headers.values("Sec-WebSocket-Accept") != listOf(Base64.toBase64String(digest))) {
                throw NativeUpstreamFailure("Invalid Sec-WebSocket-Accept")
            }
            val subprotocol = head.headers.tokens("Sec-WebSocket-Protocol")
            if (subprotocol.size > 1 || subprotocol.any { it !in offer.websocketProtocols }) {
                throw NativeUpstreamFailure("Upstream selected an unoffered WebSocket subprotocol")
            }
        }
    } catch (failure: Http1Exception) {
        throw NativeUpstreamFailure("Malformed upstream Upgrade fields", failure)
    }
}
