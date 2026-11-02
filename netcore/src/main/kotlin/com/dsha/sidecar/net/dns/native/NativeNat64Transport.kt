// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.Nat64Address
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native NAT64 dispatch; ordinary queries delegate unchanged to the supplied socket transport. */
class NativeNat64Transport(
    private val underlying: SocketDnsTransport,
    private val clock: NativeDnsClock,
    private val dialer: NativeNat64DohDialer = underlying,
    private val limits: NativeNat64Limits = NativeNat64Limits(),
    private val ownsUnderlying: Boolean = true,
) : DnsTransport, HttpsRecordTransport {
    private data class Key(val provider: String, val hostname: String, val type: NativeNat64Question)
    private class Attempt(val route: NativeNat64Config.Route) {
        var future: CompletableFuture<NativeNat64DohAnswer>? = null
        var timer: ScheduledFuture<*>? = null
        var done = false
    }
    private class Flight(val key: Key, val plan: NativeNat64Config.Plan, val attempts: List<Attempt>) {
        val waiters = linkedSetOf<CompletableFuture<NativeNat64DohAnswer>>()
        val failures = mutableListOf<Throwable>()
        var deadline: ScheduledFuture<*>? = null
    }
    private data class Entry(
        val answer: NativeDnsAnswer?,
        val error: Throwable?,
        val storedAt: Long,
        val expiresAt: Long,
    )
    data class State(val plans: Int, val entries: Int, val flights: Int, val waiters: Int, val closed: Boolean)
    private val lock = Any()
    private val plans = linkedMapOf<String, NativeNat64Config.Plan>()
    private val cache = LinkedHashMap<Key, Entry>(16, .75f, true)
    private val flights = mutableMapOf<Key, Flight>()
    private var closed = false
    private val scheduler = ScheduledThreadPoolExecutor(1) {
        Thread(it, "native-nat64-coordinator").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }

    override fun query(query: NativeDnsQuery): CompletableFuture<NativeDnsAnswer> {
        synchronized(lock) { if (closed) return failed(NativeDnsClosedException()) }
        if (query.provider.transport != "nat64") return underlying.query(query)
        if (query.family == NativeDnsFamily.IPV4) {
            return failed(UnsupportedOperationException("NAT64 returns IPv6 destinations; IPv4-only requests are unsupported"))
        }
        return map(request(query.provider, query.hostname, NativeNat64Question.A)) {
            (it as NativeNat64DohAnswer.Addresses).answer
        }
    }

    override fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer> {
        synchronized(lock) { if (closed) return failed(NativeDnsClosedException()) }
        if (query.provider.transport != "nat64") return underlying.queryHttps(query)
        return map(request(query.provider, query.hostname,
            if (query.type == HttpsRecordType.HTTPS) NativeNat64Question.HTTPS else NativeNat64Question.SVCB)) {
            (it as NativeNat64DohAnswer.Services).answer
        }
    }

    fun invalidate(hostname: String? = null, providerKey: String? = null) {
        val host = hostname?.let { NativeDnsWire.name(it.trim()) }
        val removed = synchronized(lock) {
            fun matches(key: Key) = (host == null || key.hostname == host) &&
                (providerKey == null || key.provider == providerKey)
            cache.keys.removeAll(::matches)
            flights.keys.filter(::matches).map { flights.remove(it)!! }
        }
        removed.forEach { release(it, NativeDnsInvalidatedException()) }
    }

    fun state(): State = synchronized(lock) {
        prune()
        State(plans.size, cache.size, flights.size, flights.values.sumOf { it.waiters.size }, closed)
    }

    override fun close() {
        val removed = synchronized(lock) {
            if (closed) return
            closed = true
            cache.clear()
            plans.clear()
            flights.values.toList().also { flights.clear() }
        }
        removed.forEach { release(it, NativeDnsClosedException()) }
        scheduler.shutdownNow()
        if (dialer !== underlying) dialer.close()
        if (ownsUnderlying) underlying.close()
    }

    /** Joins the coordinator; underlying transport has its separate bounded join. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        return scheduler.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun request(
        descriptor: DnsProviderDescriptor, hostname: String, type: NativeNat64Question,
    ): CompletableFuture<NativeNat64DohAnswer> {
        val parsed = try { NativeNat64Config.plan(descriptor, limits) }
        catch (error: Exception) { return failed(error) }
        val host = try { NativeDnsWire.name(hostname.trim()) }
        catch (error: Exception) { return failed(error) }
        val key = Key(descriptor.key, host, type)
        val waiter = CompletableFuture<NativeNat64DohAnswer>()
        val flight: Flight
        var start = false
        synchronized(lock) {
            if (closed) return failed(NativeDnsClosedException())
            val existingPlan = plans[descriptor.key]
            if (existingPlan != null && existingPlan.descriptor != parsed.descriptor) {
                return failed(IllegalArgumentException("NAT64 descriptor changed within a transport epoch"))
            }
            val plan = existingPlan ?: run {
                if (plans.size >= limits.maxPlans) return failed(NativeDnsCapacityException())
                parsed.also { plans[descriptor.key] = it }
            }
            prune()
            if (type == NativeNat64Question.A) cache[key]?.let { entry ->
                entry.error?.let { return failed(it) }
                val answer = requireNotNull(entry.answer)
                val age = (clock.nowMillis() - entry.storedAt).coerceAtLeast(0)
                return CompletableFuture.completedFuture(NativeNat64DohAnswer.Addresses(answer.copy(
                    records = answer.records.map { it.copy(ttlMillis = (it.ttlMillis - age).coerceAtLeast(0)) },
                    negativeTtlMillis = (answer.negativeTtlMillis - age).coerceAtLeast(0),
                )))
            }
            flight = flights[key] ?: run {
                if (flights.size >= limits.maxFlights) return failed(NativeDnsCapacityException())
                val order = plan.routes.sortedBy { if (it.provider.server == plan.lastSuccess) 0 else 1 }
                start = true
                Flight(key, plan, order.map(::Attempt)).also { flights[key] = it }
            }
            if (flight.waiters.size >= limits.maxWaiters) return failed(NativeDnsCapacityException())
            flight.waiters.add(waiter)
        }
        waiter.whenComplete { _, _ -> detach(flight, waiter) }
        if (start) start(flight)
        return waiter
    }

    private fun start(flight: Flight) {
        try {
            val timeout = limits.queryTimeoutMillis + (flight.attempts.size - 1) * limits.parallelDelayMillis
            val deadline = scheduler.schedule({
                finish(flight, error = SocketTimeoutException("NAT64 lookup deadline exceeded"))
            }, timeout, TimeUnit.MILLISECONDS)
            synchronized(lock) { if (live(flight)) flight.deadline = deadline else deadline.cancel(false) }
            for ((index, attempt) in flight.attempts.withIndex()) {
                synchronized(lock) { if (!live(flight)) return }
                if (index == 0 || limits.parallelDelayMillis == 0L) launch(flight, attempt)
                else {
                    val timer = scheduler.schedule({ launch(flight, attempt) },
                        index * limits.parallelDelayMillis, TimeUnit.MILLISECONDS)
                    synchronized(lock) { if (live(flight)) attempt.timer = timer else timer.cancel(false) }
                }
            }
        } catch (_: RejectedExecutionException) {
            finish(flight, error = NativeDnsClosedException())
        }
    }

    private fun launch(flight: Flight, attempt: Attempt) {
        synchronized(lock) { if (!live(flight)) return }
        val query = NativeNat64DohQuery(attempt.route.provider, flight.key.hostname,
            flight.key.type, attempt.route.destination)
        val future = try { dialer.queryNat64Doh(query) }
        catch (error: Exception) { failedAttempt(flight, attempt, error); return }
        synchronized(lock) {
            if (!live(flight)) { future.cancel(true); return }
            attempt.future = future
        }
        future.whenComplete { result, error ->
            synchronized(lock) { if (!live(flight)) return@whenComplete }
            if (error != null) { failedAttempt(flight, attempt, error); return@whenComplete }
            try {
                val converted = convert(flight, requireNotNull(result))
                finish(flight, converted, winner = attempt.route.provider.server)
            } catch (invalid: Exception) { failedAttempt(flight, attempt, invalid) }
        }
    }

    private fun convert(flight: Flight, value: NativeNat64DohAnswer): NativeNat64DohAnswer {
        if (flight.key.type != NativeNat64Question.A) {
            require(value is NativeNat64DohAnswer.Services)
            require(value.answer.records.size <= limits.maxAddresses &&
                value.answer.negativeTtlMillis >= 0 &&
                (value.answer.code == NativeDnsAnswerCode.ANSWER || value.answer.records.isEmpty()))
            return value
        }
        require(value is NativeNat64DohAnswer.Addresses) { "NAT64 requires an A answer" }
        val answer = value.answer
        require(answer.records.size <= limits.maxAddresses && answer.negativeTtlMillis >= 0 &&
            (answer.code == NativeDnsAnswerCode.ANSWER || answer.records.isEmpty()))
        val records = linkedMapOf<String, Long>()
        for (record in answer.records) {
            require(IpAddress.isIpv4(record.address) && record.ttlMillis >= 0) { "Invalid NAT64 source A record" }
            val address = requireNotNull(Nat64Address.toNat64Address(flight.plan.prefix, record.address))
            records[address] = minOf(records[address] ?: Long.MAX_VALUE, record.ttlMillis, flight.plan.cacheTtl)
        }
        val negative = if (answer.negativeTtlMillis > 0)
            minOf(answer.negativeTtlMillis, flight.plan.cacheTtl) else flight.plan.cacheTtl
        return NativeNat64DohAnswer.Addresses(NativeDnsAnswer(
            records.map { NativeDnsRecord(it.key, it.value) },
            if (records.isEmpty() && answer.code == NativeDnsAnswerCode.ANSWER) NativeDnsAnswerCode.NODATA else answer.code,
            if (records.isEmpty()) negative else 0,
        ))
    }

    private fun failedAttempt(flight: Flight, attempt: Attempt, error: Throwable) {
        val errors = synchronized(lock) {
            if (!live(flight) || attempt.done) return
            attempt.done = true
            attempt.future = null
            flight.failures.add(error)
            if (flight.attempts.all { it.done }) flight.failures.toList() else null
        }
        if (errors != null) finish(flight, error = NativeNat64QueryException(errors))
    }

    private fun finish(
        flight: Flight, value: NativeNat64DohAnswer? = null, error: Throwable? = null, winner: String? = null,
    ) {
        val waiters: List<CompletableFuture<NativeNat64DohAnswer>>
        synchronized(lock) {
            if (!live(flight)) return
            flights.remove(flight.key)
            waiters = flight.waiters.toList()
            flight.waiters.clear()
            winner?.let { flight.plan.lastSuccess = it }
            if (flight.key.type == NativeNat64Question.A) {
                val answer = (value as? NativeNat64DohAnswer.Addresses)?.answer
                val ttl = if (error != null) limits.failedCacheTtlMillis else requireNotNull(answer).let {
                    if (it.records.isEmpty()) it.negativeTtlMillis else it.records.minOf { rr -> rr.ttlMillis }
                }
                if (ttl > 0) {
                    val now = clock.nowMillis()
                    require(now >= 0)
                    val expiry = if (now > Long.MAX_VALUE - ttl) Long.MAX_VALUE else now + ttl
                    prune()
                    cache[flight.key] = Entry(answer, error, now, expiry)
                    while (cache.size > limits.maxEntries) cache.remove(cache.keys.first())
                }
            }
        }
        cancel(flight)
        waiters.forEach { if (error != null) it.completeExceptionally(error) else it.complete(requireNotNull(value)) }
    }

    private fun detach(flight: Flight, waiter: CompletableFuture<NativeNat64DohAnswer>) {
        val last = synchronized(lock) {
            if (!live(flight)) return
            flight.waiters.remove(waiter)
            if (flight.waiters.isEmpty()) { flights.remove(flight.key); true } else false
        }
        if (last) cancel(flight)
    }

    private fun release(flight: Flight, error: Throwable) {
        cancel(flight)
        val waiters = synchronized(lock) { flight.waiters.toList().also { flight.waiters.clear() } }
        waiters.forEach { it.completeExceptionally(error) }
    }

    private fun cancel(flight: Flight) {
        val work = synchronized(lock) {
            Triple(flight.deadline, flight.attempts.mapNotNull { it.future }, flight.attempts.mapNotNull { it.timer })
        }
        work.first?.cancel(false)
        work.third.forEach { it.cancel(false) }
        work.second.forEach { it.cancel(true) }
    }

    private fun live(flight: Flight) = !closed && flights[flight.key] === flight
    private fun prune() { cache.values.removeAll { it.expiresAt <= clock.nowMillis() } }
    private fun <T> failed(error: Throwable) = CompletableFuture<T>().apply { completeExceptionally(error) }
    private fun <T> map(source: CompletableFuture<NativeNat64DohAnswer>, convert: (NativeNat64DohAnswer) -> T): CompletableFuture<T> {
        val result = object : CompletableFuture<T>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                val canceled = super.cancel(mayInterruptIfRunning)
                if (canceled) source.cancel(mayInterruptIfRunning)
                return canceled
            }
        }
        source.whenComplete { value, error ->
            if (error != null) result.completeExceptionally(error)
            else try { result.complete(convert(value)) } catch (invalid: Exception) { result.completeExceptionally(invalid) }
        }
        return result
    }
}
