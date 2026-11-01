// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderCatalog
import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.DnsProviderJson
import com.dsha.sidecar.net.dns.DnsProviderSelection
import com.dsha.sidecar.net.dns.DynamicChoice
import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.IpCandidatePolicy
import com.dsha.sidecar.net.dns.Ipv6Availability
import com.dsha.sidecar.net.rules.HostnameMatcher
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Isolated JVM foundation. No production callers, OS resolver, interfaces or sockets.
 * One monitor protects bounded state. Transport/ranking calls and future
 * completions happen outside it. Rules are snapshotted for one configuration epoch.
 */
class NativeDnsResolver(
    catalog: DnsProviderCatalog,
    private val transport: DnsTransport,
    private val clock: NativeDnsClock,
    mapping: Map<String, Any?> = emptyMap(),
    presets: Map<String, Any?> = emptyMap(),
    ech: Map<String, Any?>? = null,
    nat64Domains: Any? = null,
    ipv6Available: Boolean = true,
    private val limits: NativeDnsLimits = NativeDnsLimits(),
    private val candidateOrder: NativeDnsCandidateOrder = NativeDnsCandidateOrder { _, ips -> ips },
) : AutoCloseable {
    private data class Key(
        val provider: String,
        val host: String,
        val family: NativeDnsFamily,
        val destinationPort: Int,
    )
    private class Entry(
        val query: NativeDnsQuery,
        val destinationPort: Int,
        val addresses: List<String>,
        val status: NativeDnsStatus,
        val expiresAt: Long,
    ) {
        var identity = Any()
        val choice = DynamicChoice(query.hostname).apply {
            setBackupList(addresses + query.hostname)
        }
    }

    private class Flight(val query: NativeDnsQuery) {
        val waiters = linkedSetOf<CompletableFuture<NativeDnsResult>>()
        var upstream: CompletableFuture<NativeDnsAnswer>? = null
    }

    data class State(val entries: Int, val flights: Int, val waiters: Int, val closed: Boolean)

    private val monitor = Any()
    private val descriptors = catalog.descriptors.associate { descriptor ->
        descriptor.key to descriptor.copy(
            echOptions = descriptor.echOptions?.let(DnsProviderJson::copyObject),
            nat64Options = descriptor.nat64Options?.let(DnsProviderJson::copyObject),
        )
    }
    private val selection = DnsProviderSelection(catalog, mapping, presets, ech, nat64Domains)
    private val presetMatcher = HostnameMatcher(DnsProviderJson.copyObject(presets))
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val flights = mutableMapOf<Key, Flight>()
    private var ipv6 = Ipv6Availability.fromInterfaceAvailability(ipv6Available)
    private var closed = false

    init {
        require(descriptors.size == catalog.descriptors.size) { "Duplicate provider keys" }
        descriptors.values.forEach { descriptor ->
            family(descriptor.family)
            providerLimit(descriptor)
        }
    }

    /**
     * Cancellation detaches just this waiter. Last-waiter cancellation cancels the
     * transport and removes the flight; a late answer cannot repopulate the cache.
     * Transport failures complete exceptionally and are never negative-cached.
     */
    fun resolve(
        hostname: String,
        requestedFamily: NativeDnsFamily? = null,
        destinationPort: Int = 443,
    ): CompletableFuture<NativeDnsResult> {
        require(destinationPort in 1..65535) { "Destination port must be in 1..65535" }
        val host = normalizeHost(hostname)
        val waiter = CompletableFuture<NativeDnsResult>()
        var start: Pair<Key, Flight>? = null
        synchronized(monitor) {
            if (closed) return failed(NativeDnsClosedException())
            prune()
            if (IpAddress.isIpLiteral(host)) {
                val allowed = accepts(host, requestedFamily ?: NativeDnsFamily.ANY)
                return CompletableFuture.completedFuture(NativeDnsResult(
                    host, null, requestedFamily ?: NativeDnsFamily.ANY, destinationPort,
                    if (allowed) NativeDnsStatus.LITERAL else NativeDnsStatus.NODATA,
                    host.takeIf { allowed }, if (allowed) listOf(host) else emptyList(),
                    if (allowed) listOf(host) else emptyList(), false,
                ))
            }
            val choice = selection.select(host)
            if (choice == null) {
                return CompletableFuture.completedFuture(NativeDnsResult(
                    host, null, requestedFamily ?: NativeDnsFamily.ANY, destinationPort,
                    NativeDnsStatus.NO_PROVIDER, host, listOf(host), emptyList(), false,
                ))
            }
            val provider = requireNotNull(descriptors[choice.providerKey]) {
                "Missing provider descriptor: ${choice.providerKey}"
            }
            val selectedFamily = requestedFamily ?: family(choice.family ?: provider.family)
            val key = Key(provider.key, host, selectedFamily, destinationPort)
            entries[key]?.let { return CompletableFuture.completedFuture(snapshot(it, true)) }
            var flight = flights[key]
            if (flight == null) {
                if (flights.size >= limits.maxInFlight) return failed(NativeDnsCapacityException())
                flight = Flight(NativeDnsQuery(provider, host, selectedFamily))
                flights[key] = flight
                start = key to flight
            }
            if (flight.waiters.size >= limits.maxWaitersPerFlight) {
                return failed(NativeDnsCapacityException())
            }
            flight.waiters.add(waiter)
            val attached = flight
            waiter.whenComplete { _, _ -> detach(key, attached, waiter) }
        }
        start?.let { (key, flight) -> start(key, flight) }
        return waiter
    }

    /** Success/failure applies only to this live cache entry, never a replacement. */
    fun feedback(result: NativeDnsResult, success: Boolean): Boolean = synchronized(monitor) {
        if (closed || result.entryIdentity == null || result.selectedDestination == null) return false
        prune()
        val key = Key(
            result.providerKey ?: return false, result.hostname, result.family, result.destinationPort,
        )
        val entry = entries[key] ?: return false
        if (entry.identity !== result.entryIdentity) return false
        entry.choice.doCount(result.selectedDestination, !success)
        true
    }

    /** Explicit retry epoch: restore IP attempts and remove hostname from reset backups. */
    fun reset(result: NativeDnsResult): Boolean = synchronized(monitor) {
        if (closed || result.entryIdentity == null) return false
        prune()
        val key = Key(
            result.providerKey ?: return false, result.hostname, result.family, result.destinationPort,
        )
        val entry = entries[key] ?: return false
        if (entry.identity !== result.entryIdentity || entry.addresses.isEmpty()) return false
        // DynamicChoice.resetChoice excludes hostname; append it to preserve fallback.
        entry.choice.resetChoice(entry.query.hostname)
        entry.choice.setBackupList(entry.addresses + entry.query.hostname)
        entry.identity = Any()
        true
    }

    /**
     * Improved native scoping: absent provider/family invalidates every matching
     * variant across all destination ports, including active queries.
     */
    fun invalidate(
        hostname: String? = null,
        providerKey: String? = null,
        family: NativeDnsFamily? = null,
    ) {
        val host = hostname?.let(::normalizeHost)
        val removed: List<Flight>
        synchronized(monitor) {
            fun matches(key: Key) = (host == null || key.host == host) &&
                (providerKey == null || key.provider == providerKey) &&
                (family == null || key.family == family)
            entries.keys.removeAll(::matches)
            val keys = flights.keys.filter(::matches)
            removed = keys.map { flights.remove(it)!! }
        }
        removed.forEach { abort(it, NativeDnsInvalidatedException()) }
    }

    /**
     * Caller reports only IPv6 ENETUNREACH. Third relevant event disables IPv6
     * for this process epoch and invalidates cache and flights atomically.
     */
    fun reportIpv6Unreachable(address: String) {
        val removed: List<Flight>
        synchronized(monitor) {
            if (closed) return
            val previous = ipv6.available
            ipv6 = ipv6.reportUnreachable(address)
            if (previous == ipv6.available) return
            entries.clear()
            removed = flights.values.toList()
            flights.clear()
        }
        removed.forEach { abort(it, NativeDnsInvalidatedException()) }
    }

    fun state(): State = synchronized(monitor) {
        prune()
        State(entries.size, flights.size, flights.values.sumOf { it.waiters.size }, closed)
    }

    /** Never waits for a transport callback or worker termination. Idempotent. */
    override fun close() {
        val removed: List<Flight>
        synchronized(monitor) {
            if (closed) return
            closed = true
            entries.clear()
            removed = flights.values.toList()
            flights.clear()
        }
        removed.forEach { abort(it, NativeDnsClosedException()) }
        transport.close()
    }

    private fun start(key: Key, flight: Flight) {
        // A close/cancellation before dispatch must not launch unnecessary work.
        synchronized(monitor) { if (flights[key] !== flight) return }
        val future = try {
            if (flight.query.provider.transport == "preset") {
                val preset = IpCandidatePolicy.selectPreset(presetMatcher.match(key.host))
                CompletableFuture.completedFuture(NativeDnsAnswer(
                    preset?.addresses.orEmpty().map { NativeDnsRecord(it, limits.presetTtlMillis) },
                ))
            } else {
                transport.query(flight.query)
            }
        } catch (error: Exception) {
            finish(key, flight, null, error)
            return
        }
        val attached = synchronized(monitor) {
            if (flights[key] !== flight) false else {
                flight.upstream = future
                true
            }
        }
        if (!attached) {
            future.cancel(true)
            return
        }
        future.whenComplete { answer, error -> finish(key, flight, answer, error) }
    }

    private fun finish(key: Key, flight: Flight, answer: NativeDnsAnswer?, error: Throwable?) {
        var failure = error
        var entry: Entry? = null
        if (failure == null) {
            try {
                val available = synchronized(monitor) {
                    if (flights[key] !== flight) return
                    ipv6.available
                }
                entry = prepare(flight.query, key.destinationPort, requireNotNull(answer), available)
            } catch (invalid: Exception) {
                failure = invalid
            }
        }
        val waiters: List<CompletableFuture<NativeDnsResult>>
        val result: NativeDnsResult?
        synchronized(monitor) {
            if (flights[key] !== flight) return
            flights.remove(key)
            waiters = flight.waiters.toList()
            flight.waiters.clear()
            if (entry != null) {
                prune()
                if (entry.expiresAt > clock.nowMillis() && providerLimit(entry.query.provider) > 0) {
                    entries[key] = entry
                    trim(entry.query.provider)
                }
                result = snapshot(entry, false)
            } else {
                result = null
            }
        }
        for (waiter in waiters) {
            if (failure != null) waiter.completeExceptionally(failure)
            else waiter.complete(requireNotNull(result))
        }
    }

    private fun prepare(
        query: NativeDnsQuery,
        destinationPort: Int,
        answer: NativeDnsAnswer,
        available: Boolean,
    ): Entry {
        require(answer.negativeTtlMillis >= 0) { "Negative TTL" }
        require(answer.records.size <= limits.maxCandidates) { "Too many DNS records" }
        require(answer.code == NativeDnsAnswerCode.ANSWER || answer.records.isEmpty()) {
            "Negative DNS answer contains records"
        }
        answer.records.forEach {
            require(it.address.length <= 253 && IpAddress.isIpLiteral(it.address) && it.ttlMillis >= 0) {
                "Invalid DNS record"
            }
        }
        val records = answer.records.filter {
            accepts(it.address, query.family) && (available || !IpAddress.isIpv6(it.address))
        }
        val ips = records.map { it.address }.distinct()
        val ranked = if (ips.isEmpty()) emptyList() else {
            val ordered = candidateOrder.order(query, ips.toList(), destinationPort)
            require(ordered.size <= ips.size) { "Ranking returned too many addresses" }
            ordered.toList()
        }
        require(ranked.size == ranked.distinct().size && ranked.all { it in ips }) {
            "Ranking must return a unique subset of the supplied addresses"
        }
        val status = when {
            answer.code == NativeDnsAnswerCode.NXDOMAIN -> NativeDnsStatus.NXDOMAIN
            answer.code == NativeDnsAnswerCode.NODATA || ips.isEmpty() -> NativeDnsStatus.NODATA
            ranked.isEmpty() -> NativeDnsStatus.NO_REACHABLE_IP
            else -> NativeDnsStatus.CANDIDATES
        }
        // Empty/filtered/probe-failed answers require an explicit negative TTL.
        val ttl = if (ranked.isEmpty()) answer.negativeTtlMillis
            else records.minOf { it.ttlMillis }
        val now = clock.nowMillis()
        require(now >= 0) { "Clock must be nonnegative" }
        val duration = ttl.coerceAtMost(limits.maxTtlMillis)
        val expires = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
        return Entry(query, destinationPort, ranked, status, expires)
    }

    private fun snapshot(entry: Entry, cached: Boolean): NativeDnsResult {
        val destination = entry.choice.value
        return NativeDnsResult(
            entry.query.hostname, entry.query.provider.key, entry.query.family, entry.destinationPort,
            if (destination == null) NativeDnsStatus.EXHAUSTED else entry.status,
            destination, listOfNotNull(destination) + entry.choice.backupList,
            entry.addresses, cached, entry.identity,
        )
    }

    private fun detach(key: Key, flight: Flight, waiter: CompletableFuture<NativeDnsResult>) {
        var cancel: CompletableFuture<NativeDnsAnswer>? = null
        synchronized(monitor) {
            if (flights[key] !== flight) return
            flight.waiters.remove(waiter)
            if (flight.waiters.isEmpty()) {
                flights.remove(key)
                cancel = flight.upstream
            }
        }
        cancel?.cancel(true)
    }

    private fun abort(flight: Flight, error: Exception) {
        // Flight was detached under the monitor, so no new waiter can join it.
        val waiters = flight.waiters.toList()
        flight.waiters.clear()
        flight.upstream?.cancel(true)
        waiters.forEach { it.completeExceptionally(error) }
    }

    private fun prune() {
        val now = clock.nowMillis()
        entries.values.removeAll { it.expiresAt <= now }
    }

    private fun trim(provider: DnsProviderDescriptor) {
        val providerEntries = entries.keys.filter { it.provider == provider.key }
        val excess = providerEntries.size - providerLimit(provider)
        providerEntries.take(excess.coerceAtLeast(0)).forEach(entries::remove)
        while (entries.size > limits.maxEntries) entries.remove(entries.keys.first())
    }

    private fun providerLimit(provider: DnsProviderDescriptor): Int {
        val raw = provider.cacheSize ?: return limits.maxEntriesPerProvider
        val number = raw.toString().toDoubleOrNull()
        require(number != null && number.isFinite() && number >= 0 && number % 1.0 == 0.0) {
            "Provider cacheSize must be a nonnegative integer"
        }
        return number.coerceAtMost(limits.maxEntriesPerProvider.toDouble()).toInt()
    }

    private fun accepts(address: String, family: NativeDnsFamily): Boolean = when (family) {
        NativeDnsFamily.ANY -> true
        NativeDnsFamily.IPV4 -> IpAddress.isIpv4(address)
        NativeDnsFamily.IPV6 -> IpAddress.isIpv6(address)
    }

    private fun family(raw: Any?): NativeDnsFamily = when (raw?.toString()) {
        null, "0", "0.0" -> NativeDnsFamily.ANY
        "4", "4.0" -> NativeDnsFamily.IPV4
        "6", "6.0" -> NativeDnsFamily.IPV6
        else -> throw IllegalArgumentException("Unsupported DNS family: $raw")
    }

    private fun normalizeHost(input: String): String {
        val trimmed = input.trim()
        if (trimmed.length <= 253 && IpAddress.isIpLiteral(trimmed)) return trimmed
        val host = trimmed.removeSuffix(".").lowercase(Locale.ROOT)
        require(host.isNotEmpty() && host.length <= 253 && host.split('.').all { label ->
            label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it in "-_" }
        }) {
            "Invalid hostname"
        }
        // DynamicChoice reserves these JavaScript prototype keys.
        require(host !in setOf("__proto__", "constructor", "__definegetter__", "__definesetter__",
            "hasownproperty", "__lookupgetter__", "__lookupsetter__", "isprototypeof",
            "propertyisenumerable", "tostring", "valueof", "tolocalestring")) { "Reserved hostname" }
        return host
    }

    private fun <T> failed(error: Exception): CompletableFuture<T> =
        CompletableFuture<T>().apply { completeExceptionally(error) }
}
