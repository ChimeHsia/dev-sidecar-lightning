package com.dsha.sidecar.net.proxy.tls

import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Bounded, access-order LRU bound permanently to one CA instance. At most [maxSize] distinct
 * hosts may be generating concurrently; callers beyond that limit fail instead of growing memory.
 */
class LeafCertificateCache internal constructor(
    private val authority: MitmCertificateAuthority,
    val maxSize: Int,
    private val renewBefore: Duration,
    private val generate: (String) -> LeafCertificate,
) {
    @JvmOverloads
    constructor(
        authority: MitmCertificateAuthority,
        maxSize: Int = 256,
        renewBefore: Duration = Duration.ofMinutes(1),
    ) : this(authority, maxSize, renewBefore, authority::issue)

    init {
        require(maxSize > 0) { "Cache size must be positive" }
        require(!renewBefore.isNegative && renewBefore <= Duration.ofDays(7)) { "Invalid renewal window" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, LeafCertificate>(16, 0.75f, true)
    private val inFlight = HashMap<String, CompletableFuture<LeafCertificate>>()
    private var epoch = 0L

    val size: Int get() = synchronized(lock) { entries.size }

    fun get(host: String): LeafCertificate {
        val canonical = CertificateHost.parse(host).canonicalName
        val future: CompletableFuture<LeafCertificate>
        val startedEpoch: Long
        val owner: Boolean
        synchronized(lock) {
            authority.checkValidity()
            entries[canonical]?.let {
                if (it.isUsableAt(authority.now(), renewBefore)) return it
                entries.remove(canonical)
            }
            val pending = inFlight[canonical]
            startedEpoch = epoch
            if (pending != null) {
                future = pending
                owner = false
            } else {
                check(inFlight.size < maxSize) { "Certificate generation capacity exhausted" }
                future = CompletableFuture()
                inFlight[canonical] = future
                owner = true
            }
        }
        // The owner generates outside the monitor; other callers wait on this same future.
        return if (owner) generateAndPublish(canonical, future, startedEpoch) else await(future)
    }

    private fun generateAndPublish(
        host: String,
        future: CompletableFuture<LeafCertificate>,
        startedEpoch: Long,
    ): LeafCertificate {
        try {
            val leaf = generate(host)
            synchronized(lock) {
                authority.checkValidity()
                check(leaf.authorityIdentity == authority.identity && leaf.host == host &&
                    leaf.isUsableAt(authority.now())) { "Generated leaf does not match cache authority, host, or time" }
                if (epoch == startedEpoch && leaf.isUsableAt(authority.now(), renewBefore)) {
                    entries[host] = leaf
                    while (entries.size > maxSize) entries.remove(entries.keys.first())
                }
                inFlight.remove(host)
                future.complete(leaf)
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                inFlight.remove(host)
                future.completeExceptionally(failure)
            }
        }
        return await(future)
    }

    private fun await(future: CompletableFuture<LeafCertificate>): LeafCertificate {
        val leaf = try {
            future.get()
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        authority.checkValidity()
        check(leaf.isUsableAt(authority.now())) { "Leaf expired while waiting for generation" }
        return leaf
    }

    /** Does not cancel existing callers; generation started before clear cannot repopulate the LRU. */
    fun clear() = synchronized(lock) {
        epoch++
        entries.clear()
    }
}
