package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.http.native.*
import com.dsha.sidecar.net.proxy.http.*
import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.rules.native.*
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext

data class NativeRuntimeEchRoute(val innerHost: String?, val verifyHost: String, val port: Int)

/** A paired composite dialer must independently enforce REQUIRED ECH; no ordinary-TLS fallback. */
fun interface NativeRuntimeEchRequirement {
    /** Memory-only config/capability lookup; fail if the required configuration is unavailable. */
    fun requireConfiguration(route: NativeRuntimeEchRoute)
}

/**
 * The copied engine invokes both hooks on the same connection worker.
 * A thread-local holds at most one active plan per bounded worker, including the original captures.
 */
class NativeRuntimeRuleAdapter(
    private val config: NativeRuntimeConfig,
    private val routes: NativeRuntimeRouteResolver,
    private val clock: Clock = Clock.systemUTC(),
    private val upstreamTrustContext: SSLContext? = null,
    private val echRequirement: NativeRuntimeEchRequirement? = null,
    private val scriptRoutes: NativeRuntimeScriptRoutes? = null,
) : NativeExchangePolicy {
    private data class Exchange(val request: RuleRequest, val plan: RequestPlan)
    private val exchange = ThreadLocal<Exchange>()
    private val executor = RuleActionExecutor(ActionLimits(maximumOutputBytes = 65_536))
    private val cache = NativeRuntimeCacheCompatibility(clock)
    fun cacheState(): NativeRuntimeCacheState = cache.state()

    override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
        exchange.remove()
        routes.clearPrepared()
        if (request.head.method == "CONNECT") return NativeRequestDecision.Forward(request.head)
        val path = when (val target = request.head.target) {
            is Http1Target.Absolute -> target.originForm
            else -> target.raw
        }
        val headers = linkedMapOf<String, Any?>()
        request.head.headers.fields.groupBy { it.name.lowercase(java.util.Locale.ROOT) }.forEach { (name, fields) ->
            headers[name] = if (fields.size == 1) fields.single().value else fields.map { it.value }
        }
        val input = RuleRequest(request.authority.host, path, request.head.method, headers,
            if (request.secure) "https" else "http", request.authority.port)
        scriptRoutes?.serve(request, input, config.responseHeadersEnabled, clock.millis())?.let { return it }
        val planned = config.planner.plan(input)
        val supported = planned.copy(issues = planned.issues.filterNot { it.kind == IssueKind.GLOBAL_SCRIPT_MIDDLEWARE })
            .requireSupported()
        val prepared = cache.request(supported, input)
        prepared.terminal?.let { terminal ->
            return NativeRequestDecision.LocalResponse(Http1ResponseHead(request.head.version, terminal.status,
                terminal.reason, toHeaders(terminal.headers)))
        }
        val plan = prepared.plan
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(clock))
        val executionInput = if (input.method == "HEAD" && plan.localResponse != null) input.copy(method = "GET") else input
        return when (val execution = executor.executeRequest(plan, executionInput, date)) {
            is RequestExecution.Terminal -> {
                val response = execution.response
                NativeRequestDecision.LocalResponse(Http1ResponseHead(request.head.version,
                    response.head.status, response.head.reason, toHeaders(response.head.headers)),
                    response.bodyBytes())
            }
            is RequestExecution.Forward -> {
                val resolved = execution.request
                if (request.secure && resolved.scheme != "https") {
                    throw IOException("HTTPS-to-HTTP proxy downgrade is unsupported by the frozen engine")
                }
                val policy = if (resolved.scheme == "https") {
                    val sni = when (val name = resolved.tls.serverName) {
                        is ServerName.Send -> name.host.takeUnless {
                            com.dsha.sidecar.net.dns.IpAddress.isIpLiteral(it)
                        }
                        ServerName.Suppress -> null
                    }
                    NativeTlsPolicy(resolved.tls.verificationHost, sniHost = sni,
                        trustMode = UpstreamTrustMode.STRICT, trustContext = upstreamTrustContext)
                } else null
                if (resolved.echRequired) {
                    if (policy == null) throw IOException("ECH requires an upstream TLS route")
                    val requirement = echRequirement ?: throw IOException("Required ECH adapter is absent")
                    requirement.requireConfiguration(NativeRuntimeEchRoute(policy.sniHost, policy.verifyHost, resolved.port))
                }
                val authority = NativeAuthority(resolved.host, resolved.port)
                val fields = toHeaders(resolved.headers).fields.filterNot { it.name.equals("Host", true) } +
                    Http1Header("Host", " ${authority.wire}")
                val head = request.head.copy(target = Http1Target.Origin(resolved.path), headers = Http1Headers(fields))
                exchange.set(Exchange(input, plan))
                routes.prepare(resolved.host, resolved.port, policy)
                NativeRequestDecision.Forward(head)
            }
        }
    }

    override fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead): Http1ResponseHead {
        if (head.status < 200) {
            if (head.status == 101) exchange.remove()
            return head // Informational/Upgrade negotiation belongs to the transport, not entity actions.
        }
        val active = exchange.get() ?: throw IOException("Missing runtime exchange plan")
        try {
            val upstream = ActionResponseHead(head.status, head.headers.fields.map { ActionHeader(it.name, it.value) },
                head.reason)
            val result = cache.response(active.plan, active.request, upstream, config.responseHeadersEnabled, executor)
            return head.copy(headers = toHeaders(result.headers), reason = result.reason)
        } finally {
            exchange.remove()
        }
    }

    private fun toHeaders(fields: List<ActionHeader>): Http1Headers =
        Http1Headers(fields.map { Http1Header(it.name, " " + it.value) })
}
