package com.dsha.sidecar.net.http.native.script

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.rules.native.ResponseAction
import org.junit.Assert.*
import org.junit.Test

class HtmlScriptInjectorTest {
    private val request = scriptRequest()
    private val injector = HtmlScriptInjector()

    @Test fun `split tags and UTF8 byte fragments inject once without document reencoding`() {
        val stream = object : ByteArrayInputStream(scriptHtml) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(1, length))
        }
        val head = scriptHead()
        val tags = readyTags()
        val prepared = (injector.prepare(request, head, tags, stream) as ScriptResponseDecision.Prepared).response
        val expected = scriptHtml.toString(Charsets.UTF_8).replace("</head>", (tags as ScriptTagDecision.Ready).headText + "</head>")
        assertArrayEquals(expected.toByteArray(), prepared.bodyBytes())
        assertEquals(1, Regex("/____ds_script____/alpha").findAll(prepared.bodyBytes().toString(Charsets.UTF_8)).count())
    }

    @Test fun `mixed case head attributes comments and raw text cannot redirect insertion to a literal`() {
        val html = "<html><!-- </head> --><HeAd data-x=\"</head>\"><script>const t='</head>';</script>" +
            "<style>x::after{content:'</head>'}</style><title>literal </head></title></hEaD><body>x</body></html>"
        val bytes = html.toByteArray()
        val head = scriptHead(bytes)
        val tags = readyTags(head = head) as ScriptTagDecision.Ready
        val prepared = (injector.prepare(request, head, tags, bytes.inputStream()) as ScriptResponseDecision.Prepared).response
        assertEquals(html.replace("</hEaD>", tags.headText + "</hEaD>"), prepared.bodyBytes().toString(Charsets.UTF_8))
    }

    @Test fun `missing head or malformed HTML is blocked after bounded consume with no sink writes`() {
        for (html in listOf("<html><body>no head</body></html>", "<html></head></html>", "<head data-x='unterminated></head>", "<head><body>missing close</body>")) {
            val bytes = html.toByteArray()
            val head = scriptHead(bytes)
            val result = injector.prepare(request, head, readyTags(head = head), bytes.inputStream())
            assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.MISSING_HEAD, true), result)
        }
    }

    @Test fun `disabled and non-html response streams of any size remain unread and unchanged`() {
        val upstream = scriptHead().copy(headers = listOf(ActionHeader("Content-Type", "application/octet-stream"), ActionHeader("Content-Length", (65L * 1024 * 1024).toString())))
        val tags = ScriptTagPlanner(syntheticCatalog()).plan(request, upstream, ResponseAction.ScriptInjection(listOf("alpha"), null), true)
        val stream = object : InputStream() { override fun read(): Int = error("Must not read") }
        val result = injector.prepare(request, upstream, tags, stream) as ScriptResponseDecision.PassThrough
        assertEquals((65L * 1024 * 1024).toString(), scriptHeader(result.head, "content-length"))
        assertEquals(ScriptSkip.NON_HTML, result.reason)
    }

    @Test fun `HEAD bodyless statuses and zero length remain untouched before body access`() {
        for ((method, status, length) in listOf(Triple("HEAD", 200, "999"), Triple("GET", 204, "0"), Triple("GET", 304, "999"), Triple("GET", 200, "0"))) {
            val input = request.copy(method = method)
            val head = scriptHead(status = status).copy(headers = listOf(ActionHeader("Content-Type", "text/html"), ActionHeader("Content-Length", length)))
            val tags = readyTags(input, head)
            assertTrue(injector.prepare(input, head, tags, object : InputStream() { override fun read(): Int = error("No body") }) is ScriptResponseDecision.PassThrough)
        }
    }

    @Test fun `partial no-transform unsafe framing and trailers block before reading`() {
        for (head in listOf(
            scriptHead(status = 206), scriptHead(extra = listOf(ActionHeader("Cache-Control", "no-transform"))),
            scriptHead(extra = listOf(ActionHeader("Transfer-Encoding", "chunked"))),
            scriptHead(extra = listOf(ActionHeader("Trailer", "X-Trailer"))),
            scriptHead(extra = listOf(ActionHeader("Content-Length", "1"))),
        )) {
            val tags = readyTags(head = head)
            val result = injector.prepare(request, head, tags, object : InputStream() { override fun read(): Int = error("Must not read") })
            assertTrue(result is ScriptResponseDecision.Blocked && !result.entityConsumed)
        }
    }

    @Test fun `tag nonce decision cannot be reused after head or request changes`() {
        val head = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", "script-src 'nonce-Abc012='")))
        val tags = readyTags(head = head)
        val changed = head.copy(headers = head.headers + ActionHeader("X-Changed", "yes"))
        val stream = object : InputStream() { override fun read(): Int = error("Must not read") }
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.INVALID_HEADERS), injector.prepare(request, changed, tags, stream))
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.INVALID_HEADERS), injector.prepare(request.copy(host = "other.test"), head, tags, stream))
    }

    @Test fun `Brotli and unknown stacked encodings block without consuming representation`() {
        for (encoding in listOf("br", "compress", "gzip, deflate")) {
            val head = scriptHead(extra = listOf(ActionHeader("Content-Encoding", encoding)))
            val result = injector.prepare(request, head, readyTags(head = head), object : InputStream() { override fun read(): Int = error("No decode") })
            assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.UNSUPPORTED_ENCODING), result)
        }
    }

    @Test fun `gzip and deflate injection keep encoding and replace encoded byte count`() {
        for (encoding in listOf("gzip", "deflate")) {
            val bytes = compress(scriptHtml, encoding)
            val head = scriptHead(bytes, listOf(ActionHeader("Content-Encoding", encoding)))
            val prepared = (injector.prepare(request, head, readyTags(head = head), bytes.inputStream()) as ScriptResponseDecision.Prepared).response
            assertEquals(encoding, scriptHeader(prepared.head, "content-encoding"))
            assertEquals(prepared.bodySize.toString(), scriptHeader(prepared.head, "content-length"))
            val decoded = if (encoding == "gzip") GZIPInputStream(prepared.bodyBytes().inputStream()).use { it.readBytes() }
                else java.util.zip.InflaterInputStream(prepared.bodyBytes().inputStream()).use { it.readBytes() }
            assertTrue(decoded.toString(Charsets.UTF_8).contains("/____ds_script____/alpha"))
        }
    }

    @Test fun `modified representations lose stale validators digests and byte range promises`() {
        val headers = listOf("ETag", "Last-Modified", "Content-MD5", "Digest", "Content-Digest", "Repr-Digest", "Accept-Ranges")
            .map { ActionHeader(it, "old") }
        val head = scriptHead(extra = headers + ActionHeader("Set-Cookie", "a=1") + ActionHeader("Set-Cookie", "b=2"))
        val prepared = (injector.prepare(request, head, readyTags(head = head), scriptHtml.inputStream()) as ScriptResponseDecision.Prepared).response
        headers.forEach { assertNull(scriptHeader(prepared.head, it.name)) }
        assertEquals(listOf("a=1", "b=2"), prepared.head.headers.filter { it.name.equals("set-cookie", true) }.map { it.value })
    }

    @Test fun `already dechunked input receives fixed Content-Length and no hop headers`() {
        val head = scriptHead().copy(headers = listOf(ActionHeader("Content-Type", "text/html"),
            ActionHeader("Transfer-Encoding", "chunked"), ActionHeader("Connection", "x-hop"), ActionHeader("X-Hop", "remove")))
        val prepared = (injector.prepare(request, head, readyTags(head = head), scriptHtml.inputStream()) as ScriptResponseDecision.Prepared).response
        assertNull(scriptHeader(prepared.head, "transfer-encoding"))
        assertNull(scriptHeader(prepared.head, "connection"))
        assertNull(scriptHeader(prepared.head, "x-hop"))
        assertEquals(prepared.bodySize.toString(), scriptHeader(prepared.head, "content-length"))
    }

    @Test fun `input decoded output and tag limits cannot return truncated success`() {
        val head = scriptHead()
        for (limits in listOf(
            ScriptLimits(maximumEncodedBytes = 1), ScriptLimits(maximumDecodedBytes = scriptHtml.size),
            ScriptLimits(maximumOutputBytes = 1), ScriptLimits(maximumTagBytes = 1),
        )) {
            val result = HtmlScriptInjector(limits).prepare(request, head, readyTags(), scriptHtml.inputStream())
            assertTrue(result is ScriptResponseDecision.Blocked && result.problem == ScriptProblem.BODY_LIMIT)
        }
    }

    @Test fun `compressed bomb and malformed compression are bounded failures`() {
        val huge = "<head>${"x".repeat(8192)}</head>".toByteArray()
        val compressed = compress(huge, "gzip")
        val head = scriptHead(compressed, listOf(ActionHeader("Content-Encoding", "gzip")))
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.BODY_LIMIT, true),
            HtmlScriptInjector(ScriptLimits(maximumDecodedBytes = 100)).prepare(request, head, readyTags(head = head), compressed.inputStream()))
        val invalid = byteArrayOf(0, 1, 2)
        val invalidHead = scriptHead(invalid, listOf(ActionHeader("Content-Encoding", "gzip")))
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.MALFORMED_BODY, true),
            injector.prepare(request, invalidHead, readyTags(head = invalidHead), invalid.inputStream()))
    }

    @Test fun `charset contradictions duplicate declarations and UTF16 BOM are blocked`() {
        for (type in listOf("text/html; charset=utf-16", "text/html; charset=iso-8859-1", "text/html; charset=utf-8; charset=utf-16")) {
            val head = scriptHead().copy(headers = listOf(ActionHeader("Content-Type", type)))
            assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.UNSUPPORTED_CHARSET),
                injector.prepare(request, head, readyTags(head = head), scriptHtml.inputStream()))
        }
        val bom = byteArrayOf(-1, -2) + scriptHtml
        val head = scriptHead(bom)
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.UNSUPPORTED_CHARSET, true),
            injector.prepare(request, head, readyTags(head = head), bom.inputStream()))
    }

    @Test fun `CSP is preserved by default and explicit source compatibility changes only enforced policy`() {
        val policy = "script-src 'nonce-Abc012=' 'strict-dynamic'"
        val head = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", policy),
            ActionHeader("Content-Security-Policy-Report-Only", "default-src 'none'")))
        val safe = (injector.prepare(request, head, readyTags(head = head), scriptHtml.inputStream()) as ScriptResponseDecision.Prepared).response
        assertEquals(policy, scriptHeader(safe.head, "content-security-policy"))
        val planner = ScriptTagPlanner(syntheticCatalog(), cspMode = ScriptCspMode.EXPLICIT_UPSTREAM_SELF_COMPAT)
        val tags = planner.plan(request, head, ResponseAction.ScriptInjection(listOf("alpha"), null), true)
        val compat = (injector.prepare(request, head, tags, scriptHtml.inputStream()) as ScriptResponseDecision.Prepared).response
        assertEquals("script-src 'self' 'nonce-Abc012=' 'strict-dynamic'", scriptHeader(compat.head, "content-security-policy"))
        assertEquals("default-src 'none'", scriptHeader(compat.head, "content-security-policy-report-only"))
    }

    @Test fun `cancellation has no silent fallback and streams remain caller-owned`() {
        val head = scriptHead()
        val stream = object : ByteArrayInputStream(scriptHtml) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.CANCELLED), injector.prepare(request, head, readyTags(), stream) { true })
        assertFalse(stream.closed)
        val response = (injector.prepare(request, head, readyTags(), stream) as ScriptResponseDecision.Prepared).response
        assertFalse(stream.closed)
        val sink = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        response.writeHttp1(sink)
        assertFalse(sink.closed)
        val wire = sink.toByteArray()
        val boundary = wire.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        assertArrayEquals(response.bodyBytes(), wire.copyOfRange(boundary, wire.size))
    }

    @Test fun `known Content-Length mismatch never becomes successful injection`() {
        val head = scriptHead().copy(headers = listOf(ActionHeader("Content-Type", "text/html"), ActionHeader("Content-Length", "999")))
        assertEquals(ScriptResponseDecision.Blocked(ScriptProblem.MALFORMED_BODY, true),
            injector.prepare(request, head, readyTags(head = head), scriptHtml.inputStream()))
    }

    private fun compress(bytes: ByteArray, encoding: String): ByteArray = ByteArrayOutputStream().also { output ->
        if (encoding == "gzip") GZIPOutputStream(output).use { it.write(bytes) }
        else DeflaterOutputStream(output).use { it.write(bytes) }
    }.toByteArray()
}
