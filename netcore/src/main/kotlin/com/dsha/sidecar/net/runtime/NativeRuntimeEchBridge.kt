package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.dns.native.NativeDnsClock
import com.dsha.sidecar.net.dns.native.NativeEchResult
import com.dsha.sidecar.net.dns.native.NativeDnsWire
import com.dsha.sidecar.net.proxy.ech.TypedEchConfigList
import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.proxy.tls.CertificateHost
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Paired preflight/dialer for one immutable runtime epoch. The engine calls both
 * on the same bounded worker. DNS remains asynchronous until the preflight waits.
 * The caller owns the injected resolver and closes this bridge with its runtime.
 */
class NativeRuntimeEchBridge(
    private val lookup: (String) -> CompletableFuture<NativeEchResult?>,
    private val clock: NativeDnsClock,
    private val lookupTimeoutMillis: Long = 8_000,
    maxConcurrentLookups: Int = 16,
    private val trust: NativeEchTrustSupplier = NativeEchTrustSupplier {
        com.dsha.sidecar.net.proxy.ech.EchPeerTrust.SystemRoots
    },
) : NativeRuntimeEchRequirement, NativeTlsDialer, Closeable {
    private data class Prepared(
        val key: NativeEchRouteKey,
        val config: TypedEchConfigList,
        val expiresAtMillis: Long,
    )
    private val prepared = ThreadLocal<Prepared>()
    private val closed = AtomicBoolean()
    private val permits = Semaphore(maxConcurrentLookups)
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<NativeEchResult?>>()
    private val ordinary = NativeTlsConnector()

    init {
        require(lookupTimeoutMillis in 1..120_000)
        require(maxConcurrentLookups in 1..256)
    }

    override fun requireConfiguration(route: NativeRuntimeEchRoute) {
        prepared.remove()
        if (closed.get()) throw IOException("ECH bridge is closed")
        val host = NativeDnsWire.name(route.innerHost ?: throw IOException("Required ECH has no inner host"))
        require(route.port in 1..65535)
        if (!permits.tryAcquire()) throw IOException("ECH preflight capacity exceeded")
        var future: CompletableFuture<NativeEchResult?>? = null
        try {
            future = lookup(host)
            pending.add(future)
            if (closed.get()) throw IOException("ECH bridge is closed")
            val result = future.get(lookupTimeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw IOException("Required ECH configuration is unavailable")
            if (closed.get() || result.hostname != host || result.expiresAtMillis <= clock.nowMillis()) {
                throw IOException("Required ECH configuration expired or bridge closed")
            }
            prepared.set(Prepared(NativeEchRouteKey(host, CertificateHost.parse(route.verifyHost).canonicalName, route.port),
                TypedEchConfigList.parse(result.echConfigList), result.expiresAtMillis))
        } catch (_: TimeoutException) {
            throw SocketTimeoutException("ECH configuration deadline")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("ECH configuration wait interrupted", failure)
        } catch (failure: ExecutionException) {
            throw IOException("ECH configuration lookup failed", failure.cause)
        } finally {
            future?.let {
                pending.remove(it)
                if (!it.isDone) it.cancel(true)
            }
            permits.release()
        }
    }

    override fun connect(raw: Socket, policy: NativeTlsPolicy, handshakeTimeoutMillis: Int): SSLSocket {
        val selected = prepared.get()
        prepared.remove()
        try {
            if (closed.get()) throw IOException("ECH bridge is closed")
            if (!policy.echRequired) {
                if (selected != null) throw IOException("Prepared ECH route changed before dialing")
                return ordinary.connect(raw, policy, handshakeTimeoutMillis)
            }
            val key = NativeEchRouteKey(policy.sniHost?.let(NativeDnsWire::name),
                CertificateHost.parse(policy.verifyHost).canonicalName, raw.port)
            if (selected == null || selected.key != key || selected.expiresAtMillis <= clock.nowMillis()) {
                throw IOException("Required ECH preflight is absent, expired or belongs to another route")
            }
            return ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.Required(selected.config) }, trust,
            ).connect(raw, policy, handshakeTimeoutMillis)
        } catch (failure: Exception) {
            runCatching { raw.close() }
            throw failure
        }
    }

    override fun clearPreflight() { prepared.remove() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        prepared.remove()
        cancelPendingQueries(pending)
    }
}
