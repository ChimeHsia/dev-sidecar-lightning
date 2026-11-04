// SPDX-License-Identifier: MPL-2.0
// Based on @blue-frontier/mitmproxy 2.3.1 src/lib/choice/index.js.
package com.dsha.sidecar.net.dns

/**
 * Deterministic candidate selection and feedback, without DNS or time tracking.
 *
 * Candidates are nonempty strings other than Object.prototype property names.
 * Feedback may introduce candidates not present in the current backup list.
 * Empty selections have a null value and never create an "undefined" counter.
 * Returned collections are copies; changing them cannot change this instance.
 */
class DynamicChoice(val key: String = "") {
    data class Count(
        val total: Double,
        val error: Double,
        val keepErrorCount: Double,
        val successRate: Double,
    )

    data class Snapshot(
        val value: String?,
        val backupList: List<String>,
        val counts: Map<String, Count>,
    )

    private class MutableCount(
        var total: Double,
        var error: Double = 0.0,
        var keepErrorCount: Double = 0.0,
        var successRate: Double,
    ) {
        fun snapshot() = Count(total, error, keepErrorCount, successRate)
    }

    private val countMap = linkedMapOf<String, MutableCount>()
    private var backups = mutableListOf<String>()

    var value: String? = null
        private set

    val backupList: List<String>
        get() = backups.toList()

    fun snapshot(): Snapshot {
        val counts = linkedMapOf<String, Count>()
        for (candidate in orderedKeys()) {
            counts[candidate] = countMap.getValue(candidate).snapshot()
        }
        return Snapshot(value, backups.toList(), counts)
    }

    fun setBackupList(newBackupList: List<String>) {
        newBackupList.forEach(::requireCandidate)
        backups = newBackupList.toMutableList()
        var defaultTotal = newBackupList.size.toDouble()
        for (candidate in newBackupList) {
            if (candidate !in countMap) {
                countMap[candidate] = MutableCount(total = defaultTotal, successRate = 0.5)
                defaultTotal--
            }
        }
        value = takeNext()
        value?.let { doCount(it, false) }
    }

    fun countStart(value: String) {
        doCount(value, false)
    }

    fun doCount(candidate: String, isError: Boolean) {
        requireCandidate(candidate)
        val count = countMap.getOrPut(candidate) {
            MutableCount(total = 5.0, successRate = 1.0)
        }
        if (isError) {
            count.error++
            count.keepErrorCount++
        } else {
            count.keepErrorCount = 0.0
        }
        count.total++
        count.successRate = 1.0 - count.error / count.total

        if (isError && value == candidate) {
            // Upstream switches on the first failure and retains the computed rate.
            count.keepErrorCount = 0.0
            count.total = 0.0
            count.error = 0.0
            value = takeNext()
        }
    }

    fun doRank() {
        val candidates = orderedKeys().sortedWith { left, right ->
            countMap.getValue(right).successRate.compareTo(countMap.getValue(left).successRate)
        }
        setBackupList(candidates)
    }

    fun resetChoice(hostname: String): Boolean {
        requireCandidate(hostname)
        val candidates = mutableListOf<String>()
        for (candidate in orderedKeys()) {
            val count = countMap.getValue(candidate)
            count.keepErrorCount = 0.0
            count.total = 0.0
            count.error = 0.0
            count.successRate = 1.0
            if (candidate != hostname) candidates.add(candidate)
        }
        if (candidates.isEmpty()) return false
        backups = candidates
        value = takeNext()
        value?.let { doCount(it, false) }
        return true
    }

    private fun takeNext(): String? =
        if (backups.isEmpty()) null else backups.removeAt(0)

    private fun orderedKeys(): List<String> {
        // JS enumerates canonical array-index keys first, then other keys by insertion.
        val indices = mutableListOf<Pair<Long, String>>()
        val others = mutableListOf<String>()
        for (candidate in countMap.keys) {
            val index = candidate.toLongOrNull()
            if (index != null && index in 0L..4294967294L && index.toString() == candidate) {
                indices.add(index to candidate)
            } else {
                others.add(candidate)
            }
        }
        return indices.sortedBy { it.first }.map { it.second } + others
    }

    private fun requireCandidate(candidate: String) {
        require(candidate.isNotEmpty() && candidate !in prototypeKeys) {
            "Candidate must be nonempty and must not name an Object.prototype property"
        }
    }

    private companion object {
        val prototypeKeys = setOf(
            "__proto__", "constructor", "__defineGetter__", "__defineSetter__",
            "hasOwnProperty", "__lookupGetter__", "__lookupSetter__", "isPrototypeOf",
            "propertyIsEnumerable", "toString", "valueOf", "toLocaleString",
        )
    }
}
