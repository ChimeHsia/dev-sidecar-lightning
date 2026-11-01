package com.dsha.sidecar.net.http.native

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import com.dsha.sidecar.net.rules.native.*
import org.junit.Assert.*
import org.junit.Test

class RuleActionExecutorTest {
    private val request = RuleRequest("example.test", "/file/a%20b.zip")
    private val html = "<html><head></head><body>caf\u00e9 \u4e2d\ud83d\ude80</body></html>".toByteArray()
    private fun plan(config: Map<String, Any?> = emptyMap(), input: RuleRequest = request): RequestPlan =
        RulePlanner(listOf(mapOf("intercepts" to mapOf(input.host to mapOf(".*" to config))))).plan(input)
    private fun head(bytes: ByteArray = html, extra: List<ActionHeader> = emptyList(), status: Int = 200) =
        ActionResponseHead(status, listOf(ActionHeader("Content-Type", "text/html; charset=utf-8"),
            ActionHeader("Content-Length", bytes.size.toString())) + extra, "OK")
    private fun header(head: ActionResponseHead, name: String): String? = head.headers.firstOrNull { it.name.equals(name, true) }?.value
    private val executor = RuleActionExecutor()

    @Test fun `header-only API leaves 65 MiB body unread and outside buffer quotas`() {
        val size = 65L * 1024 * 1024
        var reads = 0L
        val source = object : InputStream() {
            var remaining = size
            override fun read(): Int { reads++; return if (remaining-- > 0) 97 else -1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                bytes.fill(97, offset, offset + count)
                remaining -= count
                return count
            }
        }
        val input = ActionResponseHead(200, listOf(ActionHeader("Content-Length", size.toString()), ActionHeader("Content-Encoding", "gzip")))
        val tiny = RuleActionExecutor(ActionLimits(maximumInputBytes = 0, maximumDecodedBytes = 0, maximumOutputBytes = 0))
        val result = tiny.transformResponseHead(plan(mapOf("options" to true)), request, input, true)
        assertEquals(0L, reads)
        assertEquals(size.toString(), header(result.head, "Content-Length"))
        assertEquals("gzip", header(result.head, "Content-Encoding"))
        var copied = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { copied++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { copied += length }
        }
        source.copyTo(sink)
        assertEquals(size, copied)
    }

    @Test fun `header-only preserves chunked framing exactly and never requires entity`() {
        val upstream = ActionResponseHead(200, listOf(ActionHeader("Transfer-Encoding", "chunked"), ActionHeader("Content-Encoding", "br")))
        val changed = executor.transformResponseHead(
            plan(mapOf("responseReplace" to mapOf("headers" to mapOf("X-Added" to "yes")))), request, upstream, true,
        )
        assertEquals("chunked", header(changed.head, "transfer-encoding"))
        assertEquals("br", header(changed.head, "content-encoding"))
        assertNull(header(changed.head, "content-length"))
    }

    @Test fun `framing-changing header actions reject before any body read or write`() {
        for ((key, value) in listOf("Content-Length" to "1", "Content-Encoding" to "gzip", "Transfer-Encoding" to "chunked")) {
            val input = object : InputStream() { override fun read(): Int = error("Must not read") }
            val sink = ByteArrayOutputStream()
            assertThrows(RuleActionException::class.java) {
                executor.executeResponse(plan(mapOf("responseReplace" to mapOf("headers" to mapOf(key to value)))),
                    request, head(), input, sink, true)
            }
            assertEquals(0, sink.size())
        }
    }

    @Test fun `same Content-Length action is accepted without entity buffering`() {
        val input = head()
        val result = executor.transformResponseHead(
            plan(mapOf("responseReplace" to mapOf("headers" to mapOf("Content-Length" to html.size.toString())))),
            request, input, true,
        )
        assertEquals(html.size.toString(), header(result.head, "content-length"))
    }

    @Test fun `request and static response cannot relabel body framing or compression`() {
        val input = request.copy(headers = mapOf("content-length" to "20"))
        assertThrows(RuleActionException::class.java) {
            executor.executeRequest(plan(mapOf("requestReplace" to mapOf("headers" to mapOf("content-length" to "1"))), input), input)
        }
        for (headers in listOf(mapOf("Content-Encoding" to "gzip"), mapOf("Content-Length" to "999"))) {
            assertThrows(RuleActionException::class.java) {
                executor.executeRequest(plan(mapOf("success" to mapOf("text" to "ok", "headers" to headers))), request)
            }
        }
    }

