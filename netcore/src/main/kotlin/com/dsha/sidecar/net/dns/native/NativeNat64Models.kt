// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import java.io.IOException
import java.util.concurrent.CompletableFuture

enum class NativeNat64Question(val code: Int) { A(1), HTTPS(65), SVCB(64) }

/** The service descriptor retains URL/Host/SNI identity; only the dial IP is replaced. */
data class NativeNat64DohQuery(
    val provider: DnsProviderDescriptor,
    val hostname: String,
    val type: NativeNat64Question,
    val ipv6Destination: String,
)

sealed class NativeNat64DohAnswer {
    data class Addresses(val answer: NativeDnsAnswer) : NativeNat64DohAnswer()
    data class Services(val answer: HttpsRecordAnswer) : NativeNat64DohAnswer()
}

/** Nonblocking, cancellable JSON DoH over an explicit IPv6 dial destination. */
interface NativeNat64DohDialer : AutoCloseable {
    fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer>
    override fun close() {}
}

data class NativeNat64Limits(
    val maxPlans: Int = 8,
    val maxEntries: Int = 256,
    val maxFlights: Int = 16,
    val maxWaiters: Int = 64,
    val maxDohEndpoints: Int = 8,
    val maxAddresses: Int = 32,
    val queryTimeoutMillis: Long = 5_000,
    val parallelDelayMillis: Long = 300,
    val failedCacheTtlMillis: Long = 30_000,
) {
    init {
        require(maxPlans in 1..64 && maxEntries in 1..4096 && maxFlights in 1..128)
        require(maxWaiters in 1..256 && maxDohEndpoints in 1..32 && maxAddresses in 1..1024)
        require(queryTimeoutMillis in 1..120_000 && parallelDelayMillis in 0..5_000)
        require(failedCacheTtlMillis in 0..300_000)
    }
}

class NativeNat64DisabledException : UnsupportedOperationException("NAT64 is disabled: a configured /96 prefix is required")
class NativeNat64QueryException(val failures: List<Throwable>) :
    IOException("All NAT64 DoH endpoints failed (${failures.size})", failures.firstOrNull())
