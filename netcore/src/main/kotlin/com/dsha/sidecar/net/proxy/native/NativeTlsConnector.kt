package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.tls.CertificateHost
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A provider adapter must obey the route's trust mode, verifyHost, SNI and protocol policy.
 * Required features must fail closed rather than delegating silently to a weaker dialer.
 * The server retains ownership of raw-socket connect/handshake/exchange cancellation.
 */
fun interface NativeTlsDialer {
    fun connect(raw: Socket, policy: NativeTlsPolicy, handshakeTimeoutMillis: Int): SSLSocket
}

/** No global trust store, provider, hostname verifier, or SSL default is changed. */
class NativeTlsConnector : NativeTlsDialer {
    private val platformContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }
    }

    private val unverifiedContext: SSLContext by lazy {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    }

    /** Caller owns the connected raw socket, its deadlines, and closing on failure. */
    override fun connect(raw: Socket, policy: NativeTlsPolicy, handshakeTimeoutMillis: Int): SSLSocket {
        if (policy.echRequired) {
            raw.close()
            throw javax.net.ssl.SSLHandshakeException("Required ECH is unavailable in the ordinary TLS connector")
        }
        val context = if (policy.trustMode == UpstreamTrustMode.UNVERIFIED) unverifiedContext
            else policy.trustContext ?: platformContext
        val socket = context.socketFactory.createSocket(
            raw, policy.verifyHost, raw.port, true,
        ) as SSLSocket
        try {
            socket.useClientMode = true
            socket.soTimeout = handshakeTimeoutMillis
            socket.enabledProtocols = policy.protocols.filter {
                (it == "TLSv1.2" || it == "TLSv1.3") && it in socket.supportedProtocols
            }.toTypedArray()
                .also { require(it.isNotEmpty()) { "No supported route TLS protocol" } }
            socket.sslParameters = socket.sslParameters.apply {
                // JSSE may use SNI as the endpoint identity. Check verifyHost separately below.
                endpointIdentificationAlgorithm = null
                serverNames = policy.sniHost?.let { listOf(SNIHostName(it)) } ?: emptyList()
                // Leave ALPN unset: HTTP/1.1 fallback, without Android-incompatible API calls.
            }
            socket.startHandshake()
            if (policy.trustMode == UpstreamTrustMode.STRICT) {
                verifySubjectAlternativeName(
                    policy.verifyHost, socket.session.peerCertificates.first() as X509Certificate,
                )
            }
            return socket
        } catch (failure: Exception) {
            // Close the raw transport first: TLS close_notify must not hold up cancellation.
            runCatching { raw.close() }
            runCatching { socket.close() }
            throw failure
        }
    }
}

/** SAN-only identity verification: conservative DNS wildcards match exactly one label. */
internal fun verifySubjectAlternativeName(host: String, certificate: X509Certificate) {
    val canonical = CertificateHost.parse(host).canonicalName
    val ip = ':' in canonical || canonical.all { it in "0123456789." }
    val matched = certificate.subjectAlternativeNames.orEmpty().any { entry ->
        if (entry.size < 2) return@any false
        val type = entry[0] as? Int
        val name = entry[1] as? String ?: return@any false
        if (ip) {
            type == 7 && runCatching { CertificateHost.parse(name).canonicalName == canonical }.getOrDefault(false)
        } else if (type == 2) {
            val pattern = name.lowercase(java.util.Locale.ROOT).removeSuffix(".")
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(2)
                suffix.contains('.') && canonical.substringAfter('.', "") == suffix &&
                    canonical.substringBefore('.').isNotEmpty() &&
                    runCatching { CertificateHost.parse(suffix) }.isSuccess
            } else {
                runCatching { CertificateHost.parse(pattern).canonicalName == canonical }.getOrDefault(false)
            }
        } else false
    }
    if (!matched) throw SSLHandshakeException("Upstream certificate SAN does not match verifyHost")
}
