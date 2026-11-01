package com.dsha.sidecar.net.http.native.schedule

import java.io.IOException
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.BackupDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScheduleOracleTest(private val id: String, private val fixture: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
    private fun headers(value: Any?): List<ActionHeader> = obj(value).map { ActionHeader(it.key, it.value as String) }

    @Test fun originalSourceStateAndPolicySnapshotsMatch() {
        val actual: Any? = when (fixture["type"]) {
            "cache" -> {
                val config = obj(fixture["config"])
                val seconds = (config["cacheSeconds"] as? Number)?.toDouble()
                    ?: (config["cacheDays"] as Number).toDouble() * 86400
                val policy = cachePolicy(seconds).copy(
                    controlType = config["cacheControlType"] as? String ?: "public",
                    immutable = config["cacheImmutable"] != false,
                    excludedStatuses = obj(config["cacheExcludeStatusCodeList"]).filterValues { it == true }.keys,
                )
                val input = request(headers = headers(fixture["headers"])).copy(method = fixture["method"] as? String ?: "GET")
                val head = ActionResponseHead(200, headers(fixture["responseHeaders"]))
                mapOf(
                    "maxAge" to seconds,
                    "conditionalStatus" to CacheCompatibility.conditional304(input, policy, ScheduleClock().millisNow)?.status,
                    "headers" to CacheCompatibility.responseHeaders(input, policy, head, ScheduleClock().millisNow)
                        .headers.associate { it.name.lowercase() to it.value },
                )
            }
            "choice" -> {
                val candidates = (fixture["candidates"] as List<*>).map { it as String }
                val selector = BackupSelector()
                val descriptor = BackupDescriptor(candidates.first(), candidates.drop(1), "synthetic")
                (fixture["failures"] as List<*>).map { failed ->
                    val chosen = selector.select(request(), descriptor)
                    if (failed == true) chosen.failure()
                    val snapshot = chosen.snapshot()!!
                    mapOf(
                        "candidate" to chosen.candidate, "value" to snapshot.value, "remaining" to snapshot.remaining,
                        "counts" to snapshot.counts.mapValues { (value, count) -> mapOf(
                            "value" to value, "total" to count.total, "error" to count.error,
                            "keepErrorCount" to count.keepErrorCount, "successRate" to count.successRate,
                        ) },
                    )
                }
            }
            "retry" -> {
                val method = fixture["method"] as? String ?: "GET"
                val input = request(headers = headers(fixture["headers"])).copy(method = method)
                val retryValue = fixture["retry"]
                val config = obj(retryValue)
                val retry = retryValue?.let {
                    retryPolicy((if (it is Number) it else config["times"] as Number).toInt()).copy(
                        statuses = (config["statuses"] as? List<*>)?.map { (it as Number).toInt() } ?: listOf(500),
                    )
                }
                val eligible = ReplayableRequest.eligibleToBuffer(input) && (retry == null || method in retry.methods)
                val replay = if (eligible) ReplayableRequest.empty(input) else ReplayableRequest.oneShot(input, ByteArray(0).inputStream())
                var calls = 0
                var released = 0
                val steps = fixture["attempts"] as List<*>
                val result = RequestAttemptScheduler().execute(replay, retry) { _, _ ->
                    when (val step = steps[minOf(calls++, steps.lastIndex)]) {
                        "error" -> AttemptResult.Failure(IOException("synthetic"), true)
                        else -> AttemptResult.Response((step as Number).toInt(), "response", release = { released++ })
                    }
                }
                mapOf("calls" to calls, "released" to released, "status" to (result.result as AttemptResult.Response).status, "eligible" to eligible)
            }
            else -> error("Unknown fixture category")
        }
        assertEquals(id, normalized(fixture["expected"]), normalized(actual))
    }

    private fun normalized(value: Any?): Any? = when (value) {
        is Number -> value.toDouble()
        is Map<*, *> -> value.mapValues { normalized(it.value) }
        is List<*> -> value.map(::normalized)
        else -> value
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any?>> = UpstreamScheduleOracle.cases.map { arrayOf(it["id"], it) }
    }
}