    @Test fun `request uses already resolved route headers and stripped download path`() {
        val input = request.copy(path = "/file/a.zip?DS_DOWNLOAD=1", headers = mapOf("cookie" to "secret"))
        val planned = plan(mapOf(
            "requestReplace" to mapOf("doDownload" to true, "headers" to mapOf("cookie" to "[remove]", "x-added" to "yes")),
            "proxy" to "mirror.test",
        ), input)
        val result = executor.executeRequest(planned, input) as RequestExecution.Forward
        assertEquals("mirror.test", result.request.host)
        assertEquals("/file/a.zip", result.request.path)
        assertEquals("mirror.test", result.request.headers.single { it.name == "host" }.value)
        assertFalse(result.request.headers.any { it.name == "cookie" })
        assertEquals("yes", result.request.headers.single { it.name == "x-added" }.value)
    }

    @Test fun `static terminal writes valid complete UTF8 wire bytes and HEAD omits entity`() {
        for (method in listOf("GET", "HEAD")) {
            val input = request.copy(method = method)
            val body = "caf\u00e9 \u4e2d\ud83d\ude80"
            val result = executor.executeRequest(plan(mapOf("success" to mapOf("status" to 201, "text" to body)), input), input)
                as RequestExecution.Terminal
            assertEquals(body.toByteArray().size.toString(), header(result.response.head, "content-length"))
            assertArrayEquals(if (method == "HEAD") ByteArray(0) else body.toByteArray(), result.response.bodyBytes())
            val sink = ByteArrayOutputStream()
            result.response.writeHttp1(sink)
            assertTrue(sink.toString("ISO-8859-1").startsWith("HTTP/1.1 201 "))
        }
    }

    @Test fun `static abort 204 has no entity and no Content-Length`() {
        val result = executor.executeRequest(plan(mapOf("abort" to mapOf("status" to 204, "text" to "discard"))), request)
            as RequestExecution.Terminal
        assertEquals(0, result.response.bodySize)
        assertNull(header(result.response.head, "content-length"))
    }

    @Test fun `OPTIONS requires explicit Date and never reads clock`() {
        val input = request.copy(method = "OPTIONS", headers = mapOf("origin" to "https://caller.test"))
        val planned = plan(mapOf("options" to true), input)
        assertThrows(RuleActionException::class.java) { executor.executeRequest(planned, input) }
        val date = "Mon, 2 Nov 2026 00:00:00 GMT"
        val result = executor.executeRequest(planned, input, date) as RequestExecution.Terminal
        assertEquals(date, header(result.response.head, "date"))
    }

    @Test fun `cache retry backup and unsupported script plans fail explicitly`() {
        for (config in listOf(
            mapOf("cacheDays" to 1), mapOf("retry" to 2),
            mapOf("proxy" to "mirror.test", "backup" to listOf("backup.test")), mapOf("script" to listOf("custom")),
        )) {
            val failure = assertThrows(RuleActionException::class.java) { executor.executeRequest(plan(config), request) }
            assertEquals(ActionFailure.UNSUPPORTED_ACTION, failure.failure)
        }
        assertThrows(RuleActionException::class.java) {
            executor.transformResponseHead(plan(mapOf("cacheDays" to 1)), request, head(), true)
        }
    }

    @Test fun `response gate skips real header actions while enabled gate applies them`() {
        val planned = plan(mapOf("responseReplace" to mapOf("headers" to mapOf("X-Test" to "yes"))))
        assertNull(header(executor.transformResponseHead(planned, request, head(), false).head, "x-test"))
        assertEquals("yes", header(executor.transformResponseHead(planned, request, head(), true).head, "x-test"))
    }

    @Test fun `gzip insertion recalculates encoded byte length and leaves caller streams open`() {
        val bytes = gzip(html)
        val input = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val prepared = executor.prepareResponse(plan(), request, head(bytes, listOf(ActionHeader("Content-Encoding", "gzip"))),
            input, true, ResolvedHtmlAppend(head = "<!-- \u4e2d -->"))
        assertFalse(input.closed)
        val decoded = GZIPInputStream(ByteArrayInputStream(prepared.bodyBytes())).use { it.readBytes() }
        assertEquals(html.toString(Charsets.UTF_8).replace("</head>", "<!-- \u4e2d --></head>"), decoded.toString(Charsets.UTF_8))
        assertEquals(prepared.bodySize.toString(), header(prepared.head, "content-length"))
        assertEquals("gzip", header(prepared.head, "content-encoding"))
    }

