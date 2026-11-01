// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.schedule

import java.util.LinkedHashMap
import com.dsha.sidecar.net.rules.native.BackupDescriptor

data class CandidateCount(val total: Double, val error: Double, val keepErrorCount: Double, val successRate: Double)
data class BackupSnapshot(val value: String?, val remaining: List<String>, val counts: Map<String, CandidateCount>)

/**
 * Bounded, thread-safe adapter of source DynamicChoice's rank/count transition semantics.
 * Epoch, scheme/host/port, rule counter key, and candidate list are part of the selection boundary.
 */
class BackupSelector(private val maximumKeys: Int = 128, private val maximumCandidates: Int = 16) {
    init { require(maximumKeys in 1..4096 && maximumCandidates in 1..64) }
    private class Count(var total: Double, var error: Double = 0.0, var keep: Double = 0.0, var rate: Double = 0.5)
    private class State(val counts: LinkedHashMap<String, Count> = linkedMapOf(), var remaining: MutableList<String> = mutableListOf(), var value: String? = null)
    private val lock = Any()
    private val states = LinkedHashMap<String, State>(16, 0.75f, true)
    private var generation = 0L
    val size: Int get() = synchronized(lock) { states.size }

    /** Selection does not rerun within retries; feedback affects future exchanges. */
    fun select(request: ScheduledRequest, descriptor: BackupDescriptor): Selection {
        val input = ScheduleSupport.request(request)
        val candidates = listOf(descriptor.primary) + descriptor.alternatives.toList()
        require(candidates.size <= maximumCandidates && candidates.all {
            it.isNotEmpty() && it.length <= 8192 && it !in prototypeKeys
        })
        require(descriptor.counterKey.length <= 8192)
        val key = ScheduleSupport.hash(listOf(
            input.policy.policy, input.policy.epoch.toString(), input.scheme, input.host, input.port.toString(),
            descriptor.counterKey,
        ) + candidates)
        synchronized(lock) {
            val state = states[key] ?: State().also {
                setList(it, candidates)
                states[key] = it
                while (states.size > maximumKeys) states.remove(states.keys.first())
            }
            if (state.value == null) {
                val ranked = keys(state).sortedByDescending { state.counts.getValue(it).rate }
                setList(state, ranked)
            }
            val candidate = state.value ?: error("No backup candidate")
            count(state, candidate, false)
            return Selection(key, generation, candidate, state)
        }
    }

    inner class Selection internal constructor(
        private val key: String,
        private val startedGeneration: Long,
        val candidate: String,
        private val stateIdentity: Any,
    ) {
        /** Late failure of an older candidate records its rate without switching a newer candidate. */
        fun failure() = synchronized(lock) {
            val state = states[key]
            if (startedGeneration == generation && state != null && state === stateIdentity) count(state, candidate, true)
        }
        fun snapshot(): BackupSnapshot? = synchronized(lock) {
            states[key]?.takeIf { startedGeneration == generation && it === stateIdentity }?.let { state ->
                BackupSnapshot(state.value, state.remaining.toList(), keys(state).associateWith {
                    state.counts.getValue(it).let { count -> CandidateCount(count.total, count.error, count.keep, count.rate) }
                })
            }
        }
    }

    fun invalidate() = synchronized(lock) { generation++; states.clear() }

    private fun setList(state: State, candidates: List<String>) {
        state.remaining = candidates.toMutableList()
        var seed = candidates.size.toDouble()
        for (candidate in candidates) if (candidate !in state.counts) state.counts[candidate] = Count(seed--)
        state.value = state.remaining.removeFirstOrNull()
        state.value?.let { count(state, it, false) }
    }

    private fun count(state: State, candidate: String, failure: Boolean) {
        val count = state.counts.getValue(candidate)
        if (failure) { count.error++; count.keep++ } else count.keep = 0.0
        count.total++
        count.rate = 1.0 - count.error / count.total
        if (failure && state.value == candidate) {
            count.keep = 0.0
            count.total = 0.0
            count.error = 0.0
            state.value = state.remaining.removeFirstOrNull()
        }
    }

    private fun keys(state: State): List<String> {
        val (indices, other) = state.counts.keys.partition { key ->
            val value = key.toLongOrNull()
            value != null && value in 0..4294967294L && value.toString() == key
        }
        return indices.sortedBy { it.toLong() } + other
    }

    companion object {
        private val prototypeKeys = setOf("__proto__", "constructor", "toString", "valueOf", "hasOwnProperty",
            "toLocaleString", "isPrototypeOf", "propertyIsEnumerable", "__defineGetter__", "__defineSetter__",
            "__lookupGetter__", "__lookupSetter__")
    }
}
