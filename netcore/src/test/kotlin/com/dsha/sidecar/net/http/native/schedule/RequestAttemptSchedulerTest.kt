package com.dsha.sidecar.net.http.native.schedule

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.rules.native.BackupDescriptor
import org.junit.Assert.*
import org.junit.Test

class RequestAttemptSchedulerTest {
    private val scheduler = RequestAttemptScheduler()
    private val backup = BackupDescriptor("primary.test", listOf("backup.test"), "rule")

    @Test fun `configured status retries share budget with explicit retryable errors and release discarded response`() {
        val results = listOf<AttemptResult<String>>(
            AttemptResult.Failure(IOException("connect"), true), AttemptResult.Response(503, "unavailable"),
            AttemptResult.Response(200, "ok"),
        )
        var released = 0
        val result = scheduler.execute(ReplayableRequest.empty(request()), retryPolicy()) { context, body ->
            assertEquals(0, body.readBytes().size)
            when (val result = results[context.number - 1]) {
                is AttemptResult.Response -> result.copy(release = { released++ })
                is AttemptResult.Failure -> result
            }
        }
        assertEquals(3, result.attempts)
        assertEquals("ok", (result.result as AttemptResult.Response).value)
        assertEquals(1, released)
        assertEquals(0, scheduler.activeExchanges)
    }

    @Test fun `implicit retry only covers safe buffered transport failures not status errors`() {
        var calls = 0
        val result = scheduler.execute(ReplayableRequest.empty(request())) { _, _ ->
            calls++
            if (calls == 1) AttemptResult.Failure(IOException("connect"), true) else AttemptResult.Response(500, "done")
        }
        assertEquals(2, result.attempts)
        assertEquals(500, (result.result as AttemptResult.Response).status)
    }

    @Test fun `nonretryable error response started and unselected status never retry`() {
        for (failure in listOf(AttemptResult.Failure(IOException(), false), AttemptResult.Failure(IOException(), true, responseStarted = true))) {
            assertEquals(1, scheduler.execute(ReplayableRequest.empty(request()), retryPolicy()) { _, _ -> failure }.attempts)
        }
        assertEquals(1, scheduler.execute(ReplayableRequest.empty(request()), retryPolicy()) { _, _ -> AttemptResult.Response(404, "missing") }.attempts)
    }

    @Test fun `successful POST buffering replays identical bytes only with explicit side effect authorization`() {
        val input = request(headers = listOf(ActionHeader("Content-Length", "4"))).copy(method = "POST")
        val replay = ReplayableRequest.buffer(input, "body".byteInputStream())
        val bodies = mutableListOf<String>()
        val result = scheduler.execute(replay, retryPolicy(methods = listOf("POST")), allowNonIdempotentReplay = true) { context, body ->
            bodies.add(body.readBytes().toString(Charsets.UTF_8))
            AttemptResult.Response(if (context.number == 1) 500 else 200, "result")
        }
        assertEquals(2, result.attempts)
        assertEquals(listOf("body", "body"), bodies)
    }

    @Test fun `non-idempotent POST never retries ambiguous delivery or status by default`() {
        val input = request(headers = listOf(ActionHeader("Content-Length", "4"))).copy(method = "POST")
        val replay = ReplayableRequest.buffer(input, "body".byteInputStream())
        assertEquals(1, scheduler.execute(replay, retryPolicy(methods = listOf("POST"))) { _, _ ->
            AttemptResult.Failure(IOException("ambiguous"), true)
        }.attempts)
        assertEquals(1, scheduler.execute(replay, retryPolicy(methods = listOf("POST"))) { _, _ ->
            AttemptResult.Response(500, "possible side effect")
        }.attempts)
    }

    @Test fun `configured buffered POST may retry a confirmed not sent connection failure`() {
        val input = request(headers = listOf(ActionHeader("Content-Length", "1"))).copy(method = "POST")
        val replay = ReplayableRequest.buffer(input, "x".byteInputStream())
        val result = scheduler.execute(replay, retryPolicy(methods = listOf("POST"))) { context, _ ->
            if (context.number == 1) AttemptResult.Failure(IOException(), true, requestMayHaveBeenSent = false)
            else AttemptResult.Response(200, "ok")
        }
        assertEquals(2, result.attempts)
    }