    @Test fun `Brotli injection fails before sink write and unmodified Brotli passes byte exact`() {
        val bytes = byteArrayOf(1, 3, 4, 5)
        val upstream = head(bytes, listOf(ActionHeader("Content-Encoding", "br")))
        val sink = ByteArrayOutputStream()
        val failure = assertThrows(RuleActionException::class.java) {
            executor.executeResponse(plan(), request, upstream, ByteArrayInputStream(bytes), sink, true, ResolvedHtmlAppend(head = "x"))
        }
        assertEquals(ActionFailure.UNSUPPORTED_ENCODING, failure.failure)
        assertEquals(0, sink.size())
        assertArrayEquals(bytes, executor.prepareResponse(plan(), request, upstream, ByteArrayInputStream(bytes), true).bodyBytes())
    }

    @Test fun `input limit failure never emits truncated success`() {
        val tiny = RuleActionExecutor(ActionLimits(maximumInputBytes = html.size - 1))
        val sink = ByteArrayOutputStream()
        val failure = assertThrows(RuleActionException::class.java) {
            tiny.executeResponse(plan(), request, head(), ByteArrayInputStream(html), sink, true)
        }
        assertEquals(ActionFailure.BODY_LIMIT, failure.failure)
        assertEquals(0, sink.size())
    }

