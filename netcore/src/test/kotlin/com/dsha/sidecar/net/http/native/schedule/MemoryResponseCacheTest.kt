package com.dsha.sidecar.net.http.native.schedule

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.dsha.sidecar.net.http.native.ActionHeader
import org.junit.Assert.*
import org.junit.Test

class MemoryResponseCacheTest {
    private val clock = ScheduleClock()
    private val cache = MemoryResponseCache(clock)

    @Test fun `encoded entity copies hit with Date and increasing Age until exact TTL expiry`() {
        val bytes = byteArrayOf(0, -1, 4, 0)
        store(cache, bytes = bytes, head = response(bytes, listOf(ActionHeader("Content-Encoding", "gzip"))))
        bytes.fill(3)
        val first = cache.get(request(), cachePolicy())!!
        assertArrayEquals(byteArrayOf(0, -1, 4, 0), first.bodyBytes())
        first.bodyBytes().fill(5)
        assertEquals(ScheduleSupport.date(clock.millisNow), fields(first.head.headers, "date").single())
        clock.millisNow += 5000
        assertEquals("5", fields(cache.get(request(), cachePolicy())!!.head.headers, "age").single())
        clock.millisNow += 55000
        assertNull(cache.get(request(), cachePolicy()))
        assertEquals(0, cache.totalBytes)
    }

    @Test fun `keys separate method authority scheme path policy config epoch and descriptor`() {
        store(cache)
        for (input in listOf(
            request().copy(method = "HEAD"), request().copy(host = "other.test"), request().copy(port = 8443),
            request().copy(scheme = "http"), request("/other"), request().copy(policy = PolicyEpoch("other", 1)),
            request().copy(policy = PolicyEpoch("test", 2)),
        )) assertNull(cache.get(input, cachePolicy()))
        assertNull(cache.get(request(), cachePolicy(61.0)))
    }

    @Test fun `Vary and accept encoding preserve exact absent empty and duplicate request values`() {
        val gzip = request(headers = listOf(ActionHeader("Accept-Encoding", "gzip"), ActionHeader("Accept-Language", "en")))
        store(cache, gzip, head = response(headers = listOf(ActionHeader("Vary", "Accept-Language"))))
        assertNotNull(cache.get(gzip, cachePolicy()))
        assertNull(cache.get(gzip.copy(headers = listOf(ActionHeader("Accept-Encoding", "gzip"), ActionHeader("Accept-Language", "fr"))), cachePolicy()))
        assertNull(cache.get(gzip.copy(headers = listOf(ActionHeader("Accept-Language", "en"))), cachePolicy()))
        val absent = request("/empty")
        store(cache, absent, head = response(headers = listOf(ActionHeader("Vary", "X-Value"))))
        assertNull(cache.get(absent.copy(headers = listOf(ActionHeader("X-Value", ""))), cachePolicy()))
    }

    @Test fun `changed Vary field set drops incompatible old variants`() {
        val input = request(headers = listOf(ActionHeader("X-A", "a"), ActionHeader("X-B", "b")))
        store(cache, input, head = response(headers = listOf(ActionHeader("Vary", "X-A"))))
        store(cache, input, bytes = "new".toByteArray(), head = response("new".toByteArray(), listOf(ActionHeader("Vary", "X-B"))))
        assertEquals(1, cache.size)
        assertEquals("new", cache.get(input.copy(headers = listOf(ActionHeader("X-B", "b"))), cachePolicy())!!.bodyBytes().toString(Charsets.UTF_8))
    }

    @Test fun `Vary star conditional range and no-cache requests bypass`() {
        assertEquals(CaptureDecision.Bypass(CacheBypass.VARY), cache.beginCapture(request(), cachePolicy(), response(headers = listOf(ActionHeader("Vary", "*")))))
        store(cache)
        for (header in listOf(
            ActionHeader("Cache-Control", "NO-CACHE"), ActionHeader("Cache-Control", "no-store"),
            ActionHeader("Pragma", "no-cache"), ActionHeader("If-None-Match", "tag"), ActionHeader("Range", "bytes=0-1"),
        )) {
            val input = request(headers = listOf(header))
            assertNull(cache.get(input, cachePolicy()))
            assertTrue(cache.beginCapture(input, cachePolicy(), response()) is CaptureDecision.Bypass)
        }
    }