    @Test fun `chunked Expect missing length malformed length and oversize bodies are not buffered`() {
        val bad = listOf(
            listOf(ActionHeader("Transfer-Encoding", "chunked")), listOf(ActionHeader("Expect", "100-continue")),
            emptyList(), listOf(ActionHeader("Content-Length", "9999999")), listOf(ActionHeader("Content-Length", "-1")),
        )
        for (headers in bad) {
            val input = request(headers = headers).copy(method = "POST")
            val stream = object : InputStream() { override fun read(): Int = error("Must not consume") }
            assertThrows(IllegalArgumentException::class.java) { ReplayableRequest.buffer(input, stream) }
        }
    }

    @Test fun `partial and overflowing buffers fail terminally rather than create replay token`() {
        val input = request(headers = listOf(ActionHeader("Content-Length", "4"))).copy(method = "POST")
        assertThrows(IOException::class.java) { ReplayableRequest.buffer(input, "ab".byteInputStream()) }
        assertThrows(IOException::class.java) { ReplayableRequest.buffer(input, "abcdef".byteInputStream(), 4) }
        assertThrows(IllegalArgumentException::class.java) { ReplayableRequest.empty(input) }
    }

    @Test fun `one shot streams run once even with configured retry and remain caller owned`() {
        val stream = object : ByteArrayInputStream("body".toByteArray()) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val input = request(headers = listOf(ActionHeader("Content-Length", "4"))).copy(method = "POST")
        val replay = ReplayableRequest.oneShot(input, stream)
        val result = scheduler.execute(replay, retryPolicy(methods = listOf("POST")), allowNonIdempotentReplay = true) { _, body ->
            assertEquals("body", body.readBytes().toString(Charsets.UTF_8))
            AttemptResult.Response(500, "no replay")
        }
        assertEquals(1, result.attempts)
        assertFalse(stream.closed)
        assertThrows(IllegalStateException::class.java) { replay.openBody() }
    }

    @Test fun `method filter eligibility and descriptor body bound are rechecked not trusted`() {
        val replay = ReplayableRequest.empty(request())
        assertEquals(1, scheduler.execute(replay, retryPolicy(methods = listOf("HEAD"))) { _, _ ->
            AttemptResult.Response(500, "no")
        }.attempts)
        assertEquals(1, scheduler.execute(replay, retryPolicy().copy(requestBodyEligible = false)) { _, _ ->
            AttemptResult.Response(500, "no")
        }.attempts)
    }

    @Test fun `retry candidate stays fixed while failure feedback changes next exchange selection`() {
        val result = scheduler.execute(ReplayableRequest.empty(request()), retryPolicy(1), backup) { context, _ ->
            if (context.number == 1) AttemptResult.Failure(IOException(), true) else AttemptResult.Response(200, "ok")
        }
        assertEquals(listOf("primary.test", "primary.test"), result.targets)
        val next = scheduler.execute(ReplayableRequest.empty(request()), backup = backup) { context, _ -> AttemptResult.Response(200, context.target) }
        assertEquals(listOf("backup.test"), next.targets)
    }

    @Test fun `status retried before final response does not count as final backup failure`() {
        val selector = BackupSelector()
        val scheduling = RequestAttemptScheduler(backups = selector)
        scheduling.execute(ReplayableRequest.empty(request()), retryPolicy(1), backup) { context, _ ->
            AttemptResult.Response(if (context.number == 1) 500 else 200, "body")
        }
        assertEquals("primary.test", selector.select(request(), backup).candidate)
    }

