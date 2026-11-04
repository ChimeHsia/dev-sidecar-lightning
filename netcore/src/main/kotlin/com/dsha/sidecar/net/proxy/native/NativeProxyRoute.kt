package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.Http1Exception
import com.dsha.sidecar.net.proxy.http.Http1RequestHead
import com.dsha.sidecar.net.proxy.http.Http1Target
import com.dsha.sidecar.net.proxy.tls.CertificateHost
import java.net.InetSocketAddress
import java.net.URI
import javax.net.ssl.SSLContext

enum class ConnectPolicy { MITM, TUNNEL, BLOCK }
enum class UpstreamTrustMode { STRICT, CHAIN_ONLY, UNVERIFIED }

data class NativeTlsPolicy(
    val verifyHost: String,
    val sniHost: String? = verifyHost.takeUnless { ':' in it || it.all { c -> c in "0123456789." } },
    val trustMode: UpstreamTrustMode = UpstreamTrustMode.STRICT,
    /** A caller-owned trust context. Never reinitialized by the connector. */
    val trustContext: SSLContext? = null,
    val protocols: List<String> = listOf("TLSv1.3", "TLSv1.2"),
) {
    init {
        CertificateHost.parse(verifyHost)
        sniHost?.let {
            require(':' !in it && !it.all { c -> c in "0123456789." })
            CertificateHost.parse(it)
        }
        require(protocols.isNotEmpty() && protocols.all { it == "TLSv1.2" || it == "TLSv1.3" })
        require(trustMode != UpstreamTrustMode.UNVERIFIED || trustContext == null) {
            "UNVERIFIED uses its own explicitly insecure context"
        }
    }
}

data class NativeProxyRoute(
    /** Supply a resolved address to keep DNS resolution outside the engine's connect deadline. */
    val destination: InetSocketAddress,
    val connectPolicy: ConnectPolicy = ConnectPolicy.MITM,
    val tls: NativeTlsPolicy? = null,
) {
    init {
        require(!destination.isUnresolved && destination.port in 1..65535)
    }
}

data class NativeAuthority(val host: String, val port: Int) {
    init {
        require(port in 1..65535)
        CertificateHost.parse(host)
    }

    val wire: String get() = (if (':' in host) "[$host]" else host) + ":$port"

    companion object {
        fun parse(raw: String, defaultPort: Int): NativeAuthority {
            val uri = try {
                URI("http://$raw")
            } catch (_: Exception) {
                throw Http1Exception("Invalid authority")
            }
            if (uri.host == null || uri.rawAuthority != raw || uri.rawUserInfo != null ||
                !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
                raw.endsWith(':') || uri.port == 0 || uri.port > 65535
            ) throw Http1Exception("Invalid authority")
            val host = try {
                CertificateHost.parse(uri.host.removePrefix("[").removeSuffix("]")).canonicalName
            } catch (_: IllegalArgumentException) {
                throw Http1Exception("Invalid authority host")
            }
            return NativeAuthority(host, if (uri.port == -1) defaultPort else uri.port)
        }
    }
}

data class NativeProxyRequest(
    val head: Http1RequestHead,
    val authority: NativeAuthority,
    val secure: Boolean,
    val connectAuthority: NativeAuthority?,
)

fun interface RouteResolver {
    fun resolve(request: NativeProxyRequest): NativeProxyRoute
}

/** Transform a head only; the engine revalidates authority and framing before sending bytes. */
fun interface NativeRequestTransformer {
    fun transform(request: NativeProxyRequest): Http1RequestHead
}

internal fun validateRequest(head: Http1RequestHead, bound: NativeAuthority?): NativeProxyRequest {
    val connect = head.method == "CONNECT"
    if (connect && bound != null) throw Http1Exception("Nested CONNECT")
    val defaultPort = if (connect || bound != null) 443 else 80
    val hosts = head.headers.values("Host")
    if (hosts.size != 1) throw Http1Exception("Exactly one Host is required")
    val host = NativeAuthority.parse(hosts.single(), defaultPort)
    val targetAuthority = when (val target = head.target) {
        is Http1Target.Authority -> NativeAuthority.parse(target.raw, 443)
        is Http1Target.Absolute -> {
            val scheme = URI(target.raw).scheme
            if (!scheme.equals(if (bound == null) "http" else "https", true)) {
                throw Http1Exception("Target scheme does not match connection")
            }
            NativeAuthority.parse(target.authority, defaultPort)
        }
        is Http1Target.Origin -> {
            if (target.raw.startsWith("//")) throw Http1Exception("Ambiguous origin target")
            host
        }
        Http1Target.Asterisk -> host
    }
    if (host != targetAuthority || (bound != null && bound != targetAuthority)) {
        throw Http1Exception("Target, Host and CONNECT authority must agree")
    }
    return NativeProxyRequest(head, targetAuthority, connect || bound != null, bound)
}
