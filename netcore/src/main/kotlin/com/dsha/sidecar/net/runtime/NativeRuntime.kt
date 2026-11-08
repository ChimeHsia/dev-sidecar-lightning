package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.dns.native.*
import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.proxy.tls.LeafCertificateCache
import com.dsha.sidecar.net.proxy.tls.MitmCertificateAuthority
import java.io.Closeable
import java.net.InetSocketAddress
import java.time.Clock
import javax.net.ssl.SSLContext

data class NativeRuntimeOptions(
    val proxy: NativeProxyOptions = NativeProxyOptions(),
    val dnsTimeoutMillis: Int = 5_000,
    val dnsTransport: SocketDnsLimits = SocketDnsLimits(),
    val dnsCache: NativeDnsLimits = NativeDnsLimits(),
    val ipv6Available: Boolean = true,
    val certificateCacheSize: Int = 64,
    val allowSystemDnsFallback: Boolean = false,
    val legacyTlsCompatibility: Boolean = false,
) {
    init { require(dnsTimeoutMillis > 0 && certificateCacheSize > 0) }
}

enum class NativeRuntimeState { NEW, LISTENING, FAILED, STOPPED }

/**
 * JVM-only owned assembly. Construction audits an immutable config; start binds one loopback port.
 * No Android entry point, global proxy, application persistence, Node or on-disk CA material.
 */
