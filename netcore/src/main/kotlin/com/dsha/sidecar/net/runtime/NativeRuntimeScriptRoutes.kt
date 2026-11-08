package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.http.native.script.CatalogSource
import com.dsha.sidecar.net.http.native.script.ReadOnlyScriptCatalog
import com.dsha.sidecar.net.http.native.script.ScriptInjectionException
import com.dsha.sidecar.net.http.native.script.ScriptLimits
import com.dsha.sidecar.net.http.native.script.ScriptRouteDecision
import com.dsha.sidecar.net.http.native.script.ScriptRouteService
import com.dsha.sidecar.net.proxy.http.Http1Header
import com.dsha.sidecar.net.proxy.http.Http1Headers
import com.dsha.sidecar.net.proxy.http.Http1ResponseHead
import com.dsha.sidecar.net.proxy.native.NativeProxyRequest
import com.dsha.sidecar.net.proxy.native.NativeRequestDecision
import com.dsha.sidecar.net.proxy.tls.CertificateHost
import com.dsha.sidecar.net.rules.native.RuleRequest
import java.net.URI

class NativeRuntimeScriptRoutes(
    sources: Map<String, CatalogSource>,
    allowedOrigins: Set<String>,
    proxyAliases: Map<String, String> = emptyMap(),
    private val limits: ScriptLimits = ScriptLimits(maximumAssetBytes = 65_536),
) {
    private data class Origin(val scheme: String, val host: String, val port: Int)

    private val origins = allowedOrigins.also {
        require(it.isNotEmpty() && it.size <= 64) { "Script origins must contain 1..64 explicit origins" }
    }.map { value ->
        val uri = URI(value)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
            !uri.rawAuthority.endsWith(':') && uri.port in -1..65535 && uri.port != 0) {
            "Script authorization requires an HTTP(S) origin without credentials, path, query or fragment"
        }
        Origin(uri.scheme, CertificateHost.parse(uri.host.removePrefix("[").removeSuffix("]")).canonicalName,
            if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port)
    }.toSet()

    private val service = run {
        require(limits.maximumAssetBytes <= 65_536) { "Script assets must fit the local response limit" }
        ScriptRouteService(ReadOnlyScriptCatalog(sources, limits), proxyAliases, limits)
    }

    internal fun allowsConnect(request: NativeProxyRequest): Boolean =
        Origin("https", request.authority.host, request.authority.port) in origins

    internal fun requireBodyLimit(maximum: Long) {
        require(limits.maximumAssetBytes <= maximum) { "Script asset limit exceeds the proxy body quota" }
    }

    internal fun serve(
        request: NativeProxyRequest,
        input: RuleRequest,
        enabled: Boolean,
        nowMillis: Long,
    ): NativeRequestDecision.LocalResponse? {
        if (!enabled || Origin(input.scheme, input.host, input.port) !in origins) return null
        val representation = if (input.method == "HEAD") input.copy(method = "GET") else input
        return when (val decision = service.serve(representation, enabled, nowMillis)) {
            ScriptRouteDecision.NotHandled -> null
            is ScriptRouteDecision.Blocked -> throw ScriptInjectionException(decision.problem)
            is ScriptRouteDecision.Served -> {
                val response = decision.response
                NativeRequestDecision.LocalResponse(
                    Http1ResponseHead(request.head.version, response.head.status, response.head.reason,
                        Http1Headers(response.head.headers.map { Http1Header(it.name, " " + it.value) })),
                    response.bodyBytes(),
                )
            }
        }
    }
}