    @Test fun `authorization cookies private no-store and Set-Cookie never enter public cache`() {
        for (name in listOf("Authorization", "Cookie")) {
            assertEquals(CaptureDecision.Bypass(CacheBypass.SENSITIVE),
                cache.beginCapture(request(headers = listOf(ActionHeader(name, "secret"))), cachePolicy(), response()))
        }
        for (header in listOf(ActionHeader("Cache-Control", "private"), ActionHeader("Set-Cookie", "a=1"))) {
            assertEquals(CaptureDecision.Bypass(CacheBypass.SENSITIVE), cache.beginCapture(request(), cachePolicy(), response(headers = listOf(header))))
        }
        for (control in listOf("no-cache", "no-store")) {
            assertEquals(CaptureDecision.Bypass(CacheBypass.RESPONSE_DIRECTIVE),
                cache.beginCapture(request(), cachePolicy(), response(headers = listOf(ActionHeader("Cache-Control", control)))))
        }
    }

    @Test fun `explicit private mode requires partition and binds credential values`() {
        val privateCache = MemoryResponseCache(clock, privacy = CachePrivacy.ISOLATED_PRIVATE)
        assertTrue(privateCache.beginCapture(request(), cachePolicy(), response()) is CaptureDecision.Bypass)
        val input = request(headers = listOf(ActionHeader("Authorization", "Bearer one"))).copy(privatePartition = "user1")
        store(privateCache, input, head = response(headers = listOf(ActionHeader("Cache-Control", "private, max-age=60"))))
        assertNotNull(privateCache.get(input, cachePolicy()))
        assertNull(privateCache.get(input.copy(privatePartition = "user2"), cachePolicy()))
        assertNull(privateCache.get(input.copy(headers = listOf(ActionHeader("Authorization", "Bearer two"))), cachePolicy()))
    }

    @Test fun `Date Age expires and server max-age cap descriptor freshness`() {
        val old = ScheduleSupport.date(clock.millisNow - 10000)
        store(cache, head = response(headers = listOf(ActionHeader("Date", old), ActionHeader("Age", "20"), ActionHeader("Cache-Control", "max-age=30"))))
        assertEquals("20", fields(cache.get(request(), cachePolicy())!!.head.headers, "Age").single())
        clock.millisNow += 10000
        assertNull(cache.get(request(), cachePolicy()))
        store(cache, request("/expires"), head = response(headers = listOf(ActionHeader("Expires", ScheduleSupport.date(clock.millisNow + 1000)))))
        clock.millisNow += 1000
        assertNull(cache.get(request("/expires"), cachePolicy()))
    }

    @Test fun `explicit request send time contributes response delay to initial Age`() {
        val capture = (cache.beginCapture(request(), cachePolicy(), response(headers = listOf(ActionHeader("Age", "5"))),
            requestSentAtMillis = clock.millisNow - 3000) as CaptureDecision.Capture).capture
        capture.append("body".toByteArray())
        assertEquals(CacheStoreResult.Stored, capture.complete())
        assertEquals("8", fields(cache.get(request(), cachePolicy())!!.head.headers, "age").single())
        assertNull(cache.get(request(headers = listOf(ActionHeader("Cache-Control", "max-age=0"))), cachePolicy()))
    }

    @Test fun `request freshness restrictions and malformed upstream age are conservative`() {
        store(cache)
        clock.millisNow += 10000
        assertNull(cache.get(request(headers = listOf(ActionHeader("Cache-Control", "max-age=5"))), cachePolicy()))
        assertNull(cache.get(request(headers = listOf(ActionHeader("Cache-Control", "min-fresh=55"))), cachePolicy()))
        assertNotNull(cache.get(request(headers = listOf(ActionHeader("Cache-Control", "max-age=20"))), cachePolicy()))
        for (header in listOf(ActionHeader("Age", "NaN"), ActionHeader("Date", "yesterday"), ActionHeader("Cache-Control", "max-age=bad"))) {
            assertTrue(cache.beginCapture(request(), cachePolicy(), response(headers = listOf(header))) is CaptureDecision.Bypass)
        }
    }

    @Test fun `future Date does not create negative Age and rollback invalidates instead of serving future entry`() {
        store(cache, head = response(headers = listOf(ActionHeader("Date", ScheduleSupport.date(clock.millisNow + 10000)))))
        assertEquals("0", fields(cache.get(request(), cachePolicy())!!.head.headers, "Age").single())
        clock.millisNow--
        assertNull(cache.get(request(), cachePolicy()))
    }

    @Test fun `LRU count and total byte limits evict while retaining bounded weight`() {
        val limited = MemoryResponseCache(clock, CacheLimits(maximumEntries = 2, maximumTotalBytes = 1000))
        store(limited, request("/a"))
        store(limited, request("/b"))
        assertNotNull(limited.get(request("/a"), cachePolicy()))
        store(limited, request("/c"))
        assertNull(limited.get(request("/b"), cachePolicy()))
        assertNotNull(limited.get(request("/a"), cachePolicy()))
        assertTrue(limited.totalBytes <= 1000)
        assertTrue(limited.size <= 2)
    }

