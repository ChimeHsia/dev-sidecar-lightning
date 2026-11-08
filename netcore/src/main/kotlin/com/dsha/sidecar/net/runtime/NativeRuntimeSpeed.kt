package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.dns.native.*
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.*

/** Bounded TCP observations rank future DNS answers; request-local failures still take precedence. */
internal class NativeRuntimeSpeed(config: NativeRuntimeConfig) : NativeDnsCandidateOrder, Closeable {
    private data class Key(val host: String, val port: Int, val ip: String)
    private data class Score(val elapsed: Long, val measured: Long)
    private val raw = NativeRuntimeConfig.objectAt(config.dns, "speedTest")
    private val scores = ConcurrentHashMap<Key, Score>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val queries = ConcurrentHashMap.newKeySet<CompletableFuture<NativeDnsResult>>()
    private val executor = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64),
        ThreadFactory { Thread(it, "native-ip-probe").apply { isDaemon = true } })
    private val timer = ScheduledThreadPoolExecutor(1) {
        Thread(it, "native-ip-monitor").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile private var closed = false

    override fun order(query: NativeDnsQuery, addresses: List<String>): List<String> =
        order(query, addresses, 443)

    override fun order(query: NativeDnsQuery, addresses: List<String>, destinationPort: Int): List<String> {
        val now = System.nanoTime()
        return addresses.sortedBy { ip ->
            scores[Key(query.hostname, destinationPort, ip)]?.takeIf {
                now - it.measured < TimeUnit.MINUTES.toNanos(2)
            }?.elapsed ?: Long.MAX_VALUE / 2
        }
    }

    fun start(resolver: NativeDnsResolver) {
        if (raw["enabled"] != true) return
        val hosts = (raw["hostnameList"] as? List<*>)?.filterIsInstance<String>()?.take(32).orEmpty()
        val interval = ((raw["interval"] as? Number)?.toLong() ?: 60_000).coerceIn(5_000, 3_600_000)
        timer.scheduleWithFixedDelay({
            if (!closed) for (host in hosts) {
                val query = resolver.resolve(host, destinationPort = 443)
                queries.add(query)
                query.whenComplete { result, _ ->
                    queries.remove(query)
                    if (!closed && result != null) for (ip in result.addresses.take(16)) {
                        try { executor.execute { probe(host, 443, ip) } } catch (_: RejectedExecutionException) { }
                    }
                }
            }
            val expiry = System.nanoTime() - TimeUnit.MINUTES.toNanos(2)
            scores.entries.removeIf { it.value.measured < expiry }
        }, 0, interval, TimeUnit.MILLISECONDS)
    }

    private fun probe(host: String, port: Int, ip: String) {
        if (closed) return
        val socket = Socket()
        sockets.add(socket)
        val start = System.nanoTime()
        val elapsed = try {
            if (closed) return
            socket.connect(InetSocketAddress(ip, port), 1_500)
            System.nanoTime() - start
        } catch (_: Exception) { Long.MAX_VALUE } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
        if (!closed && scores.size < 1024) scores[Key(host, port, ip)] = Score(elapsed, System.nanoTime())
    }

    override fun close() {
        closed = true
        timer.shutdownNow()
        for (query in queries) query.cancel(true)
        for (socket in sockets) runCatching { socket.close() }
        executor.shutdownNow()
        scores.clear()
    }
}
