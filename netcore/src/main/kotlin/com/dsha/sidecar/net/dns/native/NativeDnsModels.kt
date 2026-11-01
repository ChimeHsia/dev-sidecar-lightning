// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import java.util.concurrent.CompletableFuture

enum class NativeDnsFamily { ANY, IPV4, IPV6 }

data class NativeDnsQuery(
    val provider: DnsProviderDescriptor,
    val hostname: String,
    val family: NativeDnsFamily,
)

data class NativeDnsRecord(val address: String, val ttlMillis: Long)

enum class NativeDnsAnswerCode { ANSWER, NXDOMAIN, NODATA }

data class NativeDnsAnswer(
    val records: List<NativeDnsRecord> = emptyList(),
    val code: NativeDnsAnswerCode = NativeDnsAnswerCode.ANSWER,
    val negativeTtlMillis: Long = 0,
)

/**
 * Nonblocking query boundary. No implementation here performs network I/O.
 * The resolver owns this transport; close and cancellation must be nonblocking.
 * A canceled future must release transport resources, including any queued work.
 */
interface DnsTransport : AutoCloseable {
    fun query(query: NativeDnsQuery): CompletableFuture<NativeDnsAnswer>
    override fun close() {}
}

fun interface NativeDnsClock {
    /** Monotonic milliseconds in a nonnegative epoch, supplied by the process. */
    fun nowMillis(): Long
}

/**
 * Optional ranking boundary. Return a unique, ordered subset of the supplied IPs.
 * An empty list means no reachable IP; it must never be replaced with the first IP.
 * Implementations must be nonblocking. Default ordering does not imply reachability.
 */
fun interface NativeDnsCandidateOrder {
    fun order(query: NativeDnsQuery, addresses: List<String>): List<String>

    /** Port-aware override; existing pure DNS-order policies keep their original API. */
    fun order(query: NativeDnsQuery, addresses: List<String>, destinationPort: Int): List<String> =
        order(query, addresses)
}

data class NativeDnsLimits(
    val maxEntries: Int = 256,
    val maxEntriesPerProvider: Int = 64,
    val maxCandidates: Int = 32,
    val maxInFlight: Int = 32,
    val maxWaitersPerFlight: Int = 64,
    val maxTtlMillis: Long = 600_000,
    val presetTtlMillis: Long = 60_000,
) {
    init {
        require(maxEntries > 0 && maxEntriesPerProvider > 0 && maxCandidates > 0)
        require(maxInFlight > 0 && maxWaitersPerFlight > 0)
        require(maxTtlMillis > 0 && presetTtlMillis >= 0)
    }
}

enum class NativeDnsStatus {
    CANDIDATES, LITERAL, NO_PROVIDER, NXDOMAIN, NODATA, NO_REACHABLE_IP, EXHAUSTED,
}

/**
 * selectedDestination may be an IP, an unresolved hostname fallback, or null.
 * candidateOrder is the exact remaining attempt order, including hostname fallback.
 * No status asserts that a connection succeeded. Feedback uses this result as a ticket.
 */
class NativeDnsResult internal constructor(
    val hostname: String,
    val providerKey: String?,
    val family: NativeDnsFamily,
    val destinationPort: Int,
    val status: NativeDnsStatus,
    val selectedDestination: String?,
    candidateOrder: List<String>,
    addresses: List<String>,
    val cached: Boolean,
    internal val entryIdentity: Any? = null,
) {
    private val storedOrder = candidateOrder.toList()
    private val storedAddresses = addresses.toList()
    val candidateOrder: List<String> get() = storedOrder.toList()
    val addresses: List<String> get() = storedAddresses.toList()
    val selectedIp: String?
        get() = selectedDestination?.takeIf { it in storedAddresses }
    val hostnameFallback: Boolean
        get() = selectedDestination == hostname && selectedIp == null
}

class NativeDnsClosedException : IllegalStateException("Native DNS resolver is closed")
class NativeDnsInvalidatedException : IllegalStateException("Native DNS query was invalidated")
class NativeDnsCapacityException : IllegalStateException("Native DNS concurrency limit reached")
