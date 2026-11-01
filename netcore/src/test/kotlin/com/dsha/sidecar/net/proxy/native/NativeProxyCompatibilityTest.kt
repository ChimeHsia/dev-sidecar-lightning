package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import com.dsha.sidecar.net.proxy.tls.LeafCertificateCache
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

/** Test peers deliberately use their own HTTP/WebSocket readers, not the production codecs. */
class NativeProxyCompatibilityTest {
    private data class Head(val line: String, val fields: List<Pair<String, String>>) {
        fun field(name: String): String? = fields.firstOrNull { it.first.equals(name, true) }?.second
        val status: Int get() = line.split(' ')[1].toInt()
    }
    private data class Frame(val opcode: Int, val data: ByteArray) {
        val text: String get() = data.toString(Charsets.UTF_8)
    }

    private class RawOrigin(
        tls: Boolean = false,
        private val action: (Socket) -> Unit,
    ) : Closeable {
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val workers = Executors.newFixedThreadPool(2) { Thread(it, "compat-origin").apply { isDaemon = true } }
        private val failures = AtomicReference<Throwable?>()
        private val server = if (tls) originLeaves.get("origin.test").newServerSocket().apply {
            bind(InetSocketAddress(LOOPBACK, 0))
        } else ServerSocket(0, 8, LOOPBACK)
        val accepted = AtomicInteger()
        val address = InetSocketAddress(LOOPBACK, server.localPort)
        private val accept = Thread {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    accepted.incrementAndGet()
                    workers.execute {
                        try {
                            socket.use {
                                socket.soTimeout = 3_000
                                if (socket is SSLSocket) socket.startHandshake()
                                action(socket)
                            }
                        } catch (_: IOException) {
                            // Cancellation and deliberate protocol rejection close the transport.
                        } catch (failure: Throwable) {
                            failures.compareAndSet(null, failure)
                        } finally {
                            sockets.remove(socket)
                        }
                    }
                } catch (_: IOException) { }
            }
        }.apply { isDaemon = true; start() }
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            accept.join(2_000)
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
            failures.get()?.let { throw AssertionError("Independent origin failed", it) }
        }
    }

    private fun proxy(
        origin: RawOrigin,
        tls: Boolean = false,
        workers: Int = 2,
        options: NativeProxyOptions = NativeProxyOptions(workers = workers, queuedConnections = 2,
            readTimeoutMillis = 2_000, exchangeTimeoutMillis = 5_000, stopTimeoutMillis = 2_000),
    ): NativeProxyServer =
        NativeProxyServer(RouteResolver {
            NativeProxyRoute(origin.address, tls = if (tls) NativeTlsPolicy("origin.test",
                sniHost = "routing.test", trustContext = originTrust) else null)
        }, clientLeaves, options).apply { start() }

    private fun client(proxy: NativeProxyServer): Socket =
        Socket().apply { connect(proxy.localAddress); soTimeout = 2_000; tcpNoDelay = true }

    private fun secureClient(proxy: NativeProxyServer): SSLSocket {
        val raw = client(proxy)
        write(raw, "CONNECT browser.test:443 HTTP/1.1\r\nHost: browser.test:443\r\n\r\n")
        assertEquals(200, head(raw.getInputStream()).status)
        return (clientTrust.socketFactory.createSocket(raw, "browser.test", 443, true) as SSLSocket).apply {
            soTimeout = 2_000
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
        }
    }

    @Test fun websocket101PreservesBufferedFramesInBothDirectionsAndCloseHandshake() {
        val closeObserved = CountDownLatch(1)
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            assertTrue(request.line.startsWith("GET /chat "))
            assertEquals("Upgrade", request.field("Connection"))
            assertEquals(KEY, request.field("Sec-WebSocket-Key"))
            val output = origin.getOutputStream()
            output.write((SWITCH + "Sec-WebSocket-Protocol: chat\r\n\r\n").toByteArray() +
                frame("server-first", masked = false))
            output.flush()
            assertEquals("client-first", readFrame(origin.getInputStream(), masked = true).text)
            val close = readFrame(origin.getInputStream(), masked = true)
            assertEquals(8, close.opcode)
            assertArrayEquals(byteArrayOf(3, -24), close.data)
            output.write(frame(close.data, masked = false, opcode = 8))
            output.flush()
            closeObserved.countDown()
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    socket.getOutputStream().write(websocketRequest("/chat").toByteArray() +
                        frame("client-first", masked = true))
                    socket.getOutputStream().flush()
                    val response = head(socket.getInputStream())
                    assertEquals(101, response.status)
                    assertEquals("chat", response.field("Sec-WebSocket-Protocol"))
                    assertEquals(ACCEPT, response.field("Sec-WebSocket-Accept"))
                    assertEquals("server-first", readFrame(socket.getInputStream(), masked = false).text)
                    socket.getOutputStream().write(frame(byteArrayOf(3, -24), masked = true, opcode = 8))
                    socket.getOutputStream().flush()
                    assertEquals(8, readFrame(socket.getInputStream(), masked = false).opcode)
                    assertTrue(closeObserved.await(1, TimeUnit.SECONDS))
                    socket.shutdownOutput()
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
        }
    }

    @Test fun websocketFramesAlsoPassThroughVerifiedMitmTls() {
        RawOrigin(tls = true) { origin ->
            assertTrue(head(origin.getInputStream()).line.startsWith("GET /secure-chat "))
            origin.getOutputStream().write((SWITCH + "\r\n").toByteArray() + frame("secure-origin", false))
            origin.getOutputStream().flush()
            assertEquals("secure-client", readFrame(origin.getInputStream(), true).text)
        }.use { origin ->
            proxy(origin, tls = true).use { proxy ->
                secureClient(proxy).use { socket ->
                    socket.getOutputStream().write(websocketRequest("/secure-chat").toByteArray() +
                        frame("secure-client", true))
                    socket.getOutputStream().flush()
                    assertEquals(101, head(socket.getInputStream()).status)
                    assertEquals("secure-origin", readFrame(socket.getInputStream(), false).text)
                }
            }
        }
    }

    @Test fun genericUpgradeHasTransparentBufferedBytesAndTcpHalfClose() {
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            assertEquals("raw/1", request.field("Upgrade"))
            origin.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Connection: Upgrade\r\nUpgrade: raw/1\r\n\r\norigin-first").toByteArray())
            origin.getOutputStream().flush()
            assertEquals("client-first", exact(origin.getInputStream(), 12).toString(Charsets.US_ASCII))
            assertEquals(-1, origin.getInputStream().read())
            origin.getOutputStream().write("half-close-echo".toByteArray())
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "GET /raw HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Connection: Upgrade\r\nUpgrade: raw/1\r\n\r\nclient-first")
                    socket.shutdownOutput()
                    assertEquals(101, head(socket.getInputStream()).status)
                    assertEquals("origin-firsthalf-close-echo",
                        socket.getInputStream().readBytes().toString(Charsets.US_ASCII))
                }
            }
        }
    }

    @Test fun expectContinueRelaysReal103And100BeforeClientSendsBody() {
        val entered = CountDownLatch(1)
        val permitContinue = CountDownLatch(1)
        val bodyObserved = CountDownLatch(1)
        RawOrigin { origin ->
            assertEquals("100-continue", head(origin.getInputStream()).field("Expect"))
            entered.countDown()
            assertTrue(permitContinue.await(2, TimeUnit.SECONDS))
            write(origin, "HTTP/1.1 103 Early Hints\r\nX-Origin: real-hint\r\n\r\n" +
                "HTTP/1.1 100 Continue\r\nX-Origin: real-continue\r\n\r\n")
            assertEquals("hello", exact(origin.getInputStream(), 5).toString(Charsets.US_ASCII))
            bodyObserved.countDown()
            final(origin.getOutputStream(), 200, "uploaded")
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /expect HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Expect: 100-continue\r\nContent-Length: 5\r\n\r\n")
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    socket.soTimeout = 150
                    try { socket.getInputStream().read(); fail("Proxy synthesized an optimistic 100") }
                    catch (_: SocketTimeoutException) { }
                    socket.soTimeout = 2_000
                    permitContinue.countDown()
                    val hint = head(socket.getInputStream())
                    assertEquals(103, hint.status)
                    assertEquals("real-hint", hint.field("X-Origin"))
                    val continueResponse = head(socket.getInputStream())
                    assertEquals(100, continueResponse.status)
                    assertEquals("real-continue", continueResponse.field("X-Origin"))
                    write(socket, "hello")
                    val response = head(socket.getInputStream())
                    assertEquals(200, response.status)
                    assertEquals("uploaded", body(socket, response))
                    assertTrue(bodyObserved.await(1, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun expectFinalRejectionArrivesWithoutConsumingTheWithheldBody() {
        val noBody = CountDownLatch(1)
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            assertEquals("100-continue", request.field("Expect"))
            final(origin.getOutputStream(), 413, "rejected-before-body")
            assertEquals(-1, origin.getInputStream().read())
            noBody.countDown()
        }.use { origin ->
            proxy(origin, workers = 1).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /expect-reject HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Expect: 100-continue\r\nContent-Length: 1000000\r\n\r\n")
                    val response = head(socket.getInputStream())
                    assertEquals(413, response.status)
                    assertEquals("close", response.field("Connection"))
                    assertEquals("rejected-before-body", body(socket, response))
                    assertTrue(noBody.await(1, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun expectEarlyFinalDoesNotForwardEvenAnEagerBufferedBody() {
        val noBody = CountDownLatch(1)
        RawOrigin { origin ->
            assertEquals("100-continue", head(origin.getInputStream()).field("Expect"))
            final(origin.getOutputStream(), 403, "no-eager-body")
            assertEquals(-1, origin.getInputStream().read())
            noBody.countDown()
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /eager HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Expect: 100-continue\r\nContent-Length: 4\r\n\r\nbody")
                    val response = head(socket.getInputStream())
                    assertEquals(403, response.status)
                    assertEquals("no-eager-body", body(socket, response))
                    assertTrue(noBody.await(1, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun continuedUploadCompletesBeforeTwoSequentialPersistentRequests() {
        val completed = CountDownLatch(1)
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            if (request.line.startsWith("POST")) {
                write(origin, "HTTP/1.1 100 Continue\r\n\r\n")
                assertEquals("test", exact(origin.getInputStream(), 4).toString(Charsets.US_ASCII))
                completed.countDown()
                final(origin.getOutputStream(), 200, "post-ok")
            } else final(origin.getOutputStream(), 200, request.line.split(' ')[1])
        }.use { origin ->
            proxy(origin, workers = 1).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /continued HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Expect: 100-continue\r\nContent-Length: 4\r\n\r\n")
                    assertEquals(100, head(socket.getInputStream()).status)
                    write(socket, "test")
                    val post = head(socket.getInputStream())
                    assertEquals(200, post.status)
                    assertEquals("post-ok", body(socket, post))
                    assertEquals("keep-alive", post.field("Connection"))
                    assertTrue(completed.await(1, TimeUnit.SECONDS))
                    for (path in listOf("/next-one", "/next-two")) {
                        write(socket, "GET $path HTTP/1.1\r\nHost: browser.test\r\n\r\n")
                        val response = head(socket.getInputStream())
                        assertEquals(200, response.status)
                        assertEquals(path, body(socket, response))
                    }
                }
            }
            assertEquals(3, origin.accepted.get())
        }
    }

    @Test fun earlyRejectionDuringVerifiedTlsUploadArrivesBeforeBodyCompletion() {
        RawOrigin(tls = true) { origin ->
            head(origin.getInputStream())
            assertEquals("one", exact(origin.getInputStream(), 3).toString(Charsets.US_ASCII))
            final(origin.getOutputStream(), 413, "tls-upload-rejected")
        }.use { origin ->
            proxy(origin, tls = true).use { proxy ->
                secureClient(proxy).use { socket ->
                    write(socket, "POST /tls-upload HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Content-Length: 1000000\r\n\r\none")
                    val response = head(socket.getInputStream())
                    assertEquals(413, response.status)
                    assertEquals("tls-upload-rejected", body(socket, response))
                }
            }
        }
    }

    @Test fun requestReaderFailureRemains400WhileMalformedUpstreamHeadIs502() {
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            if (request.line.startsWith("POST")) {
                assertEquals(-1, origin.getInputStream().read())
            } else write(origin, "HTTP/1.1 not-a-status\r\n\r\n")
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /bad-chunk HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\ninvalid\r\n")
                    assertEquals(400, head(socket.getInputStream()).status)
                }
                client(proxy).use { socket ->
                    write(socket, "GET /bad-response HTTP/1.1\r\nHost: browser.test\r\n\r\n")
                    assertEquals(502, head(socket.getInputStream()).status)
                }
            }
        }
    }

    @Test fun upgradedConnectionSurvivesTheOrdinaryHttpReadAndExchangeDeadlines() {
        RawOrigin { origin ->
            head(origin.getInputStream())
            write(origin, SWITCH + "\r\n")
            assertEquals("after-idle", readFrame(origin.getInputStream(), true).text)
            origin.getOutputStream().write(frame("still-upgraded", false))
            origin.getOutputStream().flush()
        }.use { origin ->
            val options = NativeProxyOptions(readTimeoutMillis = 150, exchangeTimeoutMillis = 300,
                stopTimeoutMillis = 2_000)
            proxy(origin, options = options).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, websocketRequest("/idle"))
                    assertEquals(101, head(socket.getInputStream()).status)
                    Thread.sleep(450)
                    socket.getOutputStream().write(frame("after-idle", true))
                    socket.getOutputStream().flush()
                    assertEquals("still-upgraded", readFrame(socket.getInputStream(), false).text)
                }
            }
        }
    }

    @Test fun configuredDuplexLifetimeClosesAnIdleUpgradedConnection() {
        RawOrigin { origin ->
            head(origin.getInputStream())
            write(origin, SWITCH + "\r\n")
            assertEquals(-1, origin.getInputStream().read())
        }.use { origin ->
            proxy(origin, options = NativeProxyOptions(duplexTimeoutMillis = 150)).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, websocketRequest("/limited"))
                    assertEquals(101, head(socket.getInputStream()).status)
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
        }
    }

    @Test fun immediate413IsDeliveredWhileFixedUploadIsPausedAndWorkersAreReusable() {
        val received = AtomicInteger()
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            if (request.line.startsWith("POST")) {
                assertEquals("abc", exact(origin.getInputStream(), 3).toString(Charsets.US_ASCII))
                received.incrementAndGet()
                final(origin.getOutputStream(), 413, "stop-upload")
                assertEquals(-1, origin.getInputStream().read())
            } else final(origin.getOutputStream(), 200, "still-serving")
        }.use { origin ->
            proxy(origin, workers = 1).use { proxy ->
                repeat(5) {
                    client(proxy).use { socket ->
                        write(socket, "POST /paused HTTP/1.1\r\nHost: browser.test\r\n" +
                            "Content-Length: 1000000\r\n\r\nabc")
                        val response = head(socket.getInputStream())
                        assertEquals(413, response.status)
                        assertEquals("stop-upload", body(socket, response))
                    }
                }
                client(proxy).use { socket ->
                    write(socket, "GET /after HTTP/1.1\r\nHost: browser.test\r\nConnection: close\r\n\r\n")
                    val response = head(socket.getInputStream())
                    assertEquals(200, response.status)
                    assertEquals("still-serving", body(socket, response))
                }
            }
            assertEquals(5, received.get())
        }
    }

    @Test fun early403DuringChunkedUploadDoesNotWaitForLastChunk() {
        RawOrigin { origin ->
            assertEquals("chunked", head(origin.getInputStream()).field("Transfer-Encoding"))
            assertEquals("3\r\nabc\r\n", exact(origin.getInputStream(), 8).toString(Charsets.US_ASCII))
            final(origin.getOutputStream(), 403, "chunk-rejected")
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /chunk HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n")
                    val response = head(socket.getInputStream())
                    assertEquals(403, response.status)
                    assertEquals("chunk-rejected", body(socket, response))
                }
            }
        }
    }

    @Test fun early413UnblocksAnUploadWriterWhenOriginDoesNotReadTheBody() {
        val originEntered = CountDownLatch(1)
        val reject = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val sent = AtomicInteger()
        RawOrigin { origin ->
            head(origin.getInputStream())
            originEntered.countDown()
            assertTrue(reject.await(2, TimeUnit.SECONDS))
            final(origin.getOutputStream(), 413, "backpressure-rejected")
            // Keep the origin read side open without draining the request body.
            writerDone.await(2, TimeUnit.SECONDS)
        }.use { origin ->
            proxy(origin).use { proxy ->
                client(proxy).use { socket ->
                    socket.sendBufferSize = 8192
                    val bytes = 128 * 1024 * 1024
                    write(socket, "POST /backpressure HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Content-Length: $bytes\r\n\r\n")
                    assertTrue(originEntered.await(1, TimeUnit.SECONDS))
                    val writer = Thread {
                        try {
                            val buffer = ByteArray(8192)
                            repeat(bytes / buffer.size) {
                                socket.getOutputStream().write(buffer)
                                sent.addAndGet(buffer.size)
                            }
                        } catch (_: IOException) {
                            // The early response explicitly closes this unfinished upload.
                        } finally { writerDone.countDown() }
                    }.apply { isDaemon = true; start() }
                    try {
                        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                        while (sent.get() < 128 * 1024 && System.nanoTime() < until) Thread.sleep(5)
                        reject.countDown()
                        val response = head(socket.getInputStream())
                        assertEquals(413, response.status)
                        assertEquals("backpressure-rejected", body(socket, response))
                        assertTrue(sent.get() < bytes)
                        assertTrue(writerDone.await(2, TimeUnit.SECONDS))
                    } finally {
                        reject.countDown()
                        socket.close()
                        writer.join(2_000)
                        assertFalse(writer.isAlive)
                    }
                }
            }
        }
    }

    @Test fun progressingUploadDoesNotSpendTheResponseWaitTimeoutBeforeItsBodyFinishes() {
        RawOrigin { origin ->
            head(origin.getInputStream())
            assertEquals("abcdef", exact(origin.getInputStream(), 6).toString(Charsets.US_ASCII))
            final(origin.getOutputStream(), 200, "slow-upload-complete")
        }.use { origin ->
            val options = NativeProxyOptions(readTimeoutMillis = 200, exchangeTimeoutMillis = 3_000)
            proxy(origin, options = options).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /progress HTTP/1.1\r\nHost: browser.test\r\nContent-Length: 6\r\n\r\n")
                    for (value in "abcdef") {
                        write(socket, value.toString())
                        Thread.sleep(65)
                    }
                    val response = head(socket.getInputStream())
                    assertEquals(200, response.status)
                    assertEquals("slow-upload-complete", body(socket, response))
                }
            }
        }
    }

    @Test fun responseWaitDeadlineStartsWhenACompletedUploadHasNoResponse() {
        val originDone = CountDownLatch(1)
        RawOrigin { origin ->
            head(origin.getInputStream())
            assertEquals("body", exact(origin.getInputStream(), 4).toString(Charsets.US_ASCII))
            assertEquals(-1, origin.getInputStream().read())
            originDone.countDown()
        }.use { origin ->
            proxy(origin, options = NativeProxyOptions(readTimeoutMillis = 150,
                exchangeTimeoutMillis = 5_000)).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, "POST /no-response HTTP/1.1\r\nHost: browser.test\r\nContent-Length: 4\r\n\r\nbody")
                    val started = System.nanoTime()
                    assertEquals(504, head(socket.getInputStream()).status)
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
                    assertTrue(originDone.await(1, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun malformedClientNegotiationIs400AndUnknownExpectationIs417BeforeOriginContact() {
        RawOrigin { fail("Invalid clients must not contact origin") }.use { origin ->
            proxy(origin).use { proxy ->
                val cases = listOf(
                    "Upgrade: websocket\r\n" to 400,
                    "Connection: Upgrade\r\n" to 400,
                    "Connection: Upgrade\r\nUpgrade: websocket/\r\n" to 400,
                    "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Key: bad\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" to 400,
                    "Expect: fancy\r\n" to 417,
                    "Expect: 100-continue, fancy\r\n" to 417,
                )
                for ((fields, status) in cases) client(proxy).use { socket ->
                    write(socket, "GET /bad HTTP/1.1\r\nHost: browser.test\r\n$fields\r\n")
                    assertEquals(status, head(socket.getInputStream()).status)
                }
                assertEquals(0, origin.accepted.get())
            }
        }
    }

    @Test fun badUpstreamUpgradeIs502RatherThanClient400AndServiceContinues() {
        val bad = listOf(
            "Upgrade: websocket\r\nSec-WebSocket-Accept: $ACCEPT\r\n",
            "Connection: Upgrade\r\nUpgrade: other\r\n",
            "Connection: Upgrade\r\nUpgrade: websocket/\r\n",
            "Connection: Upgrade, @bad\r\nUpgrade: websocket\r\n",
            "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: wrong\r\n",
            "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: $ACCEPT\r\n" +
                "Sec-WebSocket-Protocol: unoffered\r\n",
            "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: $ACCEPT\r\nContent-Length: 0\r\n",
        )
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            val path = request.line.split(' ')[1]
            if (path == "/good") final(origin.getOutputStream(), 200, "alive")
            else write(origin, "HTTP/1.1 101 Switching Protocols\r\n${bad[path.removePrefix("/").toInt()]}\r\n")
        }.use { origin ->
            proxy(origin).use { proxy ->
                for (index in bad.indices) client(proxy).use { socket ->
                    write(socket, websocketRequest("/$index"))
                    assertEquals(502, head(socket.getInputStream()).status)
                    assertEquals(NativeProxyState.LISTENING, proxy.state)
                }
                client(proxy).use { socket ->
                    write(socket, "GET /good HTTP/1.1\r\nHost: browser.test\r\n\r\n")
                    val response = head(socket.getInputStream())
                    assertEquals(200, response.status)
                    assertEquals("alive", body(socket, response))
                }
            }
        }
    }

    @Test fun stopCancelsBothBlockedUploadAndUpgradedDuplexWithoutLeakingProxyThreads() {
        val before = proxyThreads()
        val uploadEntered = CountDownLatch(1)
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            if (request.line.contains("/upload ")) {
                uploadEntered.countDown()
                origin.getInputStream().read()
            } else {
                write(origin, SWITCH + "\r\n")
                origin.getOutputStream().write(frame("ready", false))
                origin.getOutputStream().flush()
                origin.getInputStream().read()
            }
        }.use { origin ->
            val proxy = proxy(origin)
            client(proxy).use { upload ->
                client(proxy).use { websocket ->
                    try {
                        write(upload, "POST /upload HTTP/1.1\r\nHost: browser.test\r\nContent-Length: 99999\r\n\r\n")
                        assertTrue(uploadEntered.await(1, TimeUnit.SECONDS))
                        write(websocket, websocketRequest("/chat"))
                        assertEquals(101, head(websocket.getInputStream()).status)
                        assertEquals("ready", readFrame(websocket.getInputStream(), false).text)
                        val started = System.nanoTime()
                        proxy.close()
                        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500)
                        assertEquals(0, proxy.activeSocketCount)
                        assertEquals(-1, websocket.getInputStream().read())
                        assertEquals(-1, upload.getInputStream().read())
                    } finally { proxy.close() }
                }
            }
        }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while ((proxyThreads() - before).isNotEmpty() && System.nanoTime() < until) Thread.sleep(5)
        assertEquals(emptySet<Long>(), proxyThreads() - before)
    }

    @Test fun stopInterruptsAHelperWaitingForUpstreamContinueWithoutLeakedThreads() {
        val before = proxyThreads()
        val waiting = CountDownLatch(1)
        RawOrigin { origin ->
            assertEquals("100-continue", head(origin.getInputStream()).field("Expect"))
            waiting.countDown()
            assertEquals(-1, origin.getInputStream().read())
        }.use { origin ->
            val proxy = proxy(origin, workers = 1)
            try {
                client(proxy).use { socket ->
                    write(socket, "POST /waiting HTTP/1.1\r\nHost: browser.test\r\n" +
                        "Expect: 100-continue\r\nContent-Length: 9999\r\n\r\n")
                    assertTrue(waiting.await(1, TimeUnit.SECONDS))
                    proxy.close()
                    assertEquals(0, proxy.activeSocketCount)
                    assertEquals(-1, socket.getInputStream().read())
                }
            } finally { proxy.close() }
        }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while ((proxyThreads() - before).isNotEmpty() && System.nanoTime() < until) Thread.sleep(5)
        assertEquals(emptySet<Long>(), proxyThreads() - before)
    }

    @Test fun clientCancellationOfUpgradeReleasesTheOnlyWorkerAndHelperForAnotherRequest() {
        val originClosed = CountDownLatch(1)
        RawOrigin { origin ->
            val request = head(origin.getInputStream())
            if (request.line.contains("/cancel ")) {
                write(origin, SWITCH + "\r\n")
                assertEquals(-1, origin.getInputStream().read())
                originClosed.countDown()
            } else final(origin.getOutputStream(), 200, "worker-reused")
        }.use { origin ->
            proxy(origin, workers = 1).use { proxy ->
                client(proxy).use { socket ->
                    write(socket, websocketRequest("/cancel"))
                    assertEquals(101, head(socket.getInputStream()).status)
                }
                assertTrue(originClosed.await(1, TimeUnit.SECONDS))
                client(proxy).use { socket ->
                    write(socket, "GET /after-cancel HTTP/1.1\r\nHost: browser.test\r\nConnection: close\r\n\r\n")
                    val response = head(socket.getInputStream())
                    assertEquals(200, response.status)
                    assertEquals("worker-reused", body(socket, response))
                }
            }
        }
    }

    companion object {
        private val LOOPBACK = InetAddress.getByName("127.0.0.1")
        private const val KEY = "dGhlIHNhbXBsZSBub25jZQ=="
        private const val ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        private const val SWITCH = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n" +
            "Upgrade: websocket\r\nSec-WebSocket-Accept: $ACCEPT\r\n"
        private val clientCa by lazy { EphemeralTestCa.create() }
        private val originCa by lazy { EphemeralTestCa.create() }
        private val clientLeaves by lazy { LeafCertificateCache(clientCa.authority()) }
        private val originLeaves by lazy { LeafCertificateCache(originCa.authority()) }
        private val clientTrust by lazy { trust(clientCa) }
        private val originTrust by lazy { trust(originCa) }
        private fun trust(ca: EphemeralTestCa): SSLContext {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null); setCertificateEntry("ephemeral-only", ca.certificate)
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        }
        private fun websocketRequest(path: String): String =
            "GET $path HTTP/1.1\r\nHost: browser.test\r\nConnection: keep-alive, Upgrade\r\n" +
                "Upgrade: websocket\r\nSec-WebSocket-Key: $KEY\r\nSec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: chat\r\n\r\n"
        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 65_536) {
                val value = input.read()
                if (value < 0) throw EOFException()
                if (value == 13) {
                    assertEquals(10, input.read())
                    return bytes.toString("ISO-8859-1")
                }
                bytes.write(value)
            }
            throw AssertionError("Peer line too large")
        }
        private fun head(input: InputStream): Head {
            val first = line(input)
            val fields = ArrayList<Pair<String, String>>()
            while (true) {
                val value = line(input)
                if (value.isEmpty()) return Head(first, fields)
                fields.add(value.substringBefore(':') to value.substringAfter(':').trim())
            }
        }
        private fun exact(input: InputStream, length: Int): ByteArray {
            val result = ByteArray(length)
            var used = 0
            while (used < length) {
                val count = input.read(result, used, length - used)
                if (count < 0) throw EOFException()
                used += count
            }
            return result
        }
        private fun body(socket: Socket, response: Head): String =
            exact(socket.getInputStream(), response.field("Content-Length")!!.toInt()).toString(Charsets.US_ASCII)
        private fun final(output: OutputStream, status: Int, body: String) {
            output.write("HTTP/1.1 $status Origin\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
            output.flush()
        }
        private fun write(socket: Socket, text: String) {
            socket.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        }
        private fun frame(text: String, masked: Boolean): ByteArray = frame(text.toByteArray(), masked)
        private fun frame(data: ByteArray, masked: Boolean, opcode: Int = 1): ByteArray {
            require(data.size < 126)
            val mask = byteArrayOf(11, 22, 33, 44)
            val output = ByteArrayOutputStream()
            output.write(128 or opcode)
            output.write(data.size or if (masked) 128 else 0)
            if (masked) output.write(mask)
            output.write(ByteArray(data.size) { index ->
                (data[index].toInt() xor if (masked) mask[index % 4].toInt() else 0).toByte()
            })
            return output.toByteArray()
        }
        private fun readFrame(input: InputStream, masked: Boolean): Frame {
            val first = input.read()
            val second = input.read()
            assertTrue(first >= 0 && second >= 0)
            assertTrue(first and 128 != 0)
            assertEquals(masked, second and 128 != 0)
            val length = second and 127
            assertTrue(length < 126)
            val mask = if (masked) exact(input, 4) else ByteArray(4)
            val data = exact(input, length)
            return Frame(first and 15, ByteArray(length) { index ->
                (data[index].toInt() xor mask[index % 4].toInt()).toByte()
            })
        }
        private fun proxyThreads(): Set<Long> = Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name.startsWith("native-proxy-") }.map { it.threadId() }.toSet()
    }
}
