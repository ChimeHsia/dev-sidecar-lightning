// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.schedule

import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.CacheDescriptor

/**
 * Source-compatible timestamp 304/header operations, separate from native response storage.
 * No implicit clock, Date/Age invention, or authorization/private check is added to these helpers.
 */
object CacheCompatibility {
    fun conditional304(request: ScheduledRequest, policy: CacheDescriptor, nowMillis: Long): ActionResponseHead? {
        if (request.method != "GET") return null
        if (listOf("cache-control", "pragma").any { name ->
            fields(request.headers, name).any { it.contains("no-cache") || it.contains("no-store") }
        }) return null
        val modified = fields(request.headers, "if-modified-since").firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val timestamp = if (modified.matches(Regex("\\s*[0-9]+\\s*"))) modified.trim().toLongOrNull()
            else ScheduleSupport.parseDate(modified)
        if (timestamp == null || nowMillis.toDouble() - timestamp > policy.maxAgeSeconds * 1000) return null
        return ActionResponseHead(304, listOf(ActionHeader("DS-Interceptor", "cache: ${number(policy.maxAgeSeconds)}")))
    }

    fun responseHeaders(request: ScheduledRequest, policy: CacheDescriptor, head: ActionResponseHead, nowMillis: Long): ActionResponseHead {
        require(policy.maxAgeSeconds.isFinite() && policy.maxAgeSeconds > 0)
        if (request.method != "GET" || head.status.toString() in policy.excludedStatuses ||
            head.status !in minOf(policy.minimumStatus, policy.maximumStatus)..maxOf(policy.minimumStatus, policy.maximumStatus)) return head
        val headers = head.headers.toMutableList()
        var age = policy.maxAgeSeconds
        val original = fields(headers, "cache-control").lastOrNull()
        val originalAge = original?.let { Regex("max-age=([0-9]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (originalAge != null && originalAge > age) {
            if (policy.immutable && !original.contains("immutable")) age = originalAge
            else {
                ScheduleSupport.set(headers, "DS-Cache-Response-Interceptor", "skip: ${number(originalAge)} > ${number(age)}")
                return head.copy(headers = headers)
            }
        }
        ScheduleSupport.set(headers, "Cache-Control", "${policy.controlType}, max-age=${number(age + 1)}${if (policy.immutable) ", immutable" else ""}")
        ScheduleSupport.set(headers, "Last-Modified", ScheduleSupport.date(nowMillis))
        val expiry = nowMillis.toDouble() + age * 1000
        require(expiry in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble())
        ScheduleSupport.set(headers, "Expires", ScheduleSupport.date(expiry.toLong()))
        ScheduleSupport.set(headers, "DS-Cache-Response-Interceptor", number(age))
        return head.copy(headers = headers)
    }

    private fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
