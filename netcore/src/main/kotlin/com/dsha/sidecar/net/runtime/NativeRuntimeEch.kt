package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.dns.native.*
import com.dsha.sidecar.net.proxy.ech.TypedEchConfigList
import com.dsha.sidecar.net.proxy.native.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Queries HTTPS records on bounded proxy workers; the TLS dialer only reads the prepared result. */
internal class NativeRuntimeEch(
    config: NativeRuntimeConfig,
    transport: NativeNat64Transport,
    private val clock: NativeDnsClock,
    private val timeoutMillis: Int,
) : NativeRuntimeEchRequirement, NativeEchConfigResolver, Closeable {
    private data class Entry(val list: TypedEchConfigList, val expires: Long)
    private val raw = NativeRuntimeConfig.objectAt(config.dns, "ech")
    private val nat64 = NativeNat64EchDiscovery.create(
        NativeRuntimeConfig.objectAt(config.dns, "providers"), config.dns, transport, clock)
    private val resolver = try {
        NativeEchResolver(config.catalog, transport, clock,
            NativeEchOptions.fromRaw(raw), NativeRuntimeConfig.objectAt(config.dns, "mapping"),
            ownsTransport = false)
    } catch (failure: Exception) { nat64.close(); throw failure }
    private val domainSelection = com.dsha.sidecar.net.dns.DnsProviderSelection(config.catalog,
        nat64Domains = NativeRuntimeConfig.objectAt(config.dns, "nat64")["domains"])
    private val selected = ConcurrentHashMap<NativeEchRouteKey, Entry>()
    private val pending = ConcurrentHashMap.newKeySet<java.util.concurrent.CompletableFuture<NativeEchResult?>>()
    @Volatile private var closed = false

    override fun requireConfiguration(route: NativeRuntimeEchRoute) {
        if (closed) throw IOException("ECH resolver is closed")
        val host = route.innerHost ?: throw IOException("Required ECH has no inner hostname")
        val key = NativeEchRouteKey(host, route.verifyHost, route.port)
        selected[key]?.takeIf { it.expires > clock.nowMillis() }?.let { return }
        val query = if (domainSelection.isNat64Domain(host)) nat64.resolve(host) else resolver.resolve(host)
        pending.add(query)
        try {
            if (closed) throw IOException("ECH resolver is closed")
            val result = query.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                ?: throw IOException("Required ECH configuration is unavailable")
            if (closed) throw IOException("ECH resolver is closed")
            if (selected.size >= 512) {
                selected.entries.removeIf { it.value.expires <= clock.nowMillis() }
                if (selected.size >= 512) selected.keys.firstOrNull()?.let(selected::remove)
            }
            selected[key] = Entry(TypedEchConfigList.parse(result.echConfigList), result.expiresAtMillis)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("ECH lookup interrupted", failure)
        } catch (failure: java.util.concurrent.TimeoutException) {
            throw java.net.SocketTimeoutException("ECH lookup deadline").apply { initCause(failure) }
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw IOException("ECH configuration query failed", failure.cause)
        } finally {
            pending.remove(query)
            if (!query.isDone) query.cancel(true)
        }
    }

    override fun resolve(key: NativeEchRouteKey): NativeEchSelection {
        val entry = selected[key]
        if (entry != null && entry.expires > clock.nowMillis()) return NativeEchSelection.Required(entry.list)
        return NativeEchSelection.NotRequired // Dialer independently rejects echRequired with no prepared config.
    }

    override fun close() {
        closed = true
        for (query in pending) query.cancel(true)
        resolver.close()
        nat64.close()
        selected.clear()
    }
}
