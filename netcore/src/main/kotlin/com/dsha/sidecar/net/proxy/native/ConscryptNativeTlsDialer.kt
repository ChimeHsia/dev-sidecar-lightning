package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.ech.ConscryptEchConnector
import com.dsha.sidecar.net.proxy.ech.EchConnectionException
import com.dsha.sidecar.net.proxy.ech.EchFailure
import com.dsha.sidecar.net.proxy.ech.EchPeerTrust
import com.dsha.sidecar.net.proxy.ech.TypedEchConfigList
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/** The port is the connected upstream TCP port, not an inferred HTTPS default. */
data class NativeEchRouteKey(val innerHost: String?, val verifyHost: String, val port: Int)

sealed class NativeEchSelection {
    /** Only an explicit policy decision that ECH is not required permits the platform dialer. */
    object NotRequired : NativeEchSelection()
    class Required(val configList: TypedEchConfigList) : NativeEchSelection()
    /** Required but unavailable/invalid config is a terminal failure, never ordinary TLS. */
    object RequiredButUnavailable : NativeEchSelection()
}

fun interface NativeEchConfigResolver {
    /** Synchronous in-memory lookup only; do DNS/config refresh before entering the dialer. */
    fun resolve(key: NativeEchRouteKey): NativeEchSelection
}

fun interface NativeEchTrustSupplier {
    fun trustFor(key: NativeEchRouteKey): EchPeerTrust
}

enum class NativeEchDialFailure {
    UNSUPPORTED_TRUST_POLICY,
    UNSUPPORTED_PROTOCOL_POLICY,
    MISSING_INNER_HOST,
    REQUIRED_CONFIG_UNAVAILABLE,
    CONFIG_RESOLUTION
}

class NativeEchDialException(val failure: NativeEchDialFailure, cause: Throwable? = null) :
    IOException("Native ECH dial failed: $failure", cause)

/**
 * Composite dialer with strict ECH. No ECH error is retried through the ordinary TLS branch.
 * The route's required-ECH policy also applies when the resolver selects ordinary TLS.
 */
class ConscryptNativeTlsDialer(
    private val configResolver: NativeEchConfigResolver,
    private val trustSupplier: NativeEchTrustSupplier = NativeEchTrustSupplier { EchPeerTrust.SystemRoots }
) : NativeTlsDialer {
    private val ordinary = NativeTlsConnector()
    private val ech = ConscryptEchConnector()

    override fun connect(raw: Socket, policy: NativeTlsPolicy, handshakeTimeoutMillis: Int): SSLSocket {
        try {
            val key = NativeEchRouteKey(policy.sniHost, policy.verifyHost, raw.port)
            val selection = try {
                configResolver.resolve(key)
            } catch (failure: Exception) {
                throw NativeEchDialException(NativeEchDialFailure.CONFIG_RESOLUTION, failure)
            }
            return when (selection) {
                NativeEchSelection.NotRequired -> {
                    if (policy.echRequired) {
                        throw NativeEchDialException(NativeEchDialFailure.REQUIRED_CONFIG_UNAVAILABLE)
                    }
                    ordinary.connect(raw, policy, handshakeTimeoutMillis)
                }
                NativeEchSelection.RequiredButUnavailable ->
                    throw NativeEchDialException(NativeEchDialFailure.REQUIRED_CONFIG_UNAVAILABLE)
                is NativeEchSelection.Required -> {
                    if (policy.trustMode != UpstreamTrustMode.STRICT || policy.trustContext != null) {
                        // An opaque SSLContext cannot safely be converted into a set of anchors.
                        throw NativeEchDialException(NativeEchDialFailure.UNSUPPORTED_TRUST_POLICY)
                    }
                    if ("TLSv1.3" !in policy.protocols) {
                        throw NativeEchDialException(NativeEchDialFailure.UNSUPPORTED_PROTOCOL_POLICY)
                    }
                    val innerHost = policy.sniHost
                        ?: throw NativeEchDialException(NativeEchDialFailure.MISSING_INNER_HOST)
                    ech.connect(
                        raw, selection.configList, innerHost, policy.verifyHost,
                        trustSupplier.trustFor(key), handshakeTimeoutMillis
                    ).socket
                }
            }
        } catch (failure: Exception) {
            runCatching { raw.close() }
            if (failure is EchConnectionException && failure.failure == EchFailure.TIMEOUT) {
                throw SocketTimeoutException("ECH handshake deadline").apply { initCause(failure) }
            }
            throw failure
        }
    }
}
