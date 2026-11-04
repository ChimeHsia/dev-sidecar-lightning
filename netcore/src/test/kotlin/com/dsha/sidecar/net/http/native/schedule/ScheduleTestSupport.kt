package com.dsha.sidecar.net.http.native.schedule

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.CacheDescriptor
import com.dsha.sidecar.net.rules.native.RetryDescriptor
import org.junit.Assert.assertEquals

internal class ScheduleClock(var millisNow: Long = 1_793_577_600_000) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(millisNow)
    override fun millis(): Long = millisNow
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
}

internal fun cachePolicy(seconds: Double = 60.0) = CacheDescriptor(seconds, "public", true, 200, 303, emptySet())
internal fun retryPolicy(times: Int = 2, methods: List<String> = listOf("GET", "HEAD", "OPTIONS")) =
    RetryDescriptor(times, listOf(500, 502, 503), methods, true)
internal fun request(path: String = "/", headers: List<ActionHeader> = emptyList()) =
    ScheduledRequest("GET", "https", "example.test", 443, path, headers, PolicyEpoch("test", 1))
internal fun response(body: ByteArray = "body".toByteArray(), headers: List<ActionHeader> = emptyList(), status: Int = 200) =
    ActionResponseHead(status, listOf(ActionHeader("Content-Length", body.size.toString())) + headers, "Test")
internal fun store(
    cache: MemoryResponseCache,
    input: ScheduledRequest = request(),
    policy: CacheDescriptor = cachePolicy(),
    bytes: ByteArray = "body".toByteArray(),
    head: ActionResponseHead = response(bytes),
) {
    val capture = (cache.beginCapture(input, policy, head) as CaptureDecision.Capture).capture
    capture.append(bytes)
    assertEquals(CacheStoreResult.Stored, capture.complete())
}
