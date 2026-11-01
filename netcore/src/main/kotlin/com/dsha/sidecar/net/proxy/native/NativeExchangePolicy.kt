package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.Http1RequestHead
import com.dsha.sidecar.net.proxy.http.Http1ResponseHead

/**
 * Trusted, synchronous rule boundary. The engine still validates syntax, framing and negotiation.
 * Callbacks must return promptly and must not read sockets or mutate shared request state.
 */
interface NativeExchangePolicy {
    fun onRequest(request: NativeProxyRequest): NativeRequestDecision =
        NativeRequestDecision.Forward(request.head)

    /** Applied to upstream heads before hop sanitizing; body framing and protocol identity are retained. */
    fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead): Http1ResponseHead = head

    companion object {
        val Passthrough: NativeExchangePolicy = object : NativeExchangePolicy {}
    }
}

sealed class NativeRequestDecision {
    /** Only this trusted decision may change logical authority; downstream CONNECT identity stays fixed. */
    data class Forward(val head: Http1RequestHead, val route: NativeProxyRoute? = null) : NativeRequestDecision()

    /** Terminal response, capped at 64 KiB and the configured body quota; always closes downstream. */
    class LocalResponse(val head: Http1ResponseHead, body: ByteArray = byteArrayOf()) : NativeRequestDecision() {
        init { require(body.size <= 65_536) { "Local response exceeds 64 KiB" } }
        internal val body: ByteArray = body.copyOf()
    }
}
