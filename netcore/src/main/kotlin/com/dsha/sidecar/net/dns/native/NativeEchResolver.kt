// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderCatalog
import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.DnsProviderJson
import com.dsha.sidecar.net.dns.DnsProviderSelection
import com.dsha.sidecar.net.dns.EchConfigList
import com.dsha.sidecar.net.dns.EchConfigListResult
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** One immutable provider/options epoch, bounded LRU and cancellable hedged lookups. */
class NativeEchResolver(
    catalog: DnsProviderCatalog,
    private val transport: HttpsRecordTransport,
    private val clock: NativeDnsClock,
    private val options: NativeEchOptions = NativeEchOptions(),
    mapping: Map<String, Any?> = emptyMap(),
    private val limits: NativeEchLimits = NativeEchLimits(),
    private val ownsTransport: Boolean = true,
) : AutoCloseable {
    private class Attempt(val provider: DnsProviderDescriptor, val root: String) {
        val visited = linkedSetOf(root)
        var depth = 0
        var ttl = Long.MAX_VALUE
        var future: CompletableFuture<HttpsRecordAnswer>? = null
        var scheduled: ScheduledFuture<*>? = null
        var finished = false
    }
    private class Flight(val host: String) {
        val waiters = linkedSetOf<CompletableFuture<NativeEchResult?>>()
        val dependencies = linkedSetOf(host)
        val providers = linkedSetOf<String>()
        var attempts = emptyList<Attempt>()
        var errors = mutableListOf<Throwable>()
        var shared = false
        var timer: ScheduledFuture<*>? = null
    }
    private data class Entry(
        val result: NativeEchResult?,
        val expires: Long,
        val dependencies: Set<String>,
        val providers: Set<String>,
    )
    data class State(val entries: Int, val flights: Int, val waiters: Int, val closed: Boolean)

    private val lock = Any()
    private val bindings = catalog.bindings.toMap()
    private val descriptors = catalog.descriptors.map {
        it.copy(echOptions = it.echOptions?.let(DnsProviderJson::copyObject),
            nat64Options = it.nat64Options?.let(DnsProviderJson::copyObject))
    }.associateBy { it.key }
    private val selection = DnsProviderSelection(catalog, mapping)
    private val cache = LinkedHashMap<String, Entry>(16, .75f, true)
    private val flights = mutableMapOf<String, Flight>()
    private var closed = false
    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "native-ech-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }

    init {
        require(descriptors.size == catalog.descriptors.size) { "Duplicate ECH provider keys" }
        options.dns?.takeIf { it.isNotEmpty() }?.let { require(bindings[it] != null) { "Unknown ECH DNS provider: $it" } }
    }

    fun resolve(hostname: String): CompletableFuture<NativeEchResult?> {
        val host = NativeDnsWire.name(hostname.trim())
        val waiter = CompletableFuture<NativeEchResult?>()
        val flight: Flight
        var start = false
        synchronized(lock) {
            if (closed) return failed(NativeDnsClosedException())
            if (!options.enabled || !options.use) return CompletableFuture.completedFuture(null)
            prune()
            cache[host]?.let { return CompletableFuture.completedFuture(it.result) }
            flight = flights[host] ?: run {
                if (flights.size >= limits.maxFlights) return failed(NativeDnsCapacityException())
                start = true
                Flight(host).also { flights[host] = it }
            }
            if (flight.waiters.size >= limits.maxWaiters) return failed(NativeDnsCapacityException())
            flight.waiters.add(waiter)
        }
        waiter.whenComplete { _, _ -> detach(flight, waiter) }
        if (start) {
            val timer = try {
                scheduler.schedule({
                    complete(flight, error = SocketTimeoutException("ECH lookup deadline exceeded"))
                }, limits.lookupTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                complete(flight, error = NativeDnsClosedException())
                return waiter
            }
            synchronized(lock) {
                if (live(flight)) flight.timer = timer else timer.cancel(false)
            }
            stage(flight, host, false)
        }
        return waiter
    }

    /** Invalidating a name removes its direct/shared/CNAME/AliasMode dependents and pending work. */
    fun invalidate(hostname: String? = null, providerKey: String? = null) {
        val host = hostname?.let { NativeDnsWire.name(it.trim()) }
        val removed: List<Flight>
        synchronized(lock) {
            cache.entries.removeAll { (_, entry) ->
                (host == null || host in entry.dependencies) && (providerKey == null || providerKey in entry.providers)
            }
            removed = flights.values.filter {
                (host == null || host in it.dependencies) && (providerKey == null || providerKey in it.providers)
            }
            removed.forEach { flights.remove(it.host) }
        }
        removed.forEach { abort(it, NativeDnsInvalidatedException()) }
    }

    fun state(): State = synchronized(lock) {
        prune()
        State(cache.size, flights.size, flights.values.sumOf { it.waiters.size }, closed)
    }

    override fun close() {
        val removed = synchronized(lock) {
            if (closed) return
            closed = true
            cache.clear()
            flights.values.toList().also { flights.clear() }
        }
        removed.forEach { abort(it, NativeDnsClosedException()) }
        scheduler.shutdownNow()
        if (ownsTransport) transport.close()
    }

    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        return scheduler.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun candidates(host: String): List<DnsProviderDescriptor> {
        fun allowed(provider: DnsProviderDescriptor) = provider.transport in listOf("udp", "tcp", "tls", "https") &&
            provider.echOptions?.get("enabled") != false
        val dedicated = options.dns?.takeIf { it.isNotEmpty() }
        if (dedicated != null) return listOfNotNull(descriptors[bindings[dedicated]]).filter(::allowed)
        val keys = mutableListOf<String>()
        selection.select(host)?.providerKey?.let(keys::add)
        if (options.tryAllProviders) {
            bindings["ForSNI"]?.let(keys::add)
            keys.addAll(bindings.values)
        }
        val result = keys.distinct().mapNotNull(descriptors::get).filter(::allowed)
        require(result.size <= limits.maxProviders) { "ECH provider count exceeds limit" }
        return result
    }

    private fun stage(flight: Flight, hostname: String, shared: Boolean) {
        val attempts = try {
            candidates(hostname).map { Attempt(it, hostname) }
        } catch (error: Exception) {
            complete(flight, error = error)
            return
        }
        synchronized(lock) {
            if (!live(flight)) return
            flight.shared = shared
            flight.dependencies.add(hostname)
            flight.attempts = attempts
            flight.errors = mutableListOf()
            flight.providers.addAll(attempts.map { it.provider.key })
        }
        if (attempts.isEmpty()) {
            nextStage(flight, attempts)
            return
        }
        for ((index, attempt) in attempts.withIndex()) {
            synchronized(lock) { if (!live(flight) || attempt !in flight.attempts) return }
            if (index == 0 || options.parallelDelayMillis == 0L) {
                launch(flight, attempt, hostname)
            } else {
                val scheduled = try {
                    scheduler.schedule({ launch(flight, attempt, hostname) },
                        options.parallelDelayMillis, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    complete(flight, error = NativeDnsClosedException())
                    return
                }
                synchronized(lock) {
                    if (live(flight) && attempt in flight.attempts) attempt.scheduled = scheduled
                    else scheduled.cancel(false)
                }
            }
        }
    }

    private fun launch(flight: Flight, attempt: Attempt, hostname: String) {
        synchronized(lock) { if (!live(flight) || attempt.finished || attempt !in flight.attempts) return }
        val future = try { transport.queryHttps(HttpsRecordQuery(attempt.provider, hostname)) }
        catch (error: Exception) { attemptDone(flight, attempt, error); return }
        synchronized(lock) {
            if (!live(flight) || attempt.finished || attempt !in flight.attempts) {
                future.cancel(true)
                return
            }
            attempt.future = future
        }
        future.whenComplete { answer, error ->
            synchronized(lock) { if (!live(flight) || attempt.finished || attempt !in flight.attempts) return@whenComplete }
            if (error != null) { attemptDone(flight, attempt, error); return@whenComplete }
            try { consume(flight, attempt, hostname, requireNotNull(answer)) }
            catch (invalid: Exception) { attemptDone(flight, attempt, invalid) }
        }
    }

    private fun consume(flight: Flight, attempt: Attempt, hostname: String, answer: HttpsRecordAnswer) {
        require(answer.records.size <= limits.maxRecords && answer.negativeTtlMillis >= 0) { "Invalid HTTPS answer bounds" }
        require(answer.code == NativeDnsAnswerCode.ANSWER || answer.records.isEmpty()) { "Negative HTTPS records" }
        require(answer.canonicalNames.size <= 32) { "Too many canonical names" }
        val dependencies = answer.canonicalNames.map(NativeDnsWire::name)
        require(dependencies.distinct().size == dependencies.size && hostname !in dependencies) {
            "Invalid canonical-name chain"
        }
        synchronized(lock) {
            if (!live(flight)) return
            flight.dependencies.addAll(dependencies)
        }
        val records = answer.records
        val expectedOwner = dependencies.lastOrNull() ?: hostname
        records.forEach {
            require(NativeDnsWire.name(it.owner) == expectedOwner && it.ttlMillis >= 0 &&
                it.service.priority in 0..65535 && it.service.params.size <= 1024 &&
                it.service.params.sumOf { param -> param.raw.size.toLong() + 4 } <= 65535
            ) { "Invalid HTTPS record owner/bounds" }
            if (it.service.target != ".") NativeDnsWire.name(it.service.target)
        }
        val alias = records.filter { it.service.priority == 0 }
        if (alias.isNotEmpty()) {
            require(alias.size == 1 && records.size == 1 && alias.single().service.params.isEmpty()) {
                "Ambiguous/malformed HTTPS AliasMode"
            }
            val record = alias.single()
            val target = record.service.target
            if (target == ".") { attemptDone(flight, attempt); return }
            val next = NativeDnsWire.name(target)
            synchronized(lock) {
                if (!live(flight)) return
                if (attempt.depth >= limits.maxAliasDepth || !attempt.visited.add(next)) {
                    throw NativeEchConfigException("HTTPS AliasMode cycle/limit")
                }
                attempt.depth++
                attempt.ttl = minOf(attempt.ttl, record.ttlMillis)
                flight.dependencies.add(next)
            }
            launch(flight, attempt, next)
            return
        }
        var invalid: Exception? = null
        for (record in records.filter { it.service.priority > 0 }.sortedBy { it.service.priority }) {
            val service = record.service
            val keys = service.params.map { it.key }
            require(keys.zipWithNext().all { (a, b) -> a < b }) { "Invalid service parameter order" }
            val mandatory = service.params.firstOrNull { it.key == 0 }?.raw
            if (mandatory != null) {
                if (mandatory.size % 2 != 0) throw NativeEchConfigException("Malformed mandatory keys")
                val required = mandatory.indices.step(2).map {
                    ((mandatory[it].toInt() and 255) shl 8) or (mandatory[it + 1].toInt() and 255)
                }
                require(required.isNotEmpty() && required.zipWithNext().all { (a, b) -> a < b }) {
                    "Invalid mandatory service key order"
                }
                if (required.any { it == 0 || it !in keys || it !in setOf(1, 2, 3, 4, 5, 6) }) continue
            }
            val raw = service.params.firstOrNull { it.key == 5 }?.raw ?: continue
            if (raw.size !in 2..limits.maxConfigBytes) {
                invalid = NativeEchConfigException("Invalid ECHConfigList size")
                continue
            }
            val parsed = EchConfigList.parseEchConfigList(raw)
            if (parsed !is EchConfigListResult.Success) {
                invalid = NativeEchConfigException("Malformed ECHConfigList: ${(parsed as EchConfigListResult.Failure).error}")
                continue
            }
            val selected = EchConfigList.selectEchConfig(parsed.configs)
            if (selected == null) {
                invalid = NativeEchConfigException("No supported ECHConfigList profile")
                continue
            }
            var ttl = minOf(attempt.ttl, record.ttlMillis).coerceIn(options.minTtlMillis, options.maxTtlMillis)
            if (flight.shared) ttl = minOf(ttl, options.emptyTtlMillis)
            val now = clock.nowMillis()
            val result = NativeEchResult(
                flight.host, hostname, attempt.provider.key, attempt.provider.name, attempt.provider.transport,
                raw, parsed.configs, selected, ttl, expires(now, ttl), flight.shared, service.priority, service.target,
            )
            complete(flight, result)
            return
        }
        attemptDone(flight, attempt, invalid)
    }

    private fun attemptDone(flight: Flight, attempt: Attempt, error: Throwable? = null) {
        val finished = synchronized(lock) {
            if (!live(flight) || attempt.finished || attempt !in flight.attempts) return
            attempt.finished = true
            attempt.future = null
            error?.let { flight.errors.add(it) }
            flight.attempts.all { it.finished }
        }
        if (finished) nextStage(flight, flight.attempts)
    }

    private fun nextStage(flight: Flight, previous: List<Attempt>) {
        val shared: String?
        val errors: List<Throwable>
        synchronized(lock) {
            if (!live(flight) || flight.attempts !== previous) return
            errors = flight.errors.toList()
            shared = options.publicName?.takeIf { it.isNotEmpty() }?.let(NativeDnsWire::name)
                ?.takeIf { !flight.shared && it != flight.host }
        }
        when {
            errors.isNotEmpty() -> complete(flight, error = NativeEchLookupException(errors))
            shared != null -> stage(flight, shared, true)
            else -> complete(flight)
        }
    }

    private fun complete(flight: Flight, result: NativeEchResult? = null, error: Throwable? = null) {
        val waiters: List<CompletableFuture<NativeEchResult?>>
        synchronized(lock) {
            if (!live(flight)) return
            flights.remove(flight.host)
            waiters = flight.waiters.toList()
            flight.waiters.clear()
            if (error == null) {
                val expiry = result?.expiresAtMillis ?: expires(clock.nowMillis(), options.emptyTtlMillis)
                if (expiry > clock.nowMillis()) {
                    prune()
                    cache[flight.host] = Entry(result, expiry, flight.dependencies.toSet(), flight.providers.toSet())
                    while (cache.size > limits.maxEntries) cache.remove(cache.keys.first())
                }
            }
        }
        cancelWork(flight)
        waiters.forEach { if (error == null) it.complete(result) else it.completeExceptionally(error) }
    }

    private fun detach(flight: Flight, waiter: CompletableFuture<NativeEchResult?>) {
        val last = synchronized(lock) {
            if (!live(flight)) return
            flight.waiters.remove(waiter)
            if (flight.waiters.isEmpty()) { flights.remove(flight.host); true } else false
        }
        if (last) cancelWork(flight)
    }

    private fun abort(flight: Flight, error: Throwable) {
        cancelWork(flight)
        val waiters = synchronized(lock) { flight.waiters.toList().also { flight.waiters.clear() } }
        waiters.forEach { it.completeExceptionally(error) }
    }

    private fun cancelWork(flight: Flight) {
        val work = synchronized(lock) {
            Triple(flight.timer, flight.attempts.mapNotNull { it.future }, flight.attempts.mapNotNull { it.scheduled })
        }
        work.first?.cancel(false)
        work.third.forEach { it.cancel(false) }
        work.second.forEach { it.cancel(true) }
    }

    private fun live(flight: Flight) = !closed && flights[flight.host] === flight
    private fun prune() { cache.values.removeAll { it.expires <= clock.nowMillis() } }
    private fun expires(now: Long, ttl: Long): Long {
        require(now >= 0)
        return if (now > Long.MAX_VALUE - ttl) Long.MAX_VALUE else now + ttl
    }
    private fun <T> failed(error: Throwable) = CompletableFuture<T>().apply { completeExceptionally(error) }
}
