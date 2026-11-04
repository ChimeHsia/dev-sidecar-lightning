@file:OptIn(ExperimentalJsExport::class)

import com.dsha.sidecar.net.dns.DynamicChoice

/**
 * One retained selector per upstream Node DynamicChoice, including DNS IpCache.
 * Time, cache ownership, and DNS remain in the original Node implementation.
 */
@JsExport
class KotlinDynamicChoiceRuntime(key: String) {
    private val choice = DynamicChoice(key)

    fun setBackupList(candidates: Array<String>) {
        choice.setBackupList(candidates.toList())
    }

    fun countStart(candidate: String) {
        choice.countStart(candidate)
    }

    fun doCount(candidate: String, isError: Boolean) {
        choice.doCount(candidate, isError)
    }

    fun doRank() {
        choice.doRank()
    }

    fun resetChoice(hostname: String): Boolean = choice.resetChoice(hostname)

    fun snapshot(): dynamic {
        val snapshot = choice.snapshot()
        val result = js("({})")
        result.value = snapshot.value
        result.backupList = snapshot.backupList.toTypedArray()
        val counts = js("({})")
        for ((candidate, count) in snapshot.counts) {
            val record = js("({})")
            record.value = candidate
            record.total = count.total
            record.error = count.error
            record.keepErrorCount = count.keepErrorCount
            record.successRate = count.successRate
            counts[candidate] = record
        }
        result.countMap = counts
        return result
    }
}

@JsExport
fun createDynamicChoice(key: String): KotlinDynamicChoiceRuntime = KotlinDynamicChoiceRuntime(key)