    @Test fun `cancellation before start prevents any attempt and mid response releases discarded result`() {
        val cancelled = ScheduleCancellation().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            scheduler.execute<String>(ReplayableRequest.empty(request()), cancellation = cancelled) { _, _ -> error("Must not invoke") }
        }
        val during = ScheduleCancellation()
        var released = 0
        assertThrows(CancellationException::class.java) {
            scheduler.execute(ReplayableRequest.empty(request()), retryPolicy(), cancellation = during) { _, _ ->
                during.cancel()
                AttemptResult.Response(200, "result", release = { released++ })
            }
        }
        assertEquals(1, released)
        assertEquals(0, scheduler.activeExchanges)
    }

    @Test fun `buffer cancellation respects limits and leaves caller stream open`() {
        val stream = object : ByteArrayInputStream("x".toByteArray()) {
            var closed = false
            override fun close() { closed = true }
        }
        assertThrows(CancellationException::class.java) {
            ReplayableRequest.buffer(request(), stream, cancellation = ScheduleCancellation().apply { cancel() })
        }
        assertFalse(stream.closed)
    }

    @Test fun `explicit cancellation and interruption failures never retry`() {
        assertThrows(CancellationException::class.java) {
            scheduler.execute<String>(ReplayableRequest.empty(request()), retryPolicy()) { _, _ ->
                AttemptResult.Failure(CancellationException("transport cancelled"), true)
            }
        }
        try {
            assertThrows(InterruptedException::class.java) {
                scheduler.execute<String>(ReplayableRequest.empty(request()), retryPolicy()) { _, _ ->
                    AttemptResult.Failure(InterruptedException("transport interrupted"), true)
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(0, scheduler.activeExchanges)
    }

    @Test fun `concurrent exchange and replay byte budgets fail fast and recover after completion`() {
        val scheduling = RequestAttemptScheduler(AttemptLimits(maximumConcurrentExchanges = 1, maximumActiveReplayBytes = 4))
        val replay = ReplayableRequest.buffer(request(headers = listOf(ActionHeader("Content-Length", "4"))), "body".byteInputStream())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit(Callable {
                scheduling.execute(replay) { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); AttemptResult.Response(200, "ok") }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(4, scheduling.activeReplayBytes)
            assertThrows(IllegalStateException::class.java) { scheduling.execute(replay) { _, _ -> AttemptResult.Response(200, "no") } }
            release.countDown()
            future.get(5, TimeUnit.SECONDS)
            assertEquals(0, scheduling.activeReplayBytes)
            assertEquals(1, scheduling.execute(replay) { _, _ -> AttemptResult.Response(200, "ok") }.attempts)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `transport callback exception and release exception free scheduler budgets without blind retry`() {
        assertThrows(IOException::class.java) {
            scheduler.execute<String>(ReplayableRequest.empty(request()), retryPolicy()) { _, _ -> throw IOException("callback") }
        }
        assertEquals(0, scheduler.activeExchanges)
        assertThrows(IOException::class.java) {
            scheduler.execute(ReplayableRequest.empty(request()), retryPolicy()) { _, _ ->
                AttemptResult.Response(500, "discard", release = { throw IOException("release") })
            }
        }
        assertEquals(0, scheduler.activeExchanges)
    }

    @Test fun `retry count is bounded and final response ownership transfers to caller`() {
        var releases = 0
        val result = scheduler.execute(ReplayableRequest.empty(request()), retryPolicy(2)) { _, _ ->
            AttemptResult.Response(500, "last", release = { releases++ })
        }
        assertEquals(3, result.attempts)
        assertEquals(2, releases)
        (result.result as AttemptResult.Response).release()
        assertEquals(3, releases)
        assertThrows(IllegalArgumentException::class.java) {
            scheduler.execute<String>(ReplayableRequest.empty(request()), retryPolicy(99)) { _, _ -> error("Must not invoke") }
        }
    }

    @Test fun `backup capacity epoch port policy and late feedback are isolated`() {
        val selector = BackupSelector(maximumKeys = 2)
        val first = selector.select(request(), backup)
        val second = selector.select(request().copy(port = 8443), backup)
        first.failure()
        assertEquals("backup.test", first.snapshot()!!.value)
        assertEquals("primary.test", second.snapshot()!!.value)
        val epoch = selector.select(request().copy(policy = PolicyEpoch("test", 2)), backup)
        assertEquals("primary.test", epoch.candidate)
        assertEquals(2, selector.size)
        first.failure()
        assertNull(first.snapshot())
        selector.invalidate()
        epoch.failure()
        assertNull(epoch.snapshot())
    }
}
