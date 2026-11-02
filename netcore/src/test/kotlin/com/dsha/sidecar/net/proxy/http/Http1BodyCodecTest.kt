package com.dsha.sidecar.net.proxy.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class Http1BodyCodecTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun input(text: String): InputStream = object : FilterInputStream(ByteArrayInputStream(bytes(text))) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 2))
    }
    private fun text(output: ByteArrayOutputStream) = output.toString(Charsets.ISO_8859_1.name())
    private fun transfer(
        wire: String,
        limits: Http1Limits = Http1Limits(),
        mode: Http1ChunkMode = Http1ChunkMode.PRESERVE,
    ): Pair<String, Http1BodyResult> {
        val out = ByteArrayOutputStream()
        val result = Http1BodyCodec.transfer(input(wire), out, Http1BodyFraming.Chunked, limits, mode)
        return text(out) to result
    }

    @Test
    fun chunkForwardingRetainsExtensionsBinaryAndDuplicateTrailers() {
        val wire = "3;foo=\"a;\\\"b\"\r\n\u0000\u00ffx\r\n2 ; name = value\r\nyz\r\n" +
            "00;done\r\nX-Check:\tfirst \r\nx-check: second\r\n\r\n"
        val (forwarded, result) = transfer(wire)
        assertEquals(wire, forwarded)
        assertEquals(5, result.bodyBytes)
        assertEquals(listOf("first", "second"), result.trailers.values("X-CHECK"))
        assertEquals("\tfirst ", result.trailers.fields.first().rawValue)
        val (decoded, decodedResult) = transfer(wire, mode = Http1ChunkMode.DECODE)
        assertEquals("\u0000\u00ffxyz", decoded)
        assertEquals(5, decodedResult.bodyBytes)
        assertEquals(result.trailers.fields, decodedResult.trailers.fields)
    }

    @Test
    fun chunkedRequestFollowedByFixedAndEmptyRequests() {
        val reader = Http1Reader(input(
            "POST /chunk HTTP/1.1\r\nTransfer-Encoding: ChUnKeD\r\n\r\n1\r\na\r\n0\r\nX: z\r\n\r\n" +
                "POST /fixed HTTP/1.1\r\nContent-Length: 2\r\n\r\nbc" +
                "GET /empty HTTP/1.1\r\n\r\n",
        ))
        assertEquals(Http1BodyFraming.Chunked, reader.readRequest()!!.framing)
        val out = ByteArrayOutputStream()
        reader.transferBody(out, Http1ChunkMode.DECODE)
        assertEquals(Http1BodyFraming.Fixed(2), reader.readRequest()!!.framing)
        reader.transferBody(out)
        assertEquals(Http1BodyFraming.None, reader.readRequest()!!.framing)
        reader.transferBody(out)
        assertEquals("abc", text(out))
        assertNull(reader.readRequest())
    }

    @Test
    fun invalidChunkSyntaxAndTruncationAreRejected() {
        for (wire in listOf(
            "", "1", "1\nx\r\n0\r\n\r\n", "1\r\n", "1\r\nx", "1\r\nx\n0\r\n\r\n",
            "-1\r\n", "+1\r\n", "0x1\r\n", "8000000000000000\r\n",
            "1;\r\n", "1;x=\r\n", "1;x=\"unterminated\r\n", "1;x=\"bad\u0000\"\r\n",
            "1 \r\n", "0\r\n", "0\r\nX: y\r\n", "0\r\n folded: x\r\n\r\n",
        )) assertThrows(wire, Http1Exception::class.java) { transfer(wire) }
    }

    @Test
    fun dangerousTrailersAndOversizedTrailerSectionsAreRejected() {
        for (name in listOf("Content-Length", "Transfer-Encoding", "HOST", "Connection", "Trailer", "Upgrade", "Proxy-Connection")) {
            assertThrows(name, Http1Exception::class.java) { transfer("0\r\n$name: x\r\n\r\n") }
        }
        assertThrows(Http1Exception::class.java) {
            transfer("0\r\nX: y\r\n\r\n", Http1Limits(maxTrailerBytes = 7))
        }
        assertThrows(Http1Exception::class.java) {
            transfer("0\r\nX: y\r\n\r\n", Http1Limits(maxTrailerCount = 0))
        }
        assertThrows(Http1Exception::class.java) {
            transfer("0\r\nLong: value\r\n\r\n", Http1Limits(maxLineBytes = 4))
        }
        assertEquals("0\r\n\r\n", transfer("0\r\n\r\n", Http1Limits(maxTrailerBytes = 2)).first)
    }

    @Test
    fun aggregateBodyLimitAndChunkLineLimitAreEnforced() {
        assertThrows(Http1Exception::class.java) {
            transfer("2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n", Http1Limits(maxBodyBytes = 3))
        }
        assertThrows(Http1Exception::class.java) {
            transfer("1;extension=long\r\nx\r\n0\r\n\r\n", Http1Limits(maxLineBytes = 8))
        }
        assertEquals(0, transfer("0\r\n\r\n", Http1Limits(maxBodyBytes = 0)).second.bodyBytes)
        assertEquals(3, transfer("3\r\nabc\r\n0\r\n\r\n", Http1Limits(maxBodyBytes = 3)).second.bodyBytes)
    }

    @Test
    fun fixedLengthLeavesNextMessageUntouchedAndDetectsTruncation() {
        val source = input("abcNEXT")
        val out = ByteArrayOutputStream()
        assertEquals(3, Http1BodyCodec.transfer(source, out, Http1BodyFraming.Fixed(3)).bodyBytes)
        assertEquals("abc", text(out))
        assertEquals("NEXT", String(source.readBytes(), Charsets.ISO_8859_1))
        assertThrows(Http1Exception::class.java) {
            Http1BodyCodec.transfer(input("ab"), out, Http1BodyFraming.Fixed(3))
        }
        assertThrows(Http1Exception::class.java) {
            Http1BodyCodec.transfer(input(""), out, Http1BodyFraming.Fixed(-1))
        }
    }

    @Test
    fun closeDelimitedConsumesUntilEofAndDisablesReaderReuse() {
        val reader = Http1Reader(input("HTTP/1.0 200 OK\r\n\r\nbody"))
        assertEquals(Http1BodyFraming.CloseDelimited, reader.readResponse("GET")!!.framing)
        val out = ByteArrayOutputStream()
        assertEquals(4, reader.transferBody(out).bodyBytes)
        assertEquals("body", text(out))
        assertThrows(IllegalStateException::class.java) { reader.readResponse("GET") }
        assertThrows(IllegalStateException::class.java) { reader.handoff() }
    }

    @Test
    fun closeDelimitedLimitsIncludeExactZeroAndLongMaximum() {
        for ((value, max) in listOf("" to 0L, "abc" to 3L, "abc" to Long.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            val result = Http1BodyCodec.transfer(input(value), out, Http1BodyFraming.CloseDelimited,
                Http1Limits(maxBodyBytes = max))
            assertEquals(value.length.toLong(), result.bodyBytes)
            assertEquals(value, text(out))
        }
        assertThrows(Http1Exception::class.java) {
            Http1BodyCodec.transfer(input("a"), ByteArrayOutputStream(), Http1BodyFraming.CloseDelimited,
                Http1Limits(maxBodyBytes = 0))
        }
    }

    @Test
    fun chunkEncoderProducesReadableBodyAndSeparateTrailers() {
        val out = ByteArrayOutputStream()
        val trailers = Http1Headers(listOf(Http1Header("X-Check", " result")))
        val result = Http1BodyCodec.writeChunked(input("abcdefg"), out, trailers, Http1Limits(bufferBytes = 3))
        assertEquals(7, result.bodyBytes)
        val (decoded, decodedResult) = transfer(text(out), mode = Http1ChunkMode.DECODE)
        assertEquals("abcdefg", decoded)
        assertEquals(trailers.fields, decodedResult.trailers.fields)
        assertTrue(text(out).endsWith("0\r\nX-Check: result\r\n\r\n"))
    }

    @Test
    fun chunkEncoderValidatesTrailersBeforeWritingAndEnforcesBodyLimit() {
        val out = ByteArrayOutputStream()
        assertThrows(Http1Exception::class.java) {
            Http1BodyCodec.writeChunked(input("a"), out, Http1Headers(listOf(Http1Header("Content-Length", " 1"))))
        }
        assertEquals(0, out.size())
        assertThrows(Http1Exception::class.java) {
            Http1BodyCodec.writeChunked(input("abc"), out, limits = Http1Limits(maxBodyBytes = 2))
        }
    }

    @Test
    fun largeFixedBodyIsStreamedWithBoundedReadsAndNoFlushOrClose() {
        val length = 1024L * 1024 + 7
        var remaining = length
        val source = object : InputStream() {
            override fun read(): Int {
                if (remaining == 0L) return -1
                remaining--
                return 0x7f
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue("Read must fit configured buffer", len <= 37)
                if (remaining == 0L) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + n, 0x7f.toByte())
                remaining -= n
                return n
            }
            override fun close() { fail("Codec must not close source") }
        }
        var written = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) { written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
            override fun flush() { fail("Codec must not flush output") }
            override fun close() { fail("Codec must not close output") }
        }
        Http1BodyCodec.transfer(source, sink, Http1BodyFraming.Fixed(length), Http1Limits(bufferBytes = 37))
        assertEquals(length, written)
        assertEquals(0, remaining)
    }

    @Test
    fun zeroReturningBulkSourceCannotSpinForever() {
        val source = object : ByteArrayInputStream(bytes("abc")) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        val out = ByteArrayOutputStream()
        Http1BodyCodec.transfer(source, out, Http1BodyFraming.Fixed(3))
        assertEquals("abc", text(out))
    }

    @Test
    fun noBodyAndHandoffNeverConsumePayload() {
        val source = input("NEXT")
        val out = ByteArrayOutputStream()
        Http1BodyCodec.transfer(source, out, Http1BodyFraming.None)
        assertThrows(IllegalArgumentException::class.java) {
            Http1BodyCodec.transfer(source, out, Http1BodyFraming.Handoff)
        }
        assertEquals("NEXT", String(source.readBytes(), Charsets.ISO_8859_1))
        assertEquals(0, out.size())
    }
}
