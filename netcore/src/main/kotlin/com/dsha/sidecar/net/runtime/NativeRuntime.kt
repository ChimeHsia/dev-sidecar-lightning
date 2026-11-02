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
    tlsDialer: NativeTlsDialer = NativeTlsConnector(),
    upstreamTrustContext: SSLContext? = null,
    echRequirement: NativeRuntimeEchRequirement? = null,
    endpointResolver: NativeDnsEndpointResolver = NativeDnsEndpointResolver.SYSTEM,
    clock: Clock = Clock.systemUTC(),
    dnsTrustContext: SSLContext? = null,
) : Closeable {
    init {
        if (config.requiresEchAdapter && echRequirement == null) {
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
    }

    private val transport = SocketDnsTransport(endpointResolver,
        sslContext = dnsTrustContext ?: SSLContext.getInstance("TLS").apply { init(null, null, null) },
        limits = options.dnsTransport)
    private val resolver = try { NativeDnsResolver(config.catalog, transport,
        NativeDnsClock { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()).coerceAtLeast(0) },
        mapping = NativeRuntimeConfig.objectAt(config.dns, "mapping"), presets = config.presets,
        ech = NativeRuntimeConfig.objectAt(config.dns, "ech").takeIf { it.isNotEmpty() },
        ipv6Available = options.ipv6Available, limits = options.dnsCache)
    } catch (failure: Exception) {
        transport.close()
        throw failure
    }
    val routes = NativeRuntimeRouteResolver(resolver, options.dnsTimeoutMillis)
    private val certificates = LeafCertificateCache(authority, maxSize = options.certificateCacheSize)
    val proxyOptions: NativeProxyOptions = options.proxy.copy(port = config.listenPort ?: options.proxy.port)
    private val rules = NativeRuntimeRuleAdapter(config, routes, clock, upstreamTrustContext, echRequirement)
    private val engine = NativeProxyServer(routes, certificates, proxyOptions, tlsConnector = tlsDialer,
        exchangePolicy = rules)
    private val lifecycle = Any()
    @Volatile private var phase = NativeRuntimeState.NEW
    val state: NativeRuntimeState get() = if (phase == NativeRuntimeState.LISTENING &&
        engine.state == NativeProxyState.FAILED) NativeRuntimeState.FAILED else phase
    val failure: Throwable? get() = engine.failure
    val localAddress: InetSocketAddress? get() = engine.localAddress
    val activeSocketCount: Int get() = engine.activeSocketCount
    fun dnsState(): NativeDnsResolver.State = resolver.state()
    fun transportState(): SocketDnsTransport.State = transport.state()
    fun cacheState(): NativeRuntimeCacheState = rules.cacheState()

    fun start(): InetSocketAddress = synchronized(lifecycle) {
        check(phase == NativeRuntimeState.NEW) { "Native runtime is single-use: $phase" }
        try {
            engine.start().also { phase = NativeRuntimeState.LISTENING }
        } catch (failure: Exception) {
            phase = NativeRuntimeState.FAILED
            closeDns()
            throw failure
        }
    }

    private fun closeDns() {
        routes.close()
        resolver.close() // Owns the real SocketDnsTransport; cancels in-flight futures before workers wait.
    }

    override fun close() = synchronized(lifecycle) {
        if (phase != NativeRuntimeState.STOPPED) {
            phase = NativeRuntimeState.STOPPED
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
            tlsDialer: NativeTlsDialer = NativeTlsConnector(),
            upstreamTrustContext: SSLContext? = null,
            echRequirement: NativeRuntimeEchRequirement? = null,
            dnsTrustContext: SSLContext? = null,
        ): NativeRuntime = NativeRuntime(NativeRuntimeConfig.decode(config), authority, options, tlsDialer,
            upstreamTrustContext, echRequirement, dnsTrustContext = dnsTrustContext)
    }
}
