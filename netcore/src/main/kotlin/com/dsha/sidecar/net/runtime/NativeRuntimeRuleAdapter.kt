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
    private val scripts: NativeRuntimeScripts = NativeRuntimeScripts(config),
    private val legacyTlsCompatibility: Boolean = false,
) : NativeExchangePolicy {
    private data class Exchange(val request: RuleRequest, var plan: RequestPlan,
        val failed: MutableMap<String, MutableSet<String>> = mutableMapOf(), var backupIndex: Int = 0)
    private val exchange = ThreadLocal<Exchange>()
    private val executor = RuleActionExecutor(ActionLimits(maximumOutputBytes = 65_536))
    private val cache = NativeRuntimeCacheCompatibility(clock)
    private val server = NativeRuntimeConfig.objectAt(config.decoded, "server").ifEmpty { config.decoded }
    private val whitelist = com.dsha.sidecar.net.rules.HostnameMatcher(
        PlannerJson.obj(server["whiteList"]))
    private val intercepted = com.dsha.sidecar.net.rules.HostnameAllMatcher(
        NativeRuntimeConfig.objectAt(server, "intercepts"))
    private val dnsSelection = com.dsha.sidecar.net.dns.DnsProviderSelection(config.catalog,
        ech = NativeRuntimeConfig.objectAt(config.dns, "ech"),
        nat64Domains = NativeRuntimeConfig.objectAt(config.dns, "nat64")["domains"])
    fun cacheState(): NativeRuntimeCacheState = cache.state()

    override fun onExchangeComplete() {
        exchange.remove()
        routes.clearPrepared()
    }

    override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
        exchange.remove()
        routes.clearPrepared()
        if (request.head.method == "CONNECT") {
            val shouldIntercept = !PlannerJson.truthy(whitelist.match(request.authority.host)) &&
                (dnsSelection.isEchDomain(request.authority.host) ||
                    config.responseHeadersEnabled && scriptRoutes?.allowsConnect(request) == true ||
                    intercepted.match(request.authority.host) != null &&
                    NativeRuntimeConfig.objectAt(server, "intercept")["enabled"] != false)
            return NativeRequestDecision.Forward(request.head, if (shouldIntercept)
                NativeProxyRoute(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"),
                    request.authority.port)) else routes.select(request.authority.host, request.authority.port, null)
                    .route.copy(connectPolicy = ConnectPolicy.TUNNEL))
        }
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
        val supported = planned.copy(issues = planned.issues.filterNot { NativeRuntimeConfig.boundIssue(it) })
            .requireSupported()
        val active = Exchange(input, supported)
        exchange.set(active)
        val prepared = cache.request(supported, input)
        prepared.terminal?.let { terminal ->
            return NativeRequestDecision.LocalResponse(Http1ResponseHead(request.head.version, terminal.status,
                terminal.reason, toHeaders(terminal.headers)))
        }
        val plan = prepared.plan
        return execute(request, input, plan)
    }

    private fun execute(request: NativeProxyRequest, input: RuleRequest, plan: RequestPlan,
        excluded: Set<String>? = null): NativeRequestDecision {
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
                val policy = if (resolved.scheme == "https") {
                    val sni = when (val name = resolved.tls.serverName) {
                        is ServerName.Send -> name.host.takeUnless {
                            com.dsha.sidecar.net.dns.IpAddress.isIpLiteral(it)
                        }
                        ServerName.Suppress -> null
                    }
                    NativeTlsPolicy(resolved.tls.verificationHost, sniHost = sni,
                        trustMode = if (legacyTlsCompatibility &&
                            resolved.tls.trustMode == TrustMode.UPSTREAM_COMPAT_UNVERIFIED &&
                            !resolved.echRequired) UpstreamTrustMode.UNVERIFIED else UpstreamTrustMode.STRICT,
                        trustContext = upstreamTrustContext.takeUnless { legacyTlsCompatibility &&
                            resolved.tls.trustMode == TrustMode.UPSTREAM_COMPAT_UNVERIFIED && !resolved.echRequired },
                        echRequired = resolved.echRequired)
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
                NativeRequestDecision.Forward(head, routes.select(resolved.host, resolved.port, policy,
                    excluded.orEmpty()).route.copy(permitsPlaintext = resolved.scheme == "http"))
            }
        }
    }

    override fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead): Http1ResponseHead {
        if (head.status < 200) {
            if (head.status == 101) exchange.remove()
            return head // Informational/Upgrade negotiation belongs to the transport, not entity actions.
        }
        val active = exchange.get() ?: throw IOException("Missing runtime exchange plan")
        val upstream = ActionResponseHead(head.status, head.headers.fields.map { ActionHeader(it.name, it.value) },
                head.reason)
        val result = cache.response(active.plan, active.request, upstream, config.responseHeadersEnabled, executor)
        return head.copy(headers = toHeaders(result.headers), reason = result.reason)
    }

    override fun onResponseEntity(request: NativeProxyRequest, head: Http1ResponseHead,
        entity: NativeResponseEntity): NativePreparedResponse? {
        val active = exchange.get() ?: return null
        try {
            return scripts.prepare(active.request, active.plan, head, entity, config.responseHeadersEnabled)
        } finally { exchange.remove() }
    }

    override fun replayBodyLimit(request: NativeProxyRequest): Int {
        val active = exchange.get() ?: return 0
        val retry = active.plan.retry ?: return 0
        return if (retry.requestBodyEligible && active.request.method in retry.methods)
            retry.maximumBufferedBodyBytes else 0
    }

    override fun retry(request: NativeProxyRequest, previous: NativeProxyRoute, retries: Int,
        failure: Exception?, status: Int?): NativeRequestDecision.Forward? {
        val active = exchange.get() ?: return null
        val descriptor = active.plan.retry
        val methods = descriptor?.methods ?: listOf("GET", "HEAD", "OPTIONS")
        if (active.request.method !in methods) return null
        val backup = active.plan.requestSteps.mapNotNull {
            (it.action as? RequestAction.Proxy)?.backup
        }.lastOrNull()
        val maximum = (descriptor?.times ?: maxOf(3, backup?.alternatives?.size ?: 0)).coerceAtMost(16)
        if (retries >= maximum) return null
        if (status != null && status !in (descriptor?.statuses ?: emptyList()) &&
            !(backup != null && status >= 500)) return null
        val key = "${request.authority.host}:${request.authority.port}"
        val excluded = active.failed.getOrPut(key) { linkedSetOf() }
        previous.destination.address.hostAddress?.let { excluded.add(it.substringBefore('%')) }
        if (failure != null) routes.requestFailed(previous)
        // An explicit status retry may reuse a healthy destination; connection failures must switch IP.
        if (status != null && backup == null) excluded.clear()
        var plan = active.plan
        if (backup != null && active.backupIndex < backup.alternatives.size) {
            val target = backup.alternatives[active.backupIndex++]
            plan = config.planner.plan(active.request, target).let {
                it.copy(issues = it.issues.filterNot(NativeRuntimeConfig::boundIssue))
            }.requireSupported()
        }
        val executionPlan = cache.request(plan, active.request).plan
        return try {
            val nextKey = plan.routeOverride?.let { "${it.host}:${it.port}" } ?: key
            val next = execute(request, active.request, executionPlan,
                active.failed[nextKey].orEmpty()) as? NativeRequestDecision.Forward
            if (next != null) active.plan = plan
            next
        } catch (_: IOException) { null }
    }

    private fun toHeaders(fields: List<ActionHeader>): Http1Headers =
        Http1Headers(fields.map { Http1Header(it.name, " " + it.value) })
}