class NativeRuntime(
    val config: NativeRuntimeConfig,
    authority: MitmCertificateAuthority,
    val options: NativeRuntimeOptions = NativeRuntimeOptions(),
    private val tlsDialer: NativeTlsDialer? = null,
    private val upstreamTrustContext: SSLContext? = null,
    private val echRequirement: NativeRuntimeEchRequirement? = null,
    private val endpointResolver: NativeDnsEndpointResolver = NativeDnsEndpointResolver.SYSTEM,
    private val clock: Clock = Clock.systemUTC(),
    private val dnsTrustContext: SSLContext? = null,
    private val navigationRules: (() -> NativeNavigationRules)? = null,
    private val scriptRoutes: NativeRuntimeScriptRoutes? = null,
    private val scriptSources: Map<String, com.dsha.sidecar.net.http.native.script.CatalogSource> = emptyMap(),
) : Closeable {
    init {
        if (config.requiresEchAdapter && tlsDialer != null && echRequirement == null) {
            throw NativeRuntimeConfigurationException(listOf("Required ECH configuration needs a paired injected composite TLS dialer and requirement resolver"))
        }
        if (config.requiresEchAdapter && tlsDialer is NativeTlsConnector) {
            throw NativeRuntimeConfigurationException(listOf("Required ECH cannot use the ordinary NativeTlsConnector"))
        }
        // Validate the injected issuer before allocating transport executors.
        authority.certificate.checkValidity(java.util.Date.from(clock.instant()))
        require(config.listenPort == null || options.proxy.port == 0 || options.proxy.port == config.listenPort) {
            "Conflicting server.port and runtime proxy port"
        }
        scriptRoutes?.requireBodyLimit(options.proxy.limits.maxBodyBytes)
    }

    private val authority = authority
    private val revisionLock = Any()
    @Volatile private var currentEpoch: NativeRuntime = this
    private val activeEpoch = ThreadLocal<NativeRuntime>()
    private val leases = mutableMapOf<NativeRuntime, Int>()
    private val retired = mutableSetOf<NativeRuntime>()
    val currentConfig: NativeRuntimeConfig get() = currentEpoch.config
    private val runtimeScripts = NativeRuntimeScripts(config, scriptSources)
    private val transport = SocketDnsTransport(endpointResolver,
        sslContext = dnsTrustContext ?: SSLContext.getInstance("TLS").apply { init(null, null, null) },
        limits = options.dnsTransport)
    private val dnsClock = NativeDnsClock { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()).coerceAtLeast(0) }
    private val nat64 = NativeNat64Transport(transport, dnsClock)
    private val speed = NativeRuntimeSpeed(config)
    private val resolver = try { NativeDnsResolver(config.catalog, nat64, dnsClock,
        mapping = NativeRuntimeConfig.objectAt(config.dns, "mapping"), presets = config.presets,
        ech = NativeRuntimeConfig.objectAt(config.dns, "ech").takeIf { it.isNotEmpty() },
        nat64Domains = NativeRuntimeConfig.objectAt(config.dns, "nat64")["domains"],
        ipv6Available = options.ipv6Available, limits = options.dnsCache, candidateOrder = speed)
    } catch (failure: Exception) {
        nat64.close()
        speed.close()
        throw failure
    }
    private val localRoutes = NativeRuntimeRouteResolver(resolver, options.dnsTimeoutMillis, options.allowSystemDnsFallback)
    val routes: NativeRuntimeRouteResolver get() = currentEpoch.localRoutes
    private val automaticEch = try {
        if (tlsDialer == null) NativeRuntimeEch(config, nat64, dnsClock, options.dnsTimeoutMillis) else null
    } catch (failure: Exception) {
        localRoutes.close()
        resolver.close()
        speed.close()
        throw failure
    }
    private val selectedDialer = tlsDialer ?: ConscryptNativeTlsDialer(requireNotNull(automaticEch))
    private val certificates = LeafCertificateCache(authority, maxSize = options.certificateCacheSize)
    private val settings = NativeRuntimeConfig.objectAt(
        NativeRuntimeConfig.objectAt(config.decoded, "server").ifEmpty { config.decoded }, "setting")
    val proxyOptions: NativeProxyOptions = options.proxy.copy(
        port = config.listenPort ?: options.proxy.port,
        readTimeoutMillis = (settings["defaultTimeout"] as? Number)?.toInt()?.coerceIn(100, 120_000)
            ?: options.proxy.readTimeoutMillis)
    private val rules = NativeRuntimeRuleAdapter(config, localRoutes, clock, upstreamTrustContext,
        echRequirement ?: automaticEch, scriptRoutes, runtimeScripts, options.legacyTlsCompatibility)
    private val policy = if (navigationRules == null) rules else NativeNavigationPolicy(rules, navigationRules)
    private val dispatch = object : NativeExchangePolicy {
        override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
            onExchangeComplete()
            val epoch = synchronized(revisionLock) {
                currentEpoch.also { leases[it] = (leases[it] ?: 0) + 1; activeEpoch.set(it) }
            }
            return epoch.policy.onRequest(request)
        }
        private fun selected() = checkNotNull(activeEpoch.get()) { "No native configuration epoch" }
        override fun onResponseHead(request: NativeProxyRequest, head: com.dsha.sidecar.net.proxy.http.Http1ResponseHead) =
            selected().policy.onResponseHead(request, head)
        override fun onResponseEntity(request: NativeProxyRequest, head: com.dsha.sidecar.net.proxy.http.Http1ResponseHead,
            entity: NativeResponseEntity) = selected().policy.onResponseEntity(request, head, entity)
        override fun retry(request: NativeProxyRequest, previous: NativeProxyRoute, retries: Int,
            failure: Exception?, status: Int?) = selected().policy.retry(request, previous, retries, failure, status)
        override fun replayBodyLimit(request: NativeProxyRequest) = selected().policy.replayBodyLimit(request)
        override fun onExchangeComplete() {
            val epoch = activeEpoch.get() ?: return
            activeEpoch.remove()
            epoch.policy.onExchangeComplete()
            val release = synchronized(revisionLock) {
                val count = (leases[epoch] ?: 1) - 1
                if (count == 0) leases.remove(epoch) else leases[epoch] = count
                count == 0 && retired.remove(epoch)
            }
            if (release) releaseEpoch(epoch)
        }
    }
    private val engine = NativeProxyServer(
        RouteResolver { (activeEpoch.get() ?: currentEpoch).localRoutes.resolve(it) }, certificates, proxyOptions,
        tlsConnector = NativeTlsDialer { raw, policy, timeout ->
            (activeEpoch.get() ?: currentEpoch).selectedDialer.connect(raw, policy, timeout)
        }, exchangePolicy = dispatch)
    private val lifecycle = Any()
    @Volatile private var phase = NativeRuntimeState.NEW
    val state: NativeRuntimeState get() = if (phase == NativeRuntimeState.LISTENING &&
        engine.state == NativeProxyState.FAILED) NativeRuntimeState.FAILED else phase
    val failure: Throwable? get() = engine.failure
    val localAddress: InetSocketAddress? get() = engine.localAddress
    val activeSocketCount: Int get() = engine.activeSocketCount
    fun dnsState(): NativeDnsResolver.State = currentEpoch.resolver.state()
    fun transportState(): SocketDnsTransport.State = currentEpoch.transport.state()
    fun cacheState(): NativeRuntimeCacheState = currentEpoch.rules.cacheState()

    /** Publish one validated epoch; existing exchanges retain their DNS/TLS/rules until completion. */
    fun reconfigure(next: NativeRuntimeConfig): Unit = synchronized(lifecycle) {
        check(state == NativeRuntimeState.LISTENING) { "Runtime must be listening before reconfiguration" }
        require(next.listenPort == null || next.listenPort == localAddress?.port) { "Live port changes require restart" }
        val replacement = NativeRuntime(next, authority, options.copy(proxy = proxyOptions), tlsDialer,
            upstreamTrustContext, echRequirement, endpointResolver, clock, dnsTrustContext, navigationRules,
            scriptRoutes, scriptSources)
        val obsolete = synchronized(revisionLock) {
            val previous = currentEpoch
            currentEpoch = replacement
            if ((leases[previous] ?: 0) == 0) previous else {
                retired.add(previous)
                null
            }
        }
        replacement.speed.start(replacement.resolver)
        obsolete?.let(::releaseEpoch)
    }

    private fun releaseEpoch(epoch: NativeRuntime) {
        if (epoch === this) closeDns() else epoch.close()
    }

    fun start(): InetSocketAddress = synchronized(lifecycle) {
        check(phase == NativeRuntimeState.NEW) { "Native runtime is single-use: $phase" }
        try {
            engine.start().also { phase = NativeRuntimeState.LISTENING; speed.start(resolver) }
        } catch (failure: Exception) {
            phase = NativeRuntimeState.FAILED
            closeDns()
            throw failure
        }
    }

    private fun closeDns() {
        automaticEch?.close()
        speed.close()
        localRoutes.close()
        resolver.close() // Owns the real SocketDnsTransport; cancels in-flight futures before workers wait.
    }

    override fun close(): Unit = synchronized(lifecycle) {
        if (phase != NativeRuntimeState.STOPPED) {
            phase = NativeRuntimeState.STOPPED
            val revisions = synchronized(revisionLock) {
                (retired.toList() + currentEpoch).distinct().also { retired.clear() }
            }
            revisions.filter { it !== this }.forEach { it.close() }
            closeDns()
            engine.close()
            certificates.clear()
        }
    }

    companion object {
        fun fromDecoded(
            config: Map<String, Any?>,
            authority: MitmCertificateAuthority,
            options: NativeRuntimeOptions = NativeRuntimeOptions(),
            tlsDialer: NativeTlsDialer? = null,
            upstreamTrustContext: SSLContext? = null,
            echRequirement: NativeRuntimeEchRequirement? = null,
            dnsTrustContext: SSLContext? = null,
            navigationRules: (() -> NativeNavigationRules)? = null,
            scriptRoutes: NativeRuntimeScriptRoutes? = null,
        ): NativeRuntime = NativeRuntime(NativeRuntimeConfig.decode(config), authority, options, tlsDialer,
            upstreamTrustContext, echRequirement, dnsTrustContext = dnsTrustContext,
            navigationRules = navigationRules, scriptRoutes = scriptRoutes)
    }
}
