package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.native.NativeDnsResolver
import com.dsha.sidecar.net.dns.native.NativeDnsResult
import com.dsha.sidecar.net.dns.native.NativeDnsStatus
import com.dsha.sidecar.net.proxy.native.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun cancelPendingQueries(queries: Iterable<java.util.concurrent.CompletableFuture<*>>) {
    // Concurrent completion can invalidate the size shortcut used by toList().
    for (query in queries) query.cancel(true)
}

/** This is a selection, not a successful connection. Do not report success at resolve time. */
class NativeRuntimeRouteSelection internal constructor(
    val route: NativeProxyRoute,
    val dnsResult: NativeDnsResult,
    internal val owner: Any,
) {
    internal val reported = AtomicBoolean()
    internal val connected = AtomicBoolean()
}

enum class NativeRuntimeSocketOutcome { CONNECTED, CONNECT_FAILED }

/**
 * Synchronous bridge only on bounded engine workers. The native DNS transport remains asynchronous.
 * Hostname fallback remains explicit failure. Real TCP feedback affects later exchanges only.
 */
class NativeRuntimeRouteResolver(
    private val dns: NativeDnsResolver,
    private val dnsTimeoutMillis: Int,
    private val allowSystemDnsFallback: Boolean = false,
) : RouteResolver {
    private val identity = Any()
    private val selections = AtomicLong()
    private val feedbackReports = AtomicLong()
    private val closed = AtomicBoolean()
    private data class Prepared(val host: String, val port: Int, val tls: NativeTlsPolicy?)
    private val prepared = ThreadLocal<Prepared>()
    private val latest = ThreadLocal<NativeRuntimeRouteSelection>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.CompletableFuture<NativeDnsResult>>()
    private val systemQueries = java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
        TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
        java.util.concurrent.ThreadFactory { Thread(it, "native-platform-dns").apply { isDaemon = true } })

    init { require(dnsTimeoutMillis > 0) }

    data class State(val selections: Long, val feedbackReports: Long, val pending: Int, val closed: Boolean)
    fun state(): State = State(selections.get(), feedbackReports.get(), pending.size, closed.get())

    internal fun prepare(host: String, port: Int, tls: NativeTlsPolicy?) {
        val canonical = NativeAuthority.parse(NativeAuthority(host, port).wire, port).host
        prepared.set(Prepared(canonical, port, tls))
    }
    internal fun clearPrepared() { prepared.remove() }

    override fun resolve(request: NativeProxyRequest): NativeProxyRoute {
        val selected = prepared.get()
        prepared.remove()
        if (selected != null) {
            check(selected.host == request.authority.host && selected.port == request.authority.port) {
                "Prepared rule route does not match the revalidated engine authority"
            }
            return select(selected.host, selected.port, selected.tls).route
        }
        check(request.head.method == "CONNECT") { "Missing prepared runtime route" }
        return select(request.authority.host, request.authority.port,
            if (request.secure) NativeTlsPolicy(request.authority.host) else null).route
    }

    fun select(host: String, port: Int, tls: NativeTlsPolicy?, excluded: Set<String> = emptySet()): NativeRuntimeRouteSelection {
        if (closed.get()) throw IOException("Runtime routes are closed")
        val start = System.nanoTime()
        val future = dns.resolve(host, destinationPort = port)
        pending.add(future)
        try {
            if (closed.get()) { future.cancel(true); throw IOException("Runtime routes are closed") }
            val remaining = TimeUnit.MILLISECONDS.toNanos(dnsTimeoutMillis.toLong()) - (System.nanoTime() - start)
            if (remaining <= 0) throw SocketTimeoutException("Runtime DNS deadline")
            var result = future.get(remaining, TimeUnit.NANOSECONDS)
            if (closed.get()) throw IOException("Runtime routes are closed")
            if (result.status == NativeDnsStatus.NO_PROVIDER && allowSystemDnsFallback) {
                val query = systemQueries.submit<List<String>> {
                    InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
                }
                try {
                    val left = TimeUnit.MILLISECONDS.toNanos(dnsTimeoutMillis.toLong()) - (System.nanoTime() - start)
                    if (left <= 0) throw SocketTimeoutException("Platform DNS deadline")
                    val addresses = query.get(left, TimeUnit.NANOSECONDS)
                    result = NativeDnsResult(host, null, result.family, port, NativeDnsStatus.CANDIDATES,
                        addresses.firstOrNull(), addresses, addresses, false)
                } finally { if (!query.isDone) query.cancel(true) }
            }
            val literal = (listOfNotNull(result.selectedIp) + result.candidateOrder + result.addresses)
                .firstOrNull { it !in excluded && IpAddress.isIpLiteral(it) }
                ?: throw IOException("DNS ${result.status}: no untried literal candidate")
            if (!IpAddress.isIpLiteral(literal)) throw IOException("DNS selected a nonliteral destination")
            if (result.selectedIp != literal) result = NativeDnsResult(result.hostname, result.providerKey,
                result.family, result.destinationPort, result.status, literal, result.candidateOrder,
                result.addresses, result.cached, result.entryIdentity)
            // Guarded literal parsing never sends the hostname through JVM system DNS.
            val address = InetAddress.getByName(literal)
            selections.incrementAndGet()
            lateinit var selection: NativeRuntimeRouteSelection
            selection = NativeRuntimeRouteSelection(NativeProxyRoute(InetSocketAddress(address, port), tls = tls,
                socketOutcome = { outcome ->
                    if (outcome == NativeSocketOutcome.CONNECTED) selection.connected.set(true)
                    feedback(selection, if (outcome == NativeSocketOutcome.CONNECTED)
                        NativeRuntimeSocketOutcome.CONNECTED else NativeRuntimeSocketOutcome.CONNECT_FAILED)
                }),
                result, identity)
            latest.set(selection)
            return selection
        } catch (_: TimeoutException) {
            throw SocketTimeoutException("Runtime DNS deadline")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is SocketTimeoutException) throw cause
            throw IOException("Runtime DNS query failed", cause)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Runtime DNS wait interrupted", failure)
        } finally {
            pending.remove(future)
            if (!future.isDone) future.cancel(true)
        }
    }

    /** Engine connect outcomes and explicit callers share the same single-use DNS selection ticket. */
    fun feedback(selection: NativeRuntimeRouteSelection, outcome: NativeRuntimeSocketOutcome): Boolean {
        require(selection.owner === identity) { "Selection belongs to another runtime" }
        if (closed.get() || !selection.reported.compareAndSet(false, true)) return false
        feedbackReports.incrementAndGet()
        return dns.feedback(selection.dnsResult, outcome == NativeRuntimeSocketOutcome.CONNECTED)
    }

    /** A TCP handshake can succeed while TLS/HTTP subsequently fails. Record that distinct outcome. */
    internal fun requestFailed(route: NativeProxyRoute) {
        val selection = latest.get() ?: return
        if (selection.route.destination == route.destination && selection.connected.compareAndSet(true, false) && !closed.get()) {
            dns.feedback(selection.dnsResult, false)
        }
    }

    internal fun close() {
        closed.set(true)
        cancelPendingQueries(pending)
        systemQueries.shutdownNow()
    }
}
