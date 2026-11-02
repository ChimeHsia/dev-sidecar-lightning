package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.native.NativeDnsResolver
import com.dsha.sidecar.net.dns.native.NativeDnsResult
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
}

enum class NativeRuntimeSocketOutcome { CONNECTED, CONNECT_FAILED }

/**
 * Synchronous bridge only on bounded engine workers. The native DNS transport remains asynchronous.
 * All hostname fallback is explicit failure until a bounded fallback/attempt observer is integrated.
 */
class NativeRuntimeRouteResolver(
    private val dns: NativeDnsResolver,
    private val dnsTimeoutMillis: Int,
) : RouteResolver {
    private val identity = Any()
    private val selections = AtomicLong()
    private val feedbackReports = AtomicLong()
    private val closed = AtomicBoolean()
    private data class Prepared(val host: String, val port: Int, val tls: NativeTlsPolicy?)
    private val prepared = ThreadLocal<Prepared>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.CompletableFuture<NativeDnsResult>>()

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

    fun select(host: String, port: Int, tls: NativeTlsPolicy?): NativeRuntimeRouteSelection {
        if (closed.get()) throw IOException("Runtime routes are closed")
        val start = System.nanoTime()
        val future = dns.resolve(host, destinationPort = port)
        pending.add(future)
        try {
            if (closed.get()) { future.cancel(true); throw IOException("Runtime routes are closed") }
            val remaining = TimeUnit.MILLISECONDS.toNanos(dnsTimeoutMillis.toLong()) - (System.nanoTime() - start)
            if (remaining <= 0) throw SocketTimeoutException("Runtime DNS deadline")
            val result = future.get(remaining, TimeUnit.NANOSECONDS)
            if (closed.get()) throw IOException("Runtime routes are closed")
            val literal = result.selectedIp
                ?: throw IOException("DNS ${result.status}: no literal candidate; hostname fallback is not silently used")
            if (!IpAddress.isIpLiteral(literal)) throw IOException("DNS selected a nonliteral destination")
            // Guarded literal parsing never sends the hostname through JVM system DNS.
            val address = InetAddress.getByName(literal)
            selections.incrementAndGet()
            return NativeRuntimeRouteSelection(NativeProxyRoute(InetSocketAddress(address, port), tls = tls),
                result, identity)
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

    /** For a future engine observer ONLY, after a real TCP outcome. The current engine never calls it. */
    fun feedback(selection: NativeRuntimeRouteSelection, outcome: NativeRuntimeSocketOutcome): Boolean {
        require(selection.owner === identity) { "Selection belongs to another runtime" }
        if (closed.get() || !selection.reported.compareAndSet(false, true)) return false
        feedbackReports.incrementAndGet()
        return dns.feedback(selection.dnsResult, outcome == NativeRuntimeSocketOutcome.CONNECTED)
    }

    internal fun close() {
        closed.set(true)
        cancelPendingQueries(pending)
    }
}
