package com.dsha.sidecar.net.proxy.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class Http1CodecTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun input(text: String): InputStream = object : FilterInputStream(ByteArrayInputStream(bytes(text))) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
    }
    private fun text(output: ByteArrayOutputStream) = output.toString(Charsets.ISO_8859_1.name())
    private fun readRequest(text: String, limits: Http1Limits = Http1Limits()) =
        Http1HeadCodec.readRequest(input(text), limits)!!
    private fun readResponse(text: String, method: String = "GET", limits: Http1Limits = Http1Limits()) =
        Http1HeadCodec.readResponse(input(text), method, limits)!!

    @Test
    fun pipelineConsumesExactBodiesAndPreservesHeaderOrder() {
        val reader = Http1Reader(input(
            "POST http://example.test?x=1 HTTP/1.1\r\nHost: example.test\r\n" +
                "X-Value:\tfirst \r\nx-value: second\r\nContent-Length: 4\r\n\r\ndata" +
                "GET /next?q=2 HTTP/1.1\r\nHost: example.test\r\n\r\n",
        ))
        val first = reader.readRequest()!!
        assertEquals(Http1Target.Absolute("http://example.test?x=1", "example.test", "/?x=1"), first.head.target)
        assertEquals(listOf("first", "second"), first.head.headers.values("X-VALUE"))
        assertEquals(listOf("Host", "X-Value", "x-value", "Content-Length"), first.head.headers.fields.map { it.name })
        assertEquals("\tfirst ", first.head.headers.fields[1].rawValue)
        val forwardedHead = ByteArrayOutputStream()
        val originHead = first.head.copy(target = Http1Target.Origin((first.head.target as Http1Target.Absolute).originForm))
        Http1HeadCodec.writeRequest(forwardedHead, originHead)
        assertTrue(text(forwardedHead).startsWith("POST /?x=1 HTTP/1.1\r\n"))
        assertTrue(text(forwardedHead).contains("X-Value:\tfirst \r\nx-value: second\r\n"))
        val body = ByteArrayOutputStream()
        assertEquals(4, reader.transferBody(body).bodyBytes)
        assertEquals("data", text(body))
        val second = reader.readRequest()!!
        assertEquals(Http1Target.Origin("/next?q=2"), second.head.target)
        reader.transferBody(ByteArrayOutputStream())
        assertNull(reader.readRequest())
    }

    @Test
    fun malformedHeadsAndFramingAreRejected() {
        val bad = listOf(
            "GET / HTTP/1.1\nHost: x\n\n",
            "GET / HTTP/1.1\rX",
            "GET / HTTP/1.1\r\n Host: x\r\n\r\n",
            "GET / HTTP/1.1\r\nHost : x\r\n\r\n",
            "GET  / HTTP/1.1\r\n\r\n",
            "GET / HTTP/2.0\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 1\r\ncontent-length: 1\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 1, 1\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 9223372036854775808\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked, chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked;\r\n\r\n",
            "POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n",
            "GET / HTTP/1.1\r\nX: \u0000\r\n\r\n",
        )
        for (head in bad) assertThrows(head, Http1Exception::class.java) { readRequest(head) }
    }

    @Test
    fun limitsApplyToLinesSectionsFieldCountAndDeclaredLength() {
        assertThrows(Http1Exception::class.java) {
            readRequest("GET /long HTTP/1.1\r\n\r\n", Http1Limits(maxLineBytes = 8))
        }
        assertThrows(Http1Exception::class.java) {
            readRequest("GET / HTTP/1.1\r\nX: y\r\n\r\n", Http1Limits(maxHeadBytes = 22))
        }
        assertThrows(Http1Exception::class.java) {
            readRequest("GET / HTTP/1.1\r\nX: y\r\n\r\n", Http1Limits(maxHeaderCount = 0))
        }
        assertThrows(Http1Exception::class.java) {
            readRequest("POST / HTTP/1.1\r\nContent-Length: 4\r\n\r\n", Http1Limits(maxBodyBytes = 3))
        }
        assertEquals(Http1BodyFraming.Fixed(0), readRequest("POST / HTTP/1.1\r\nContent-Length: 0\r\n\r\n",
            Http1Limits(maxBodyBytes = 0)).framing)
    }

    @Test
    fun requestTargetFormsAndValidation() {
        assertEquals(Http1Target.Origin("//path?q=ok"),
            readRequest("GET //path?q=ok HTTP/1.1\r\n\r\n").head.target)
        assertEquals(Http1Target.Origin("//"), readRequest("GET // HTTP/1.1\r\n\r\n").head.target)
        assertEquals(Http1Target.Asterisk, readRequest("OPTIONS * HTTP/1.1\r\n\r\n").head.target)
        assertEquals(Http1Target.Authority("[::1]:443"),
            readRequest("CONNECT [::1]:443 HTTP/1.1\r\n\r\n").head.target)
        val absolute = readRequest("GET HTTP://example.test:80/a%2Fb?x=1 HTTP/1.1\r\n\r\n").head.target
            as Http1Target.Absolute
        assertEquals("/a%2Fb?x=1", absolute.originForm)
        for (line in listOf(
            "GET * HTTP/1.1", "GET /a#b HTTP/1.1", "GET http://x/a#b HTTP/1.1",
            "GET http://user@x/ HTTP/1.1", "GET foo HTTP/1.1", "CONNECT example.test HTTP/1.1",
            "CONNECT example.test:99999 HTTP/1.1", "CONNECT / HTTP/1.1",
        )) assertThrows(line, Http1Exception::class.java) { readRequest("$line\r\n\r\n") }
    }

    @Test
    fun responseBodyPrecedenceAndHandoff() {
        for ((method, status) in listOf("HEAD" to 200, "GET" to 100, "GET" to 103, "GET" to 204, "GET" to 304)) {
            val response = readResponse("HTTP/1.1 $status OK\r\nContent-Length: 999\r\n\r\n", method,
                Http1Limits(maxBodyBytes = 1))
            assertEquals(Http1BodyFraming.None, response.framing)
        }
        assertEquals(Http1BodyFraming.Handoff, readResponse("HTTP/1.1 101 Switching\r\n\r\n").framing)
        assertEquals(Http1BodyFraming.Handoff, readResponse("HTTP/1.1 200 OK\r\n\r\n", "CONNECT").framing)
        assertEquals(Http1BodyFraming.CloseDelimited, readResponse("HTTP/1.1 400 Bad\r\n\r\n", "CONNECT").framing)
        assertEquals(Http1BodyFraming.Fixed(2), readResponse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n", "head").framing)
        assertThrows(Http1Exception::class.java) {
            readResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n", "CONNECT")
        }
    }

    @Test
    fun interimThenFinalResponseUsesCorrectBoundaries() {
        val reader = Http1Reader(input(
            "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok" +
                "HTTP/1.1 204 No Content\r\n\r\n",
        ))
        assertEquals(100, reader.readResponse("POST")!!.head.status)
        reader.transferBody(ByteArrayOutputStream())
        assertEquals(200, reader.readResponse("POST")!!.head.status)
        val out = ByteArrayOutputStream()
        reader.transferBody(out)
        assertEquals("ok", text(out))
        assertEquals(Http1BodyFraming.None, reader.readResponse("GET")!!.framing)
        reader.transferBody(ByteArrayOutputStream())
        assertNull(reader.readResponse("GET"))
    }

    @Test
    fun handoffIncludesBytesAlreadyBuffered() {
        val raw = ByteArrayInputStream(bytes("HTTP/1.1 200 Established\r\n\r\n\u0016\u0003\u0001TLS"))
        val reader = Http1Reader(raw)
        assertEquals(Http1BodyFraming.Handoff, reader.readResponse("CONNECT")!!.framing)
        assertEquals(0, raw.available())
        assertArrayEquals(bytes("\u0016\u0003\u0001TLS"), reader.handoff().readBytes())
        assertThrows(IllegalStateException::class.java) { reader.readResponse("GET") }
    }

    @Test
    fun earlyConnectClientHelloSurvivesRequestHandoff() {
        val hello = bytes("\u0016\u0003\u0001\u0000\u0005hello")
        val raw = ByteArrayInputStream(bytes("CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n\r\n") + hello)
        val reader = Http1Reader(raw)
        assertEquals(Http1BodyFraming.None, reader.readRequest()!!.framing)
        assertEquals(0, raw.available())
        val discardedBody = ByteArrayOutputStream()
        reader.transferBody(discardedBody)
        assertEquals(0, discardedBody.size())
        assertArrayEquals(hello, reader.handoff().readBytes())
        assertThrows(IllegalStateException::class.java) { reader.readRequest() }
    }

    @Test
    fun upgradeHandoffRetainsPrefetchedFrames() {
        val frame = bytes("\u0081\u0002hi")
        val raw = ByteArrayInputStream(bytes(
            "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
        ) + frame)
        val reader = Http1Reader(raw)
        val response = reader.readResponse("GET")!!
        assertTrue(response.head.headers.hasToken("Connection", "upgrade"))
        assertEquals(Http1BodyFraming.Handoff, response.framing)
        assertThrows(IllegalStateException::class.java) { reader.transferBody(ByteArrayOutputStream()) }
        assertArrayEquals(frame, reader.handoff().readBytes())
        assertThrows(IllegalStateException::class.java) { reader.handoff() }
    }

    @Test
    fun lowLevelCodecsShareCallerOwnedBufferedInputAcrossMessages() {
        val stream = java.io.BufferedInputStream(ByteArrayInputStream(bytes(
            "POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\nok" +
                "CONNECT example.test:443 HTTP/1.1\r\n\r\n\u0016\u0003\u0001hello",
        )))
        val first = Http1HeadCodec.readRequest(stream)!!
        val out = ByteArrayOutputStream()
        Http1BodyCodec.transfer(stream, out, first.framing)
        assertEquals("ok", text(out))
        val connect = Http1HeadCodec.readRequest(stream)!!
        assertEquals("CONNECT", connect.head.method)
        Http1BodyCodec.transfer(stream, out, connect.framing)
        assertArrayEquals(bytes("\u0016\u0003\u0001hello"), stream.readBytes())
    }

    @Test
    fun pendingBodyBlocksReadingAndCanBeRetriedAfterApiMisuse() {
        val reader = Http1Reader(input("POST / HTTP/1.1\r\nContent-Length: 1\r\n\r\nx"))
        reader.readRequest()
        assertThrows(IllegalStateException::class.java) { reader.readRequest() }
        assertThrows(IllegalStateException::class.java) { reader.handoff() }
        val out = ByteArrayOutputStream()
        reader.transferBody(out)
        assertEquals("x", text(out))
    }

    @Test
    fun persistenceRespectsVersionsAndCaseInsensitiveTokens() {
        fun request(version: String, connection: String = "") = readRequest(
            "GET / $version\r\n${if (connection.isEmpty()) "" else "Connection: $connection\r\n"}\r\n",
        )
        fun response(version: String, connection: String = "") = readResponse(
            "$version 200 OK\r\nContent-Length: 0\r\n${if (connection.isEmpty()) "" else "Connection: $connection\r\n"}\r\n",
        )
        assertTrue(Http1Persistence.canReuse(request("HTTP/1.1"), response("HTTP/1.1")))
        assertFalse(Http1Persistence.canReuse(request("HTTP/1.1", "keep-alive, ClOsE"), response("HTTP/1.1")))
        assertFalse(Http1Persistence.canReuse(request("HTTP/1.0"), response("HTTP/1.0", "Keep-Alive")))
        assertTrue(Http1Persistence.canReuse(request("HTTP/1.0", "Keep-Alive"), response("HTTP/1.0", "keep-alive")))
        assertFalse(Http1Persistence.canReuse(request("HTTP/1.1"), readResponse("HTTP/1.1 200 OK\r\n\r\n")))
        assertFalse(Http1Persistence.canReuse(request("HTTP/1.1"), readResponse("HTTP/1.1 101 Switch\r\n\r\n")))
        assertThrows(Http1Exception::class.java) {
            Http1Persistence.isPersistent("HTTP/1.1", Http1Headers(listOf(Http1Header("Connection", " close,,x"))))
        }
    }

    @Test
    fun writerChecksAllFieldsBeforeEmittingAHead() {
        val out = ByteArrayOutputStream()
        val bad = Http1RequestHead("GET", Http1Target.Origin("/"), "HTTP/1.1",
            Http1Headers(listOf(Http1Header("X", " good\r\nInjected: bad"))))
        assertThrows(Http1Exception::class.java) { Http1HeadCodec.writeRequest(out, bad) }
        assertEquals(0, out.size())
        val response = Http1ResponseHead("HTTP/1.1", 200, "OK", Http1Headers(listOf(Http1Header("X", " \u00ff"))))
        Http1HeadCodec.writeResponse(out, response, "GET")
        assertEquals("HTTP/1.1 200 OK\r\nX: \u00ff\r\n\r\n", text(out))
    }

    @Test
    fun readerIsPoisonedAfterTruncatedOrFailedBodyWrite() {
        val truncated = Http1Reader(input("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\nx"))
        truncated.readRequest()
        assertThrows(Http1Exception::class.java) { truncated.transferBody(ByteArrayOutputStream()) }
        assertThrows(IllegalStateException::class.java) { truncated.readRequest() }
        assertThrows(IllegalStateException::class.java) { truncated.transferBody(ByteArrayOutputStream()) }
        val reader = Http1Reader(input("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\nok"))
        reader.readRequest()
        val failing = object : java.io.OutputStream() {
            override fun write(b: Int) { throw IOException("output failed") }
        }
        assertThrows(IOException::class.java) { reader.transferBody(failing) }
        assertThrows(IllegalStateException::class.java) { reader.handoff() }
    }

    @Test
    fun cleanEofIsDistinctFromTruncatedHeadsAndPoisonedReaders() {
        assertNull(Http1HeadCodec.readRequest(input("")))
        assertNull(Http1HeadCodec.readResponse(input(""), "GET"))
        for (head in listOf("GET / HTTP/1.1", "GET / HTTP/1.1\r\n", "GET / HTTP/1.1\r\nX: y\r\n")) {
            val reader = Http1Reader(input(head))
            assertThrows(Http1Exception::class.java) { reader.readRequest() }
            assertThrows(IllegalStateException::class.java) { reader.readRequest() }
            assertThrows(IllegalStateException::class.java) { reader.handoff() }
        }
        for (head in listOf("HTTP/1.1 200\r\n\r\n", "HTTP/1.1 099 Bad\r\n\r\n", "HTTP/1.1 600 Bad\r\n\r\n")) {
            assertThrows(Http1Exception::class.java) { readResponse(head) }
        }
    }

    @Test
    fun exactHeadLimitIncludesDelimitersAndMatchesWriter() {
        val wire = "GET / HTTP/1.1\r\nX: y\r\n\r\n"
        val limits = Http1Limits(maxHeadBytes = bytes(wire).size, maxLineBytes = 14, maxHeaderCount = 1)
        val head = readRequest(wire, limits).head
        val out = ByteArrayOutputStream()
        Http1HeadCodec.writeRequest(out, head, limits)
        assertEquals(wire, text(out))
        assertThrows(Http1Exception::class.java) { readRequest(wire, limits.copy(maxHeadBytes = wire.length - 1)) }
        val failed = ByteArrayOutputStream()
        assertThrows(Http1Exception::class.java) {
            Http1HeadCodec.writeRequest(failed, head, limits.copy(maxHeadBytes = wire.length - 1))
        }
        assertEquals(0, failed.size())
    }

    @Test
    fun headAndNoContentResponsesLeaveNextStatusLineUntouched() {
        for ((method, status) in listOf("HEAD" to 200, "GET" to 204, "GET" to 304)) {
            val stream = input(
                "HTTP/1.1 $status OK\r\nContent-Length: 99\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nx",
            )
            val noBody = Http1HeadCodec.readResponse(stream, method)!!
            val out = ByteArrayOutputStream()
            Http1BodyCodec.transfer(stream, out, noBody.framing)
            assertEquals(0, out.size())
            val next = Http1HeadCodec.readResponse(stream, "GET")!!
            Http1BodyCodec.transfer(stream, out, next.framing)
            assertEquals("x", text(out))
        }
    }
}
