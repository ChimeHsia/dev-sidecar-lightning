// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.schedule

import java.io.InputStream
import java.util.concurrent.CancellationException
import com.dsha.sidecar.net.rules.native.BackupDescriptor
import com.dsha.sidecar.net.rules.native.RetryDescriptor

data class AttemptLimits(
    val maximumRetries: Int = 8,
    val maximumConcurrentExchanges: Int = 32,
    val maximumReplayBytes: Int = 1_048_576,
    val maximumActiveReplayBytes: Int = 8_388_608,
) {
    init {
        require(maximumRetries in 0..64 && maximumConcurrentExchanges in 1..1024 &&
            maximumReplayBytes in 0..1_048_576 && maximumActiveReplayBytes in 0..67_108_864)
    }
}

/**
 * Runs synchronously on caller execution. No executors, timers, DNS, implicit sleeps, or network.
 * The transport must honor cancellation/deadlines during an attempt and return explicit failures.
 */
class RequestAttemptScheduler(
    private val limits: AttemptLimits = AttemptLimits(),
    private val backups: BackupSelector = BackupSelector(),
) {
    private val lock = Any()
    private var exchanges = 0
    private var replayBytes = 0
    val activeExchanges: Int get() = synchronized(lock) { exchanges }
    val activeReplayBytes: Int get() = synchronized(lock) { replayBytes }

    fun <T> execute(
        request: ReplayableRequest,
        retry: RetryDescriptor? = null,
        backup: BackupDescriptor? = null,
        cancellation: ScheduleCancellation = ScheduleCancellation(),
        /** Requires caller knowledge of server-side idempotency; configured POST alone is insufficient. */
        allowNonIdempotentReplay: Boolean = false,
        attempt: (AttemptContext, InputStream) -> AttemptResult<T>,
    ): ScheduledResult<T> {
        cancellation.throwIfCancelled()
        val input = request.request
        if (retry != null) {
            require(retry.times in 0..limits.maximumRetries && retry.statuses.size <= 600 && retry.methods.size <= 64)
            require(retry.statuses.all { it in 100..599 } && retry.maximumBufferedBodyBytes in 0..1_048_576)
        }
        val bufferedSize = (request as? BufferedReplay)?.size ?: 0
        require(bufferedSize <= limits.maximumReplayBytes) { "Replay exceeds scheduler body limit" }
        synchronized(lock) {
            check(exchanges < limits.maximumConcurrentExchanges &&
                replayBytes.toLong() + bufferedSize <= limits.maximumActiveReplayBytes) { "Scheduler capacity exhausted" }
            exchanges++
            replayBytes += bufferedSize
        }
        try {
            val selection = backup?.let { backups.select(input, it) }
            val configured = retry?.copy(statuses = retry.statuses.toList(), methods = retry.methods.toList())?.takeIf {
                it.times > 0 && it.requestBodyEligible && request.replayable &&
                    input.method in it.methods && bufferedSize <= it.maximumBufferedBodyBytes &&
                    ReplayableRequest.eligibleToBuffer(input, it.maximumBufferedBodyBytes)
            }
            // Source has an implicit one-error retry; native limits it to safe, fully replayable methods.
            val automatic = configured == null && request.replayable &&
                input.method in listOf("GET", "HEAD", "OPTIONS") &&
                ReplayableRequest.eligibleToBuffer(input, limits.maximumReplayBytes)
            val retryLimit = configured?.times ?: if (automatic) minOf(1, limits.maximumRetries) else 0
            val targets = mutableListOf<String?>()
            var number = 0
            while (true) {
                cancellation.throwIfCancelled()
                number++
                targets.add(selection?.candidate)
                val body = request.openBody()
                val result = try {
                    attempt(AttemptContext(number, selection?.candidate, input, cancellation), body)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    if (request.replayable) body.close()
                }
                if (cancellation.isCancelled || Thread.currentThread().isInterrupted) {
                    if (result is AttemptResult.Response) result.release()
                    cancellation.throwIfCancelled()
                }
                val shouldRetry = when (result) {
                    is AttemptResult.Failure -> {
                        if (result.cause is CancellationException) throw result.cause
                        if (result.cause is InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw result.cause
                        }
                        selection?.failure()
                        result.retryable && !result.responseStarted && number <= retryLimit &&
                            (input.method in idempotent || !result.requestMayHaveBeenSent || allowNonIdempotentReplay)
                    }
                    is AttemptResult.Response -> {
                        if (result.status !in 100..599) {
                            result.release()
                            throw IllegalArgumentException("Invalid attempt response status")
                        }
                        if (result.slow) selection?.failure()
                        val again = configured != null && result.status in configured.statuses && number <= retryLimit &&
                            (input.method in idempotent || allowNonIdempotentReplay)
                        if (!again && result.status >= 400) selection?.failure()
                        again
                    }
                }
                if (!shouldRetry) return ScheduledResult(result, number, targets.toList())
                if (result is AttemptResult.Response) result.release()
            }
        } finally {
            synchronized(lock) { exchanges--; replayBytes -= bufferedSize }
        }
    }

    companion object {
        private val idempotent = setOf("GET", "HEAD", "OPTIONS", "PUT", "DELETE")
    }
}