    @Test fun `overlarge stream continues forwarding but capture discards without truncation`() {
        val limited = MemoryResponseCache(clock, CacheLimits(maximumEntryBytes = 4))
        val capture = (limited.beginCapture(request(), cachePolicy(), response().copy(headers = emptyList())) as CaptureDecision.Capture).capture
        val sink = ByteArrayOutputStream()
        for (bytes in listOf("abc".toByteArray(), "def".toByteArray(), "ghi".toByteArray())) {
            sink.write(bytes)
            capture.append(bytes)
        }
        assertEquals("abcdefghi", sink.toString("UTF-8"))
        assertEquals(CacheStoreResult.Bypassed(CacheBypass.BODY_TOO_LARGE), capture.complete())
        assertEquals(0, limited.activeCaptures)
        assertEquals(0, limited.capturedBytes)
        assertEquals(0, limited.size)
    }

    @Test fun `active capture budget and aggregate byte budget bound concurrent accumulation`() {
        val limited = MemoryResponseCache(clock, CacheLimits(maximumActiveCaptures = 1, maximumCaptureBytes = 3))
        val capture = (limited.beginCapture(request(), cachePolicy(), response()) as CaptureDecision.Capture).capture
        assertEquals(CaptureDecision.Bypass(CacheBypass.CAPACITY), limited.beginCapture(request("/other"), cachePolicy(), response()))
        assertTrue(capture.append(byteArrayOf(1, 2, 3)))
        assertFalse(capture.append(byteArrayOf(4)))
        assertEquals(0, limited.capturedBytes)
    }

    @Test fun `incomplete aborted cancelled or expired capture never publishes`() {
        val partial = (cache.beginCapture(request(), cachePolicy(), response()) as CaptureDecision.Capture).capture
        partial.append(byteArrayOf(1))
        assertEquals(CacheStoreResult.Bypassed(CacheBypass.INCOMPLETE), partial.complete())
        val aborted = (cache.beginCapture(request(), cachePolicy(), response()) as CaptureDecision.Capture).capture
        aborted.append("body".toByteArray())
        aborted.close()
        assertEquals(CacheStoreResult.Bypassed(CacheBypass.INCOMPLETE), aborted.complete())
        val cancelled = (cache.beginCapture(request(), cachePolicy(), response()) as CaptureDecision.Capture).capture
        cancelled.append("body".toByteArray())
        assertEquals(CacheStoreResult.Bypassed(CacheBypass.CANCELLED), cancelled.complete(ScheduleCancellation().apply { cancel() }))
        val expired = (cache.beginCapture(request(), cachePolicy(1.0), response()) as CaptureDecision.Capture).capture
        expired.append("body".toByteArray())
        clock.millisNow += 1000
        assertEquals(CacheStoreResult.Bypassed(CacheBypass.EXPIRED), expired.complete())
        assertEquals(0, cache.size)
    }

    @Test fun `invalidate revokes completed entries and active capture generation atomically`() {
        store(cache, request("/stored"))
        val pending = (cache.beginCapture(request(), cachePolicy(), response()) as CaptureDecision.Capture).capture
        pending.append("body".toByteArray())
        cache.invalidate()
        assertNull(cache.get(request("/stored"), cachePolicy()))
        assertEquals(CacheStoreResult.Bypassed(CacheBypass.INVALIDATED), pending.complete())
        assertEquals(0, cache.capturedBytes)
        store(cache)
        assertNotNull(cache.get(request(), cachePolicy()))
    }

    @Test fun `concurrent invalidate cannot allow old captures to repopulate`() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<CacheStoreResult> {
                val capture = (cache.beginCapture(request(), cachePolicy(), response()) as CaptureDecision.Capture).capture
                capture.append("body".toByteArray())
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                capture.complete()
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            cache.invalidate()
            release.countDown()
            assertEquals(CacheStoreResult.Bypassed(CacheBypass.INVALIDATED), future.get(5, TimeUnit.SECONDS))
            assertNull(cache.get(request(), cachePolicy()))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `partial trailer invalid framing and excluded statuses bypass before capture`() {
        for (head in listOf(
            response(status = 206), response(status = 500),
            response(headers = listOf(ActionHeader("Transfer-Encoding", "chunked"))),
            response(headers = listOf(ActionHeader("Trailer", "X-Value"))),
        )) assertTrue(cache.beginCapture(request(), cachePolicy(), head) is CaptureDecision.Bypass)
        assertTrue(cache.beginCapture(request(), cachePolicy().copy(excludedStatuses = setOf("200")), response()) is CaptureDecision.Bypass)
    }
}
