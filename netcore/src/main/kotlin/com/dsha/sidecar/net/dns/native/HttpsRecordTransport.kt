// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.SvcbParam
import com.dsha.sidecar.net.dns.SvcbRecord
import com.dsha.sidecar.net.dns.SvcbValue
import java.util.concurrent.CompletableFuture

enum class HttpsRecordType(val code: Int) { HTTPS(65), SVCB(64) }

data class HttpsRecordQuery(
    val provider: DnsProviderDescriptor,
    val hostname: String,
    val type: HttpsRecordType = HttpsRecordType.HTTPS,
)

/** Detached RDATA with absolute TTL in milliseconds (already limited by CNAME TTL). */
class HttpsServiceRecord(val owner: String, val ttlMillis: Long, service: SvcbRecord) {
    private val stored = copyService(service)
    val service: SvcbRecord get() = copyService(stored)

    internal fun withTtl(ttl: Long) = HttpsServiceRecord(owner, minOf(ttlMillis, ttl), stored)

    private fun copyService(value: SvcbRecord) = SvcbRecord(value.priority, value.target, value.params.map {
        SvcbParam(it.key, it.name, it.raw.copyOf(), when (val decoded = it.value) {
            is SvcbValue.Bytes -> SvcbValue.Bytes(decoded.bytes.copyOf())
            is SvcbValue.Strings -> SvcbValue.Strings(decoded.items.toList())
            else -> decoded
        })
    })
}

class HttpsRecordAnswer(
    records: List<HttpsServiceRecord> = emptyList(),
    val code: NativeDnsAnswerCode = NativeDnsAnswerCode.ANSWER,
    val negativeTtlMillis: Long = 0,
    canonicalNames: List<String> = emptyList(),
) {
    private val stored = records.toList()
    private val names = canonicalNames.toList()
    val records: List<HttpsServiceRecord> get() = stored.toList()
    /** CNAME dependencies used to invalidate aliases as well as original queries. */
    val canonicalNames: List<String> get() = names.toList()
}

/** Typed service records, with the same nonblocking cancellation/ownership contract as DnsTransport. */
interface HttpsRecordTransport : AutoCloseable {
    fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer>
    override fun close() {}
}
