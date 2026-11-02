// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.schedule

import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.CacheDescriptor

/**
 * Bounded memory-only encoded-entity cache. Capture observes bytes already forwarded by the engine.
 * No network/thread/disk, no coalescing, and no claim that upstream's browser cache stores entities.
 */
class MemoryResponseCache(
    private val clock: Clock,
    private val limits: CacheLimits = CacheLimits(),
    private val privacy: CachePrivacy = CachePrivacy.PUBLIC_ONLY,
) {
    private data class Key(val base: String, val vary: Map<String, List<String>>)
    private data class Entry(
        val head: ActionResponseHead,
        val bytes: ByteArray,
        val received: Long,
        val initialAge: Long,
        val lifetime: Long,
        val weight: Int,
    )
    private data class Pending(
        val generation: Long,
        val key: Key,
        val head: ActionResponseHead,
        val received: Long,
        val initialAge: Long,
        val lifetime: Long,
        val expected: Long?,
        var bytes: ByteArrayOutputStream? = ByteArrayOutputStream(),
        var reason: CacheBypass? = null,
        var complete: Boolean = false,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val pending = HashMap<Long, Pending>()
    private var generation = 0L
    private var sequence = 0L
    private var residentBytes = 0
    private var captureBytes = 0

    val size: Int get() = synchronized(lock) { entries.size }
    val totalBytes: Int get() = synchronized(lock) { residentBytes }
    val activeCaptures: Int get() = synchronized(lock) { pending.size }
    val capturedBytes: Int get() = synchronized(lock) { captureBytes }

    fun get(request: ScheduledRequest, descriptor: CacheDescriptor): CachedResponse? {
        val input = ScheduleSupport.request(request)
        val policy = policy(descriptor)
        if (requestBypass(input) != null) return null
        val base = base(input, policy)
        synchronized(lock) {
            val now = clock.millis()
            val candidates = entries.keys.filter { it.base == base }
            for (key in candidates) {
                val entry = entries[key] ?: continue
                if (now < entry.received || age(entry, now) >= entry.lifetime) {
                    remove(key)
                    continue
                }
                if (key.vary.any { (name, values) -> fields(input.headers, name) != values }) continue
                val requestDirectives = directives(input.headers)
                val maximumAge = requestDirectives["max-age"]?.trim('"')?.toDoubleOrNull()
                val minimumFresh = requestDirectives["min-fresh"]?.trim('"')?.toDoubleOrNull() ?: 0.0
                if (maximumAge != null && age(entry, now) > maximumAge * 1000 ||
                    entry.lifetime - age(entry, now) < minimumFresh * 1000) continue
                val headers = entry.head.headers.toMutableList()
                ScheduleSupport.set(headers, "Age", (age(entry, now) / 1000).toString())
                return CachedResponse(
                    entry.head.copy(headers = Collections.unmodifiableList(headers.toList())), entry.bytes.clone(),
                )
            }
            return null
        }
    }

    fun beginCapture(
        request: ScheduledRequest,
        descriptor: CacheDescriptor,
        head: ActionResponseHead,
        requestSentAtMillis: Long? = null,
    ): CaptureDecision {
        val input = ScheduleSupport.request(request)
        val policy = policy(descriptor)
        requestBypass(input)?.let { return CaptureDecision.Bypass(it) }
        ScheduleSupport.validateHeaders(head.headers)
        require(head.reason.length <= 1024 && head.reason.none { it == '\r' || it == '\n' || it.code < 32 || it.code > 255 })
        if (head.status !in maxOf(200, minOf(policy.minimumStatus, policy.maximumStatus))..
            minOf(599, maxOf(policy.minimumStatus, policy.maximumStatus)) ||
            head.status.toString() in policy.excludedStatuses || head.status in listOf(206, 304)) {
            return CaptureDecision.Bypass(CacheBypass.STATUS)
        }
        val directives = directives(head.headers)
        val ages = fields(head.headers, "age")
        val dates = fields(head.headers, "date")
        if (ages.size > 1 || dates.size > 1 ||
            ages.any { !it.matches(Regex("[0-9]+")) || it.toLongOrNull() == null } ||
            dates.any { ScheduleSupport.parseDate(it) == null } ||
            listOf("max-age", "s-maxage").any { name ->
                name in directives && directives[name]?.trim('"')?.matches(Regex("[0-9]+")) != true
            }) return CaptureDecision.Bypass(CacheBypass.RESPONSE_DIRECTIVE)
        if (directives.keys.any { it in setOf("no-cache", "no-store") } ||
            fields(head.headers, "pragma").any { it.contains("no-cache", true) }) {
            return CaptureDecision.Bypass(CacheBypass.RESPONSE_DIRECTIVE)
        }
        if (fields(head.headers, "set-cookie").isNotEmpty() ||
            privacy == CachePrivacy.PUBLIC_ONLY && ("private" in directives || policy.controlType.lowercase(Locale.ROOT) == "private")) {
            return CaptureDecision.Bypass(CacheBypass.SENSITIVE)
        }
        val vary = fields(head.headers, "vary").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
        if (vary.any { it == "*" || !it.matches(Regex("[!#$%&'+.^_`|~0-9a-z-]+")) }) return CaptureDecision.Bypass(CacheBypass.VARY)
        if (fields(head.headers, "content-range").isNotEmpty() || fields(head.headers, "trailer").isNotEmpty()) {
            return CaptureDecision.Bypass(CacheBypass.INVALID_FRAMING)
        }
        val transfer = fields(head.headers, "transfer-encoding")
        val expected = try { ScheduleSupport.length(head.headers) } catch (_: IllegalArgumentException) {
            return CaptureDecision.Bypass(CacheBypass.INVALID_FRAMING)
        }
        if (transfer.isNotEmpty() && (expected != null || transfer.size != 1 || !transfer.single().equals("chunked", true))) {
            return CaptureDecision.Bypass(CacheBypass.INVALID_FRAMING)
        }
        if (expected != null && expected > limits.maximumEntryBytes) return CaptureDecision.Bypass(CacheBypass.BODY_TOO_LARGE)
        synchronized(lock) {
            if (pending.size >= limits.maximumActiveCaptures) return CaptureDecision.Bypass(CacheBypass.CAPACITY)
            val now = clock.millis()
            require(requestSentAtMillis == null || requestSentAtMillis <= now) { "Request send time is in the future" }
            val date = ScheduleSupport.parseDate(fields(head.headers, "date").firstOrNull()) ?: now
            val responseDelay = if (requestSentAtMillis == null) 0.0 else now.toDouble() - requestSentAtMillis
            val initialAge = maxOf(
                maxOf(0.0, now.toDouble() - date).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong(),
                ((fields(head.headers, "age").firstOrNull()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0) * 1000 +
                    responseDelay).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong(),
            )
            var ttl = minOf(limits.maximumTtlMillis.toDouble(), policy.maxAgeSeconds * 1000)
            val serverMaxAge = (if (privacy == CachePrivacy.PUBLIC_ONLY) directives["s-maxage"] ?: directives["max-age"]
                else directives["max-age"])?.trim('"')?.toDoubleOrNull()
            if (serverMaxAge != null && serverMaxAge.isFinite()) ttl = minOf(ttl, maxOf(0.0, serverMaxAge * 1000))
            else ScheduleSupport.parseDate(fields(head.headers, "expires").firstOrNull())?.let {
                ttl = minOf(ttl, maxOf(0.0, it.toDouble() - date))
            }
            val lifetime = ttl.toLong()
            if (initialAge >= lifetime) return CaptureDecision.Bypass(CacheBypass.EXPIRED)
            val normalized = normalizeHead(head, now)
            if (normalized == null) return CaptureDecision.Bypass(CacheBypass.INVALID_FRAMING)
            val key = Key(base(input, policy), (vary + "accept-encoding").distinct().sorted().associateWith {
                fields(input.headers, it).toList()
            })
            val id = ++sequence
            pending[id] = Pending(generation, key, normalized, now, initialAge, lifetime, expected)
            return CaptureDecision.Capture(Capture(id))
        }
    }

    /**
     * Call append AFTER forwarding the same chunk. False means capture was dropped, not forwarding
     * failure. complete is called only at a successfully parsed EOF; close/abort never stores.
     */
    inner class Capture internal constructor(private val id: Long) : AutoCloseable {
        private var terminalReason: CacheBypass? = null
        fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean = synchronized(lock) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            val state = pending[id] ?: return false
            val output = state.bytes ?: return false
            if (output.size().toLong() + length > limits.maximumEntryBytes ||
                captureBytes.toLong() + length > limits.maximumCaptureBytes) {
                terminalReason = CacheBypass.BODY_TOO_LARGE
                drop(id, CacheBypass.BODY_TOO_LARGE)
                return false
            }
            output.write(bytes, offset, length)
            captureBytes += length
            true
        }

        fun complete(cancellation: ScheduleCancellation = ScheduleCancellation()): CacheStoreResult = synchronized(lock) {
            val state = pending[id] ?: return CacheStoreResult.Bypassed(terminalReason ?: CacheBypass.INVALIDATED)
            if (state.complete) return CacheStoreResult.Bypassed(CacheBypass.INCOMPLETE)
            state.complete = true
            if (cancellation.isCancelled || Thread.currentThread().isInterrupted) {
                drop(id, CacheBypass.CANCELLED)
                return CacheStoreResult.Bypassed(CacheBypass.CANCELLED)
            }
            val bytes = state.bytes?.toByteArray() ?: return CacheStoreResult.Bypassed(state.reason ?: CacheBypass.INCOMPLETE)
            val now = clock.millis()
            val reason = when {
                state.generation != generation -> CacheBypass.INVALIDATED
                state.expected != null && state.expected != bytes.size.toLong() -> CacheBypass.INCOMPLETE
                state.head.status in listOf(204, 205) && bytes.isNotEmpty() -> CacheBypass.INVALID_FRAMING
                now < state.received || now.toDouble() - state.received + state.initialAge >= state.lifetime -> CacheBypass.EXPIRED
                else -> null
            }
            if (reason != null) { drop(id, reason); return CacheStoreResult.Bypassed(reason) }
            val headers = state.head.headers.toMutableList()
            if (state.head.status != 204) ScheduleSupport.set(headers, "Content-Length", bytes.size.toString())
            else headers.removeAll { it.name.equals("content-length", true) }
            val weight = bytes.size.toLong() + headers.sumOf { it.name.length.toLong() + it.value.length + 4 } +
                state.key.vary.entries.sumOf { it.key.length.toLong() + it.value.sumOf { value -> value.length.toLong() } } + 256
            if (weight > limits.maximumTotalBytes) {
                drop(id, CacheBypass.CAPACITY)
                return CacheStoreResult.Bypassed(CacheBypass.CAPACITY)
            }
            // A changed Vary definition invalidates older variants for the same resource.
            entries.keys.filter { it.base == state.key.base && it.vary.keys != state.key.vary.keys }.forEach(::remove)
            remove(state.key)
            while (entries.size >= limits.maximumEntries || residentBytes.toLong() + weight > limits.maximumTotalBytes) {
                remove(entries.keys.first())
            }
            entries[state.key] = Entry(
                state.head.copy(headers = Collections.unmodifiableList(headers.toList())), bytes,
                state.received, state.initialAge, state.lifetime, weight.toInt(),
            )
            residentBytes += weight.toInt()
            drop(id, null)
            CacheStoreResult.Stored
        }

        fun abort() = synchronized(lock) {
            terminalReason = CacheBypass.INCOMPLETE
            drop(id, CacheBypass.INCOMPLETE)
        }
        override fun close() = abort()
    }

    /** Global generation boundary also invalidates all active captures and releases their buffers. */
    fun invalidate() = synchronized(lock) {
        generation++
        entries.clear()
        residentBytes = 0
        pending.keys.toList().forEach { drop(it, CacheBypass.INVALIDATED) }
    }

    private fun drop(id: Long, reason: CacheBypass?) {
        val state = pending.remove(id) ?: return
        captureBytes -= state.bytes?.size() ?: 0
        state.bytes = null
        state.reason = reason
    }

    private fun remove(key: Key) { entries.remove(key)?.let { residentBytes -= it.weight } }
    private fun age(entry: Entry, now: Long): Long =
        (entry.initialAge.toDouble() + maxOf(0.0, now.toDouble() - entry.received)).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()

    private fun policy(value: CacheDescriptor): CacheDescriptor {
        require(value.maxAgeSeconds.isFinite() && value.maxAgeSeconds > 0 && value.controlType.length <= 128)
        require(value.excludedStatuses.size <= 600)
        return value.copy(excludedStatuses = value.excludedStatuses.toSet())
    }

    private fun base(input: ScheduledRequest, descriptor: CacheDescriptor): String = ScheduleSupport.hash(listOf(
        input.method, input.scheme, input.host, input.port.toString(), input.path,
        input.policy.policy, input.policy.epoch.toString(), input.privatePartition ?: "",
        descriptor.copy(excludedStatuses = descriptor.excludedStatuses.sorted().toSet()).toString(),
        fields(input.headers, "authorization").joinToString("\u0000"), fields(input.headers, "cookie").joinToString("\u0000"),
    ))

    private fun requestBypass(input: ScheduledRequest): CacheBypass? {
        if (input.method != "GET") return CacheBypass.METHOD
        if (input.headers.any { it.name.equals("range", true) || it.name.startsWith("if-", true) } ||
            directives(input.headers).keys.any { it in setOf("no-cache", "no-store") } ||
            directives(input.headers)["max-age"]?.trim('"')?.toDoubleOrNull() == 0.0 ||
            fields(input.headers, "pragma").any { it.contains("no-cache", true) || it.contains("no-store", true) }) return CacheBypass.REQUEST_DIRECTIVE
        val sensitive = fields(input.headers, "authorization").isNotEmpty() || fields(input.headers, "cookie").isNotEmpty()
        if (privacy == CachePrivacy.PUBLIC_ONLY && sensitive ||
            privacy == CachePrivacy.ISOLATED_PRIVATE && input.privatePartition.isNullOrEmpty()) return CacheBypass.SENSITIVE
        if (fields(input.headers, "transfer-encoding").isNotEmpty() || (ScheduleSupport.length(input.headers) ?: 0) > 0) return CacheBypass.INVALID_FRAMING
        return null
    }

    private fun normalizeHead(head: ActionResponseHead, now: Long): ActionResponseHead? {
        val nominated = fields(head.headers, "connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
        if (nominated.any { it in setOf("content-encoding", "vary", "cache-control") }) return null
        val hop = setOf("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "http2-settings")
        val headers = head.headers.filter { it.name.lowercase(Locale.ROOT) !in hop + nominated }.toMutableList()
        if (fields(headers, "date").isEmpty()) ScheduleSupport.set(headers, "Date", ScheduleSupport.date(now))
        return head.copy(headers = Collections.unmodifiableList(headers.toList()))
    }

    private fun directives(headers: List<ActionHeader>): Map<String, String?> = fields(headers, "cache-control")
        .flatMap { it.split(',') }.associate { directive ->
            val parts = directive.trim().split('=', limit = 2)
            parts[0].lowercase(Locale.ROOT) to parts.getOrNull(1)?.trim()
        }
}