    @Test fun `gzip and deflate decompression bomb limits fail before output`() {
        val plain = "<head>${"a".repeat(8192)}</head>".toByteArray()
        val deflate = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(plain) } }.toByteArray()
        for ((bytes, encoding) in listOf(gzip(plain) to "gzip", deflate to "deflate")) {
            val sink = ByteArrayOutputStream()
            val failure = assertThrows(RuleActionException::class.java) {
                RuleActionExecutor(ActionLimits(maximumDecodedBytes = 100)).executeResponse(
                    plan(), request, head(bytes, listOf(ActionHeader("Content-Encoding", encoding))),
                    ByteArrayInputStream(bytes), sink, true, ResolvedHtmlAppend(head = "x"),
                )
            }
            assertEquals(ActionFailure.BODY_LIMIT, failure.failure)
            assertEquals(0, sink.size())
        }
    }

    @Test fun `append and output limits fail before sink headers`() {
        for (limits in listOf(
            ActionLimits(maximumAppendBytes = 1), ActionLimits(maximumOutputBytes = 1),
            ActionLimits(maximumDecodedBytes = html.size),
        )) {
            val sink = ByteArrayOutputStream()
            assertThrows(RuleActionException::class.java) {
                RuleActionExecutor(limits).executeResponse(plan(), request, head(), ByteArrayInputStream(html), sink,
                    true, ResolvedHtmlAppend(head = "large"))
            }
            assertEquals(0, sink.size())
        }
    }

    @Test fun `malformed compression cannot send a successful head`() {
        val sink = ByteArrayOutputStream()
        val invalid = byteArrayOf(0, 1, 2)
        val failure = assertThrows(RuleActionException::class.java) {
            executor.executeResponse(plan(), request, head(invalid, listOf(ActionHeader("Content-Encoding", "gzip"))),
                ByteArrayInputStream(invalid), sink, true, ResolvedHtmlAppend(head = "x"))
        }
        assertEquals(ActionFailure.MALFORMED_BODY, failure.failure)
        assertEquals(0, sink.size())
    }

    @Test fun `boundary split tags and multibyte text remain intact`() {
        val input = object : ByteArrayInputStream(html) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(1, length))
        }
        val prepared = executor.prepareResponse(plan(), request, head(), input, true, ResolvedHtmlAppend(head = "<i>\u4e2d</i>"))
        assertArrayEquals(html.toString(Charsets.UTF_8).replace("</head>", "<i>\u4e2d</i></head>").toByteArray(), prepared.bodyBytes())
    }

    @Test fun `opaque invalid UTF8 bytes are never decoded or corrupted on pass through`() {
        val binary = byteArrayOf(0, -1, -2, -128, 13, 10, 0)
        val prepared = executor.prepareResponse(plan(), request,
            ActionResponseHead(200, listOf(ActionHeader("Content-Length", binary.size.toString()))),
            ByteArrayInputStream(binary), false)
        assertArrayEquals(binary, prepared.bodyBytes())
    }

    @Test fun `non200 replacement skipped and insertion primitive independently follows middleware gate`() {
        val planned = plan(mapOf("responseReplace" to mapOf("headers" to mapOf("X-New" to "yes"))))
        val prepared = executor.prepareResponse(planned, request, head(status = 404), ByteArrayInputStream(html), true,
            ResolvedHtmlAppend(head = "x"))
        assertNull(header(prepared.head, "x-new"))
        assertTrue(prepared.bodyBytes().toString(Charsets.UTF_8).contains("x</head>"))
    }

    @Test fun `charset mismatch and malformed append Unicode are explicit before output`() {
        for ((type, append) in listOf("text/html; charset=utf-16" to "x", "text/html; charset=us-ascii" to "\u4e2d", "text/html" to "\ud800")) {
            val upstream = ActionResponseHead(200, listOf(ActionHeader("Content-Type", type), ActionHeader("Content-Length", html.size.toString())))
            assertThrows(RuleActionException::class.java) {
                executor.prepareResponse(plan(), request, upstream, ByteArrayInputStream(html), true, ResolvedHtmlAppend(head = append))
            }
        }
    }

    @Test fun `framing ambiguity length mismatch header injection and trailers reject`() {
        val badHeads = listOf(
            head(extra = listOf(ActionHeader("Transfer-Encoding", "chunked"))),
            head(extra = listOf(ActionHeader("Content-Length", html.size.toString()))),
            head(extra = listOf(ActionHeader("X-Test", "bad\r\nheader"))),
            head(extra = listOf(ActionHeader("Trailer", "X-Trailer"))),
            ActionResponseHead(200, listOf(ActionHeader("Content-Length", "999"))),
        )
        for (upstream in badHeads) {
            val sink = ByteArrayOutputStream()
            assertThrows(RuleActionException::class.java) {
                executor.executeResponse(plan(), request, upstream, ByteArrayInputStream(html), sink, true)
            }
            assertEquals(0, sink.size())
        }
    }

    @Test fun `bounded preparation reframes dechunked body and removes nominated hop headers`() {
        val upstream = ActionResponseHead(200, listOf(ActionHeader("Transfer-Encoding", "chunked"),
            ActionHeader("Connection", "x-hop"), ActionHeader("X-Hop", "remove"), ActionHeader("Set-Cookie", "a=1"),
            ActionHeader("Set-Cookie", "b=2")))
        val prepared = executor.prepareResponse(plan(), request, upstream, ByteArrayInputStream(html), false)
        assertNull(header(prepared.head, "transfer-encoding"))
        assertNull(header(prepared.head, "x-hop"))
        assertEquals(html.size.toString(), header(prepared.head, "content-length"))
        assertEquals(listOf("a=1", "b=2"), prepared.head.headers.filter { it.name.equals("set-cookie", true) }.map { it.value })
    }

    @Test fun `HEAD and 304 preserve metadata Content-Length without reading representation`() {
        for ((input, status) in listOf(request.copy(method = "HEAD") to 200, request to 304)) {
            val upstream = ActionResponseHead(status, listOf(ActionHeader("Content-Length", "1234567")))
            val prepared = executor.prepareResponse(plan(input = input), input, upstream, ByteArrayInputStream(ByteArray(0)), true)
            assertEquals(0, prepared.bodySize)
            assertEquals("1234567", header(prepared.head, "content-length"))
        }
    }

    @Test fun `caller headers and returned bytes cannot mutate prepared response`() {
        val original = mutableListOf(ActionHeader("Content-Length", html.size.toString()))
        val prepared = executor.prepareResponse(plan(), request, ActionResponseHead(200, original), ByteArrayInputStream(html), true)
        original.clear()
        prepared.bodyBytes().fill(0)
        assertArrayEquals(html, prepared.bodyBytes())
        assertEquals(html.size.toString(), header(prepared.head, "content-length"))
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()
}
