// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.schedule

import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead

data class PolicyEpoch(val policy: String, val epoch: Long) {
    init { require(policy.isNotBlank() && policy.length <= 1024 && epoch >= 0) }
}

data class ScheduledRequest(
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val headers: List<ActionHeader> = emptyList(),
    val policy: PolicyEpoch,
    /** Required for explicitly enabled isolated private caching. Never a substitute for a policy epoch. */
    val privatePartition: String? = null,
)

data class CacheLimits(
    val maximumEntries: Int = 256,
    val maximumEntryBytes: Int = 1_048_576,
    val maximumTotalBytes: Int = 16_777_216,
    val maximumActiveCaptures: Int = 16,
    val maximumCaptureBytes: Int = 4_194_304,
    val maximumTtlMillis: Long = 604_800_000,
) {
    init {
        require(maximumEntries in 1..4096 && maximumEntryBytes in 0..16_777_216)
        require(maximumTotalBytes in 0..268_435_456 && maximumCaptureBytes in 0..67_108_864)
        require(maximumActiveCaptures in 1..256 && maximumTtlMillis in 1..31_536_000_000L)
    }
}

enum class CachePrivacy { PUBLIC_ONLY, ISOLATED_PRIVATE }
enum class CacheBypass {
    METHOD, REQUEST_DIRECTIVE, SENSITIVE, RESPONSE_DIRECTIVE, STATUS, VARY, CAPACITY,
    EXPIRED, INVALIDATED, BODY_TOO_LARGE, INCOMPLETE, INVALID_FRAMING, CANCELLED,
}

sealed interface CacheStoreResult {
    data object Stored : CacheStoreResult
    data class Bypassed(val reason: CacheBypass) : CacheStoreResult
}

sealed interface CaptureDecision {
    data class Capture(val capture: MemoryResponseCache.Capture) : CaptureDecision
    data class Bypass(val reason: CacheBypass) : CaptureDecision
}

/** Encoded entity bytes, copied on access. HTTP protocol/framing is owned by the engine. */
class CachedResponse internal constructor(val head: ActionResponseHead, private val body: ByteArray) {
    fun bodyBytes(): ByteArray = body.clone()
    val bodySize: Int get() = body.size
}

class ScheduleCancellation {
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    val isCancelled: Boolean get() = cancelled.get()
    fun throwIfCancelled() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw CancellationException("Request scheduling cancelled")
    }
}

/**
 * Scheduler does not consume a first-send stream speculatively. Use buffer() only after eligibility,
 * on an exactly framed entity. OneShot leaves the original stream owned by the injected transport.
 */
sealed class ReplayableRequest {
    abstract val request: ScheduledRequest
    abstract fun openBody(): InputStream
    abstract val replayable: Boolean

    companion object {
        fun empty(request: ScheduledRequest): ReplayableRequest {
            val copy = ScheduleSupport.request(request)
            require(ScheduleSupport.length(copy.headers).let { it == null || it == 0L }) { "Nonempty body requires complete buffering" }
            return BufferedReplay(copy, ByteArray(0))
        }
        fun oneShot(request: ScheduledRequest, body: InputStream): ReplayableRequest =
            OneShotReplay(ScheduleSupport.request(request), body)

        fun eligibleToBuffer(request: ScheduledRequest, maximumBytes: Int = 1_048_576): Boolean =
            ScheduleSupport.bufferEligible(request, maximumBytes)

        /** Failure is terminal to this buffer operation; callers must not forward a consumed partial body. */
        fun buffer(
            request: ScheduledRequest,
            body: InputStream,
            maximumBytes: Int = 1_048_576,
            cancellation: ScheduleCancellation = ScheduleCancellation(),
        ): ReplayableRequest = ScheduleSupport.buffer(request, body, maximumBytes, cancellation)
    }
}

internal class BufferedReplay(override val request: ScheduledRequest, bytes: ByteArray) : ReplayableRequest() {
    private val bytes = bytes.clone()
    override val replayable: Boolean get() = true
    val size: Int get() = bytes.size
    override fun openBody(): InputStream = bytes.inputStream()
}

internal class OneShotReplay(override val request: ScheduledRequest, private val body: InputStream) : ReplayableRequest() {
    private val opened = AtomicBoolean()
    override val replayable: Boolean get() = false
    override fun openBody(): InputStream {
        check(opened.compareAndSet(false, true)) { "One-shot body was already consumed" }
        return body
    }
}

data class AttemptContext(
    val number: Int,
    val target: String?,
    val request: ScheduledRequest,
    val cancellation: ScheduleCancellation,
)

sealed interface AttemptResult<out T> {
    /** release must dispose/drain a discarded response without buffering it in this scheduler. */
    data class Response<T>(
        val status: Int,
        val value: T,
        val release: () -> Unit = {},
        val slow: Boolean = false,
        val responseStarted: Boolean = false,
    ) : AttemptResult<T>
    data class Failure(
        val cause: Throwable,
        val retryable: Boolean,
        /** No retry is allowed after a downstream response has started. */
        val responseStarted: Boolean = false,
        val requestMayHaveBeenSent: Boolean = true,
    ) : AttemptResult<Nothing>
}

data class ScheduledResult<T>(val result: AttemptResult<T>, val attempts: Int, val targets: List<String?>)

internal fun fields(headers: List<ActionHeader>, name: String): List<String> =
    headers.filter { it.name.equals(name, true) }.map { it.value }
