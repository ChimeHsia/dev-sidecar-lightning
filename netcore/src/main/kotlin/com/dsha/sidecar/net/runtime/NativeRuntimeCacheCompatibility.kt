package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.http.native.RuleActionExecutor
import com.dsha.sidecar.net.http.native.schedule.CacheCompatibility
import com.dsha.sidecar.net.http.native.schedule.PolicyEpoch
import com.dsha.sidecar.net.http.native.schedule.ScheduledRequest
import com.dsha.sidecar.net.rules.native.*
import java.io.IOException
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

data class NativeRuntimeCacheState(
    val conditionalChecks: Long,
    val conditionalHits: Long,
    val responseHeaderChanges: Long,
    /** Header compatibility never creates an entity store or claims a native memory-cache hit. */
    val memoryStorageEnabled: Boolean = false,
)

/** Accounts only the cache descriptors actually executed; all unrelated guards remain intact. */
internal class NativeRuntimeCacheCompatibility(private val clock: Clock) {
    data class RequestResult(val plan: RequestPlan, val terminal: ActionResponseHead? = null)
    private val checks = AtomicLong()
    private val hits = AtomicLong()
    private val changes = AtomicLong()

    fun state() = NativeRuntimeCacheState(checks.get(), hits.get(), changes.get())

    fun request(plan: RequestPlan, request: RuleRequest): RequestResult {
        val remaining = mutableListOf<RequestStep>()
        for (step in plan.requestSteps) {
            when (val action = step.action) {
                is RequestAction.CacheLookup -> {
                    checks.incrementAndGet()
                    val head = CacheCompatibility.conditional304(scheduled(request), action.descriptor, clock.millis())
                    if (head != null) {
                        hits.incrementAndGet()
                        return RequestResult(plan, head)
                    }
                    // This descriptor was handled as a source-compatible miss, not ignored.
                }
                is RequestAction.Retry -> {
                    if (action.descriptor != null) throw IOException("Retry needs an engine precommit attempt boundary")
                    remaining.add(step)
                }
                is RequestAction.Proxy -> {
                    if (action.backup != null) throw IOException("Backup needs actual attempt feedback and target recomposition")
                    remaining.add(step)
                }
                else -> remaining.add(step)
            }
        }
        return RequestResult(plan.copy(requestSteps = remaining))
    }

    fun response(
        plan: RequestPlan,
        request: RuleRequest,
        upstream: ActionResponseHead,
        enabled: Boolean,
        executor: RuleActionExecutor,
    ): ActionResponseHead {
        if (!enabled) return executor.transformResponseHead(plan, request, upstream, false).head
        var head = upstream
        val pending = mutableListOf<ResponseStep>()
        fun flush() {
            // Executing ordered batches retains executor validation even for an empty action list.
            head = executor.transformResponseHead(plan.copy(responseSteps = pending.toList()), request, head, true).head
            pending.clear()
        }
        for (step in plan.responseSteps) {
            val action = step.action
            if (action is ResponseAction.Cache) {
                flush() // Source order: CORS (201), cache headers (202), responseReplace (203).
                val next = CacheCompatibility.responseHeaders(scheduled(request), action.descriptor, head, clock.millis())
                if (next != head) changes.incrementAndGet()
                head = next
            } else {
                pending.add(step)
            }
        }
        flush()
        return head
    }

    private fun scheduled(request: RuleRequest): ScheduledRequest {
        val headers = request.headers.flatMap { (name, raw) ->
            val values = if (raw is List<*>) raw else listOf(raw)
            values.map { value ->
                require(value is String) { "Runtime request headers must be field strings" }
                ActionHeader(name, value)
            }
        }
        return ScheduledRequest(request.method, request.scheme, request.host, request.port, request.path, headers,
            PolicyEpoch("native-runtime-header-compatibility", 0))
    }
}
