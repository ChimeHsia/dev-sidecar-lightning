package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.*
import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import com.dsha.sidecar.net.proxy.tls.LeafCertificateCache
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

class NativeProxyServerTest {
    private data class Reply(val status: Int, val headers: Http1Headers, val body: String)
    private data class Received(
        val head: Http1RequestHead,
        val body: String,
        val sni: String?,
        val protocol: String?,
    )

    private class Origin(
        tlsHost: String? = null,
        private val respond: (Received, OutputStream) -> Unit = { request, output ->
            fixed(output, request.head.target.raw)
        },
    ) : Closeable {
        val received = CopyOnWriteArrayList<Received>()
        val accepted = AtomicInteger()
        private val failed = AtomicReference<Throwable?>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val done = AtomicReference(false)
        private val server = if (tlsHost == null) ServerSocket(0, 20, LOOPBACK) else
            originLeaves.get(tlsHost).newServerSocket().apply { bind(InetSocketAddress(LOOPBACK, 0)) }
        val address = InetSocketAddress(LOOPBACK, server.localPort)
        private val thread = Thread {
            while (!done.get()) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    accepted.incrementAndGet()
                    socket.use {
                        socket.soTimeout = 5_000
                        val sni = if (socket is SSLSocket) {
                            socket.startHandshake()
                            ((socket.session as ExtendedSSLSession).requestedServerNames.firstOrNull() as? SNIHostName)
                                ?.asciiName
                        } else null
                        val reader = Http1Reader(socket.getInputStream(), unlimited)
                        val request = reader.readRequest() ?: return@use
                        val bytes = ByteArrayOutputStream()
                        reader.transferBody(bytes, Http1ChunkMode.DECODE)
                        val record = Received(request.head, bytes.toString("UTF-8"), sni,
                            (socket as? SSLSocket)?.session?.protocol)
                        received.add(record)
                        respond(record, socket.getOutputStream())
                        socket.getOutputStream().flush()
                    }
                    sockets.remove(socket)
                } catch (_: IOException) {
                    // Certificate rejection / intentional timeout and cancellation are test inputs.
                } catch (failure: Throwable) {
                    failed.set(failure)
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() {
            done.set(true)
            server.close()
            sockets.forEach { runCatching { it.close() } }
            thread.join(3_000)
            failed.get()?.let { throw AssertionError("Origin failed", it) }
        }
    }

    private fun server(
        route: NativeProxyRoute,
        options: NativeProxyOptions = NativeProxyOptions(),
        transform: NativeRequestTransformer = NativeRequestTransformer { it.head },
        resolver: RouteResolver = RouteResolver { route },
        policy: NativeExchangePolicy = NativeExchangePolicy.Passthrough,
    ): NativeProxyServer = NativeProxyServer(resolver, downstreamLeaves, options, transform,
        exchangePolicy = policy).apply { start() }

    private fun client(proxy: NativeProxyServer): Socket =
        Socket().apply { connect(proxy.localAddress); soTimeout = 5_000 }

    private fun tlsClient(proxy: NativeProxyServer, host: String = "browser.test"): SSLSocket {
        val raw = client(proxy)
        send(raw, "CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n")
        assertEquals(200, Http1HeadCodec.readResponse(raw.getInputStream(), "CONNECT")!!.head.status)
        return (clientTrust.socketFactory.createSocket(raw, host, 443, true) as SSLSocket).apply {
            soTimeout = 5_000
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
        }
    }

    private fun reply(reader: Http1Reader, method: String = "GET"): Reply {
        val response = reader.readResponse(method) ?: throw AssertionError("Missing response")
        val body = ByteArrayOutputStream()
        reader.transferBody(body, Http1ChunkMode.DECODE)
        return Reply(response.head.status, response.head.headers, body.toString("UTF-8"))
    }

    @Test fun startBindFailureAndStopAreDeterministic() {
        Origin().use { origin ->
            val first = server(NativeProxyRoute(origin.address))
            first.use {
                assertEquals(NativeProxyState.LISTENING, first.state)
                val second = NativeProxyServer(RouteResolver { NativeProxyRoute(origin.address) }, null,
                    NativeProxyOptions(port = first.localAddress!!.port))
                try {
                    second.start()
                    fail("Bind must fail")
                } catch (_: BindException) {
                    assertEquals(NativeProxyState.FAILED, second.state)
                    assertNotNull(second.failure)
                } finally { second.close() }
                client(first).use { socket ->
                    send(socket, get("/still-alive", "plain.test"))
                    assertEquals(200, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
            first.close()
            assertEquals(NativeProxyState.STOPPED, first.state)
            assertEquals(0, first.activeSocketCount)
            try { first.start(); fail("Single-use") } catch (_: IllegalStateException) { }
        }
        try { NativeProxyOptions(listenAddress = InetAddress.getByName("0.0.0.0")); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun absoluteAndOriginRequestsStreamSequentiallyWithSeparateTransformation() {
        Origin().use { origin ->
            val transform = NativeRequestTransformer {
                it.head.copy(headers = Http1Headers(it.head.headers.fields + Http1Header("X-Injected", " yes")))
            }
            server(NativeProxyRoute(origin.address), transform = transform).use { proxy ->
                client(proxy).use { socket ->
                    val reader = Http1Reader(socket.getInputStream())
                    send(socket, get("http://plain.test/one?q=2", "plain.test"))
                    assertEquals("/one?q=2", reply(reader).body)
                    send(socket, get("/two", "plain.test", close = true))
                    val response = reply(reader)
                    assertEquals("/two", response.body)
                    assertTrue(response.headers.hasToken("Connection", "close"))
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertEquals(2, origin.accepted.get())
            assertEquals(listOf("/one?q=2", "/two"), origin.received.map { it.head.target.raw })
            assertTrue(origin.received.all { it.head.headers.values("X-Injected") == listOf("yes") })
            assertTrue(origin.received.all { it.head.headers.hasToken("Connection", "close") })
        }
    }

    @Test fun verifiedMitmDecryptsPathsAndSeparatesTcpSniAndVerificationHost() {
        Origin("origin.test").use { origin ->
            val policy = NativeTlsPolicy("origin.test", "routing.test", trustContext = originTrust)
            server(NativeProxyRoute(origin.address, tls = policy)).use { proxy ->
                tlsClient(proxy).use { socket ->
                    assertEquals("browser.test", (socket.session.peerCertificates.first() as
                        java.security.cert.X509Certificate).subjectAlternativeNames.first { it[0] == 2 }[1])
                    val reader = Http1Reader(socket.getInputStream())
                    send(socket, get("/decrypted/one", "browser.test"))
                    assertEquals("/decrypted/one", reply(reader).body)
                    send(socket, get("https://browser.test/decrypted/two", "browser.test", close = true))
                    assertEquals("/decrypted/two", reply(reader).body)
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertEquals(listOf("/decrypted/one", "/decrypted/two"), origin.received.map { it.head.target.raw })
            assertEquals(listOf("routing.test", "routing.test"), origin.received.map { it.sni })
        }
    }

    @Test fun connectAndEarlyClientHelloInOneWriteSurviveBufferedTlsHandoff() {
        Origin("origin.test").use { origin ->
            server(NativeProxyRoute(origin.address, tls = NativeTlsPolicy("origin.test",
                trustContext = originTrust))).use { proxy ->
                val helloWritten = AtomicBoolean()
                val raw = object : Socket() {
                    override fun getOutputStream(): OutputStream {
                        val transport = super.getOutputStream()
                        return object : OutputStream() {
                            override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                if (helloWritten.compareAndSet(false, true)) {
                                    val connect = ("CONNECT browser.test:443 HTTP/1.1\r\n" +
                                        "Host: browser.test:443\r\n\r\n").toByteArray()
                                    val coalesced = ByteArray(connect.size + length)
                                    connect.copyInto(coalesced)
                                    bytes.copyInto(coalesced, connect.size, offset, offset + length)
                                    transport.write(coalesced)
                                } else transport.write(bytes, offset, length)
                            }
                            override fun flush() { transport.flush() }
                        }
                    }
                    override fun getInputStream(): InputStream {
                        val transport = super.getInputStream()
                        return object : FilterInputStream(transport) {
                            private var acknowledged = false
                            private fun acknowledge() {
                                if (!acknowledged) {
                                    acknowledged = true
                                    assertEquals(200, Http1HeadCodec.readResponse(transport, "CONNECT")!!.head.status)
                                }
                            }
                            override fun read(): Int { acknowledge(); return transport.read() }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                acknowledge()
                                return transport.read(bytes, offset, length)
                            }
                        }
                    }
                }.apply { connect(proxy.localAddress); soTimeout = 5_000 }
                (clientTrust.socketFactory.createSocket(raw, "browser.test", 443, true) as SSLSocket).use { secure ->
                    secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    secure.startHandshake()
                    assertTrue(helloWritten.get())
                    send(secure, get("/early-hello-was-retained", "browser.test", close = true))
                    assertEquals("/early-hello-was-retained", reply(Http1Reader(secure.getInputStream())).body)
                }
                assertEquals("/early-hello-was-retained", origin.received.single().head.target.raw)
            }
        }
    }

    @Test fun strictWrongHostAndUntrustedIssuerRejectWithoutDowngradeAndServiceContinues() {
        Origin("origin.test").use { origin ->
            val good = NativeProxyRoute(origin.address, tls = NativeTlsPolicy("origin.test", trustContext = originTrust))
            val resolver = RouteResolver {
                when (it.head.target.raw) {
                    "/wrong" -> good.copy(tls = NativeTlsPolicy("wrong.test", "origin.test", trustContext = originTrust))
                    "/untrusted" -> good.copy(tls = NativeTlsPolicy("origin.test"))
                    else -> good
                }
            }
            server(good, resolver = resolver).use { proxy ->
                for (path in listOf("/wrong", "/untrusted", "/good")) {
                    tlsClient(proxy).use { socket ->
                        send(socket, get(path, "browser.test", close = true))
                        assertEquals(if (path == "/good") 200 else 502,
                            reply(Http1Reader(socket.getInputStream())).status)
                    }
                    assertEquals(NativeProxyState.LISTENING, proxy.state)
                }
            }
            assertEquals(listOf("/good"), origin.received.map { it.head.target.raw })
        }
    }

    @Test fun relaxedTrustIsExplicitlyScopedToEachRoute() {
        Origin("origin.test").use { origin ->
            val strict = NativeProxyRoute(origin.address,
                tls = NativeTlsPolicy("wrong.test", trustContext = originTrust))
            val resolver = RouteResolver {
                when (it.head.target.raw) {
                    "/chain" -> strict.copy(tls = strict.tls!!.copy(trustMode = UpstreamTrustMode.CHAIN_ONLY))
                    "/insecure" -> strict.copy(tls = NativeTlsPolicy("wrong.test",
                        trustMode = UpstreamTrustMode.UNVERIFIED))
                    else -> strict
                }
            }
            server(strict, resolver = resolver).use { proxy ->
                for (path in listOf("/chain", "/strict", "/insecure", "/strict")) {
                    tlsClient(proxy).use { socket ->
                        send(socket, get(path, "browser.test", close = true))
                        assertEquals(if (path == "/strict") 502 else 200,
                            reply(Http1Reader(socket.getInputStream())).status)
                    }
                }
            }
        }
    }

    @Test fun injectedTlsDialerIsActuallyUsedAndStrictTrustStillApplies() {
        val calls = AtomicInteger()
        val delegate = NativeTlsConnector()
        val injected = NativeTlsDialer { raw, policy, timeout ->
            calls.incrementAndGet()
            assertTrue(raw.isConnected)
            assertEquals(UpstreamTrustMode.STRICT, policy.trustMode)
            assertEquals("routing.test", policy.sniHost)
            assertEquals(750, timeout)
            delegate.connect(raw, policy, timeout)
        }
        Origin("origin.test").use { origin ->
            val resolver = RouteResolver {
                NativeProxyRoute(origin.address, tls = NativeTlsPolicy(
                    verifyHost = if (it.head.target.raw == "/wrong") "wrong.test" else "origin.test",
                    sniHost = "routing.test", trustContext = originTrust,
                ))
            }
            NativeProxyServer(resolver, downstreamLeaves,
                NativeProxyOptions(handshakeTimeoutMillis = 750), tlsConnector = injected).use { proxy ->
                proxy.start()
                for (path in listOf("/injected", "/wrong", "/still-strict")) {
                    tlsClient(proxy).use { socket ->
                        send(socket, get(path, "browser.test", close = true))
                        assertEquals(if (path == "/wrong") 502 else 200,
                            reply(Http1Reader(socket.getInputStream())).status)
                    }
                }
            }
            assertEquals(3, calls.get())
            assertEquals(listOf("/injected", "/still-strict"), origin.received.map { it.head.target.raw })
            assertTrue(origin.received.all { it.sni == "routing.test" })
        }
    }

    @Test fun injectedDialerCannotEscapeServerOwnedRawSocketHandshakeDeadline() {
        val entered = CountDownLatch(1)
        ServerSocket(0, 2, LOOPBACK).use { rawOrigin ->
            val route = NativeProxyRoute(InetSocketAddress(LOOPBACK, rawOrigin.localPort),
                tls = NativeTlsPolicy("origin.test", trustContext = originTrust))
            val injected = NativeTlsDialer { raw, policy, _ ->
                assertEquals(UpstreamTrustMode.STRICT, policy.trustMode)
                raw.soTimeout = 0
                entered.countDown()
                raw.getInputStream().read()
                throw AssertionError("Server deadline must abort the blocked raw read")
            }
            NativeProxyServer(RouteResolver { route }, downstreamLeaves,
                NativeProxyOptions(handshakeTimeoutMillis = 150), tlsConnector = injected).use { proxy ->
                proxy.start()
                client(proxy).use { socket ->
                    send(socket, get("/deadline", "plain.test"))
                    assertEquals(504, reply(Http1Reader(socket.getInputStream())).status)
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    assertEquals(NativeProxyState.LISTENING, proxy.state)
                }
            }
        }
    }

    @Test fun tls12PolicyAndExplicitNullSniLeaveGlobalDefaultsAlone() {
        val defaultContext = SSLContext.getDefault()
        val defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        val providers = java.security.Security.getProviders().map { it.name }
        Origin("origin.test").use { origin ->
            val policy = NativeTlsPolicy("origin.test", sniHost = null, trustContext = originTrust,
                protocols = listOf("TLSv1.2"))
            server(NativeProxyRoute(origin.address, tls = policy),
                NativeProxyOptions(downstreamProtocols = listOf("TLSv1.2"))).use { proxy ->
                tlsClient(proxy).use { socket ->
                    assertEquals("TLSv1.2", socket.session.protocol)
                    send(socket, get("/tls12", "browser.test", close = true))
                    assertEquals(200, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
            assertNull(origin.received.single().sni)
            assertEquals("TLSv1.2", origin.received.single().protocol)
        }
        assertSame(defaultContext, SSLContext.getDefault())
        assertSame(defaultVerifier, javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier())
        assertEquals(providers, java.security.Security.getProviders().map { it.name })
        try { NativeTlsPolicy("origin.test", protocols = listOf("TLSv1.1")); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun tls13PolicyIsUsedOnBothLegsWhenSupported() {
        Origin("origin.test").use { origin ->
            server(NativeProxyRoute(origin.address, tls = NativeTlsPolicy("origin.test",
                trustContext = originTrust, protocols = listOf("TLSv1.3"))),
                NativeProxyOptions(downstreamProtocols = listOf("TLSv1.3"))).use { proxy ->
                tlsClient(proxy).use { socket ->
                    assertEquals("TLSv1.3", socket.session.protocol)
                    send(socket, get("/tls13", "browser.test", close = true))
                    assertEquals(200, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
            assertEquals("TLSv1.3", origin.received.single().protocol)
        }
    }

    @Test fun fixedAndChunkedBodiesPreserveMessageBoundaries() {
        Origin { request, output ->
            val text = request.body
            output.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: X-End\r\n\r\n" +
                "${text.length.toString(16)};x=y\r\n$text\r\n0\r\nX-End: yes\r\n\r\n").toByteArray())
        }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    val reader = Http1Reader(socket.getInputStream())
                    send(socket, "POST /fixed HTTP/1.1\r\nHost: plain.test\r\nContent-Length: 5\r\n\r\nhello" +
                        "POST /chunk HTTP/1.1\r\nHost: plain.test\r\nTransfer-Encoding: chunked\r\n" +
                        "Connection: close\r\n\r\n2\r\nab\r\n3\r\ncde\r\n0\r\nX-Input: yes\r\n\r\n")
                    assertEquals("hello", reply(reader, "POST").body)
                    assertEquals("abcde", reply(reader, "POST").body)
                }
            }
            assertEquals(listOf("hello", "abcde"), origin.received.map { it.body })
        }
    }

    @Test fun headInformationalAndNoBodyResponsesDoNotConsumeNextMessage() {
        Origin { request, output ->
            val response = when (request.head.method) {
                "HEAD" -> "HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n"
                else -> "HTTP/1.1 103 Early Hints\r\nLink: </style>\r\n\r\n" +
                    "HTTP/1.1 204 No Content\r\n\r\n"
            }
            output.write(response.toByteArray())
        }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    val reader = Http1Reader(socket.getInputStream())
                    send(socket, "HEAD /head HTTP/1.1\r\nHost: plain.test\r\n\r\n")
                    assertEquals("", reply(reader, "HEAD").body)
                    send(socket, get("/no-content", "plain.test"))
                    assertEquals(103, reply(reader).status)
                    assertEquals(204, reply(reader).status)
                }
            }
        }
    }

    @Test fun closeDelimitedResponseForcesDownstreamClose() {
        Origin { _, output -> output.write("HTTP/1.1 200 OK\r\n\r\nuntil-eof".toByteArray()) }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/close", "plain.test"))
                    val response = reply(Http1Reader(socket.getInputStream()))
                    assertEquals("until-eof", response.body)
                    assertTrue(response.headers.hasToken("Connection", "close"))
                }
            }
        }
    }

    @Test fun http10ChunkedOriginIsDecodedAndClosed() {
        Origin { _, output ->
            output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray())
        }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "GET /legacy HTTP/1.0\r\nHost: plain.test\r\n\r\n")
                    val response = reply(Http1Reader(socket.getInputStream()))
                    assertEquals("abc", response.body)
                    assertTrue(response.headers.values("Transfer-Encoding").isEmpty())
                    assertTrue(response.headers.hasToken("Connection", "close"))
                }
            }
        }
    }

    @Test fun malformedClientsAndUnsupportedFeaturesDoNotKillListener() {
        Origin().use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                val cases = listOf(
                    "GET / HTTP/1.1\nHost: plain.test\n\n" to 400,
                    "GET / HTTP/1.1\r\nHost: plain.test\r\nHost: other.test\r\n\r\n" to 400,
                    "GET http://other.test/ HTTP/1.1\r\nHost: plain.test\r\n\r\n" to 400,
                    "GET //other.test/ HTTP/1.1\r\nHost: plain.test\r\n\r\n" to 400,
                    "GET https://plain.test/ HTTP/1.1\r\nHost: plain.test\r\n\r\n" to 400,
                    "POST / HTTP/1.1\r\nHost: plain.test\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n" to 400,
                    "POST / HTTP/1.1\r\nHost: plain.test\r\nExpect: unknown\r\nContent-Length: 3\r\n\r\n" to 417,
                    "GET / HTTP/1.1\r\nHost: plain.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n" to 400,
                )
                for ((wire, status) in cases) client(proxy).use { socket ->
                    send(socket, wire)
                    assertEquals(wire, status, reply(Http1Reader(socket.getInputStream())).status)
                }
                client(proxy).use { socket ->
                    send(socket, get("/good", "plain.test", close = true))
                    assertEquals(200, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
        }
    }

    @Test fun connectHostOriginAndAbsoluteTargetsStayBoundInsideTls() {
        Origin("origin.test").use { origin ->
            server(NativeProxyRoute(origin.address, tls = NativeTlsPolicy("origin.test",
                trustContext = originTrust))).use { proxy ->
                for ((target, host) in listOf(
                    "/" to "escape.test",
                    "https://escape.test/" to "browser.test",
                    "https://browser.test:444/" to "browser.test",
                    "http://browser.test/" to "browser.test",
                )) tlsClient(proxy).use { socket ->
                    send(socket, get(target, host))
                    assertEquals(400, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
            assertTrue(origin.received.isEmpty())
        }
    }

    @Test fun transformedFramingAndAuthorityAreRevalidatedBeforeUpstreamIo() {
        Origin().use { origin ->
            val transforms = listOf<NativeRequestTransformer>(
                NativeRequestTransformer { it.head.copy(headers = Http1Headers(it.head.headers.fields +
                    Http1Header("Content-Length", " 4"))) },
                NativeRequestTransformer { it.head.copy(headers = Http1Headers(it.head.headers.fields +
                    Http1Header("Content-Length", " 3"))) },
                NativeRequestTransformer { it.head.copy(target =
                    Http1Target.Absolute("http://escape.test/", "escape.test", "/")) },
                NativeRequestTransformer { it.head.copy(headers = Http1Headers(it.head.headers.fields +
                    Http1Header("Connection", " content-length"))) },
            )
            for (transform in transforms) server(NativeProxyRoute(origin.address), transform = transform).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "POST / HTTP/1.1\r\nHost: plain.test\r\nContent-Length: 3\r\n\r\nabc")
                    assertEquals(400, reply(Http1Reader(socket.getInputStream()), "POST").status)
                }
            }
            assertTrue(origin.received.isEmpty())
        }
    }

    @Test fun invalidChunkFails400AndTruncatedUpstreamDoesNotAppendAnErrorResponse() {
        Origin { _, output ->
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial".toByteArray())
        }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "POST / HTTP/1.1\r\nHost: plain.test\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n")
                    assertEquals(400, reply(Http1Reader(socket.getInputStream())).status)
                }
                client(proxy).use { socket ->
                    send(socket, get("/", "plain.test"))
                    val wire = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                    assertTrue(wire.startsWith("HTTP/1.1 200 "))
                    assertEquals(1, Regex("HTTP/1.1").findAll(wire).count())
                    assertTrue(wire.endsWith("partial"))
                }
            }
        }
    }

    @Test fun upstreamUpgradeIsExplicit502() {
        Origin { _, output ->
            output.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".toByteArray())
        }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/", "plain.test"))
                    assertEquals(502, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
        }
    }

    @Test fun malformedUpstreamFramingReturns502() {
        Origin { _, output ->
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n").toByteArray())
        }.use { origin ->
            server(NativeProxyRoute(origin.address)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/", "plain.test"))
                    assertEquals(502, reply(Http1Reader(socket.getInputStream())).status)
                }
                assertEquals(NativeProxyState.LISTENING, proxy.state)
            }
        }
    }

    @Test fun upstreamReadAndHandshakeTimeoutsReturn504AndListenerSurvives() {
        val unblock = CountDownLatch(1)
        Origin { _, _ -> unblock.await(3, TimeUnit.SECONDS) }.use { origin ->
            try {
                server(NativeProxyRoute(origin.address), NativeProxyOptions(readTimeoutMillis = 150)).use { proxy ->
                    client(proxy).use { socket ->
                        send(socket, get("/", "plain.test"))
                        assertEquals(504, reply(Http1Reader(socket.getInputStream())).status)
                    }
                    assertEquals(NativeProxyState.LISTENING, proxy.state)
                }
            } finally { unblock.countDown() }
        }
        ServerSocket(0, 2, LOOPBACK).use { rawOrigin ->
            server(NativeProxyRoute(InetSocketAddress(LOOPBACK, rawOrigin.localPort),
                tls = NativeTlsPolicy("origin.test", trustContext = originTrust)),
                NativeProxyOptions(handshakeTimeoutMillis = 150)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/", "plain.test"))
                    assertEquals(504, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
        }
    }

    @Test fun connectBlockDoesNotContactOrigin() {
        Origin().use { origin ->
            server(NativeProxyRoute(origin.address, ConnectPolicy.BLOCK)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "CONNECT browser.test:443 HTTP/1.1\r\nHost: browser.test:443\r\n\r\n")
                    assertEquals(403, reply(Http1Reader(socket.getInputStream()), "CONNECT").status)
                }
            }
            assertEquals(0, origin.accepted.get())
        }
    }

    @Test fun tunnelRetainsEarlyBytesAndHalfClose() {
        Origin().use { origin ->
            server(NativeProxyRoute(origin.address, ConnectPolicy.TUNNEL)).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "CONNECT browser.test:443 HTTP/1.1\r\nHost: browser.test:443\r\n\r\n" +
                        get("/early-tunnel", "opaque.test", close = true))
                    socket.shutdownOutput()
                    assertEquals(200, Http1HeadCodec.readResponse(socket.getInputStream(), "CONNECT")!!.head.status)
                    assertEquals("/early-tunnel", reply(Http1Reader(socket.getInputStream())).body)
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
        }
    }

    @Test fun stopClosesActiveAndQueuedClientsAndBlockedUpstream() {
        val originEntered = CountDownLatch(1)
        val releaseOrigin = CountDownLatch(1)
        Origin { _, _ -> originEntered.countDown(); releaseOrigin.await(5, TimeUnit.SECONDS) }.use { origin ->
            val proxy = server(NativeProxyRoute(origin.address),
                NativeProxyOptions(workers = 1, queuedConnections = 1, stopTimeoutMillis = 2_000))
            val one = client(proxy)
            val two = client(proxy)
            try {
                send(one, get("/blocked", "plain.test"))
                assertTrue(originEntered.await(2, TimeUnit.SECONDS))
                send(two, "GET /")
                val start = System.nanoTime()
                proxy.close()
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_500)
                assertEquals(0, proxy.activeSocketCount)
                assertEquals(-1, one.getInputStream().read())
                assertEquals(-1, two.getInputStream().read())
            } finally {
                releaseOrigin.countDown()
                one.close(); two.close(); proxy.close()
            }
        }
    }

    @Test fun stopClosesUnfinishedDownstreamTlsHandshake() {
        Origin().use { origin ->
            val proxy = server(NativeProxyRoute(origin.address))
            client(proxy).use { socket ->
                send(socket, "CONNECT browser.test:443 HTTP/1.1\r\nHost: browser.test:443\r\n\r\n")
                assertEquals(200, Http1HeadCodec.readResponse(socket.getInputStream(), "CONNECT")!!.head.status)
                proxy.close()
                assertEquals(0, proxy.activeSocketCount)
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test fun localPolicySuccessAbortRedirectAndOptionsNeverResolveOrContactUpstream() {
        val resolutions = AtomicInteger()
        val policy = object : NativeExchangePolicy {
            override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
                val status = when (request.head.target.raw) {
                    "/redirect" -> 302
                    "/abort" -> 403
                    "/options" -> 204
                    else -> 200
                }
                return NativeRequestDecision.LocalResponse(
                    Http1ResponseHead("HTTP/1.1", status, "Local",
                        Http1Headers(listOf(Http1Header("Location", " https://redirect.test/"),
                            Http1Header("Access-Control-Allow-Origin", " *")))),
                    if (status == 204) byteArrayOf() else "local-$status".toByteArray())
            }
        }
        Origin().use { origin ->
            server(NativeProxyRoute(origin.address), resolver = RouteResolver {
                resolutions.incrementAndGet()
                throw AssertionError("Local response must precede resolution")
            }, policy = policy).use { proxy ->
                for ((path, status) in listOf("/success" to 200, "/abort" to 403,
                    "/redirect" to 302, "/options" to 204)) {
                    client(proxy).use { socket ->
                        val method = if (path == "/options") "OPTIONS" else "GET"
                        send(socket, "$method $path HTTP/1.1\r\nHost: plain.test\r\n\r\n")
                        val response = reply(Http1Reader(socket.getInputStream()), method)
                        assertEquals(status, response.status)
                        assertEquals(if (status == 204) "" else "local-$status", response.body)
                        assertEquals(listOf("*"), response.headers.values("Access-Control-Allow-Origin"))
                        assertTrue(response.headers.hasToken("Connection", "close"))
                    }
                }
            }
            assertEquals(0, resolutions.get())
            assertEquals(0, origin.accepted.get())
        }
    }

    @Test fun trustedForwardMayRewriteAuthorityPathAndRouteInsideVerifiedConnect() {
        val decisions = AtomicInteger()
        val responseAuthority = AtomicReference<String?>()
        Origin("origin.test").use { origin ->
            val route = NativeProxyRoute(origin.address, tls = NativeTlsPolicy("origin.test",
                sniHost = "routing.test", trustContext = originTrust))
            val policy = object : NativeExchangePolicy {
                override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
                    if (request.head.method == "CONNECT") return NativeRequestDecision.Forward(request.head)
                    assertEquals("browser.test", request.authority.host)
                    assertEquals("browser.test", request.connectAuthority!!.host)
                    decisions.incrementAndGet()
                    val rewritten = request.head.copy(
                        target = Http1Target.Absolute("https://rule.test/rewritten", "rule.test", "/rewritten"),
                        headers = Http1Headers(request.head.headers.fields.filterNot {
                            it.name.equals("Host", true)
                        } + Http1Header("Host", " rule.test")),
                    )
                    return NativeRequestDecision.Forward(rewritten, route)
                }
                override fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead): Http1ResponseHead {
                    responseAuthority.set(request.authority.host)
                    assertEquals("browser.test", request.connectAuthority!!.host)
                    return head
                }
            }
            server(route, policy = policy).use { proxy ->
                tlsClient(proxy).use { socket ->
                    send(socket, get("/before-rule", "browser.test", close = true))
                    assertEquals("/rewritten", reply(Http1Reader(socket.getInputStream())).body)
                }
            }
            assertEquals(1, decisions.get())
            assertEquals("rule.test", responseAuthority.get())
            assertEquals(listOf("rule.test:443"), origin.received.single().head.headers.values("Host"))
            assertEquals("routing.test", origin.received.single().sni)
        }
    }

    @Test fun trustedRequestRewriteCannotSmuggleFramingAndUntrustedRewriteCannotReachPolicy() {
        Origin().use { origin ->
            val called = AtomicInteger()
            val policy = object : NativeExchangePolicy {
                override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
                    called.incrementAndGet()
                    return NativeRequestDecision.Forward(request.head.copy(headers =
                        Http1Headers(request.head.headers.fields + Http1Header("Content-Length", " 1"))))
                }
            }
            server(NativeProxyRoute(origin.address), policy = policy).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "POST / HTTP/1.1\r\nHost: plain.test\r\nContent-Length: 1\r\n\r\nx")
                    assertEquals(502, reply(Http1Reader(socket.getInputStream()), "POST").status)
                }
            }
            assertEquals(1, called.get())
            val transform = NativeRequestTransformer {
                it.head.copy(target = Http1Target.Absolute("http://escape.test/", "escape.test", "/"),
                    headers = Http1Headers(listOf(Http1Header("Host", " escape.test"))))
            }
            server(NativeProxyRoute(origin.address), transform = transform, policy = policy).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/", "plain.test"))
                    assertEquals(400, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
            assertEquals(1, called.get())
            assertEquals(0, origin.accepted.get())
        }
    }

    @Test fun responsePolicyCannotChangeLengthOrInjectHeaders() {
        Origin().use { origin ->
            for (fields in listOf(
                listOf(Http1Header("Content-Length", " 999")),
                listOf(Http1Header("Content-Length", " 1"), Http1Header("X-Injected", " ok\r\nbad: yes")),
            )) {
                val policy = object : NativeExchangePolicy {
                    override fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead) =
                        head.copy(headers = Http1Headers(fields))
                }
                server(NativeProxyRoute(origin.address), policy = policy).use { proxy ->
                    client(proxy).use { socket ->
                        send(socket, get("/", "plain.test"))
                        assertEquals(502, reply(Http1Reader(socket.getInputStream())).status)
                    }
                }
            }
        }
    }

    @Test fun oversizedLocalResponseIsRejectedBeforeCommitAndBeforeUpstream() {
        Origin().use { origin ->
            val policy = object : NativeExchangePolicy {
                override fun onRequest(request: NativeProxyRequest) = NativeRequestDecision.LocalResponse(
                    Http1ResponseHead("HTTP/1.1", 200, "Local", Http1Headers(emptyList())), ByteArray(65_537))
            }
            server(NativeProxyRoute(origin.address), policy = policy).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/", "plain.test"))
                    assertEquals(502, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
            assertEquals(0, origin.accepted.get())
        }
    }

    @Test fun bodyQuotaIsExplicitAndDefaultAllowsDownloadsBeyond64MiB() {
        assertEquals(Long.MAX_VALUE, NativeProxyOptions().limits.maxBodyBytes)
        assertEquals(65_536, NativeProxyOptions().limits.maxHeadBytes)
        val length = 65L * 1024 * 1024
        Origin { _, output ->
            output.write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\n\r\n".toByteArray())
            val bytes = ByteArray(8192) { 7 }
            var left = length
            while (left > 0) {
                val count = minOf(left, bytes.size.toLong()).toInt()
                output.write(bytes, 0, count)
                left -= count
            }
        }.use { origin ->
            val corsPolicy = object : NativeExchangePolicy {
                override fun onResponseHead(request: NativeProxyRequest, head: Http1ResponseHead) =
                    head.copy(headers = Http1Headers(head.headers.fields +
                        Http1Header("Access-Control-Allow-Origin", " https://browser.test") +
                        Http1Header("X-Rule-Response", " streaming")))
            }
            server(NativeProxyRoute(origin.address), policy = corsPolicy).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, get("/large", "plain.test", close = true))
                    val reader = Http1Reader(socket.getInputStream(), unlimited)
                    val response = reader.readResponse("GET")!!.head
                    assertEquals(200, response.status)
                    assertEquals(listOf("streaming"), response.headers.values("X-Rule-Response"))
                    assertEquals(listOf("https://browser.test"), response.headers.values("Access-Control-Allow-Origin"))
                    val count = AtomicReference(0L)
                    reader.transferBody(object : OutputStream() {
                        override fun write(b: Int) { assertEquals(7, b); count.set(count.get() + 1) }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            assertTrue((off until off + len).all { b[it].toInt() == 7 })
                            count.set(count.get() + len)
                        }
                    })
                    assertEquals(length, count.get())
                }
            }
        }
        Origin().use { origin ->
            server(NativeProxyRoute(origin.address),
                NativeProxyOptions(limits = Http1Limits(maxBodyBytes = 2))).use { proxy ->
                client(proxy).use { socket ->
                    send(socket, "POST / HTTP/1.1\r\nHost: plain.test\r\nContent-Length: 3\r\n\r\nabc")
                    assertEquals(400, reply(Http1Reader(socket.getInputStream())).status)
                }
            }
        }
    }

    @Test fun preparedEntityUsesOneFramedMessageBeforeDownstreamCommit() {
        for (bodyWire in listOf(
            "Content-Length: 3\r\n\r\nabc",
            "Transfer-Encoding: chunked\r\n\r\n1\r\na\r\n2\r\nbc\r\n0\r\n\r\n",
            "Connection: close\r\n\r\nabc",
        )) {
            val calls = AtomicInteger()
            Origin { _, output ->
                output.write(("HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\n" + bodyWire).toByteArray())
            }.use { origin ->
                val policy = object : NativeExchangePolicy {
                    override fun onResponseEntity(
                        request: NativeProxyRequest, head: Http1ResponseHead, entity: NativeResponseEntity,
                    ): NativePreparedResponse {
                        calls.incrementAndGet()
                        assertEquals("abc", entity.read(3).toString(Charsets.US_ASCII))
                        return NativePreparedResponse(head.copy(headers = Http1Headers(listOf(
                            Http1Header("Content-Length", " 4"),
                        ))), "done".toByteArray())
                    }
                }
                server(NativeProxyRoute(origin.address), policy = policy).use { proxy ->
                    client(proxy).use { socket ->
                        send(socket, get("/", "plain.test"))
                        val reader = Http1Reader(socket.getInputStream())
                        assertEquals(103, reply(reader).status)
                        val response = reply(reader)
                        assertEquals(200, response.status)
                        assertEquals("done", response.body)
                        assertEquals(listOf("4"), response.headers.values("Content-Length"))
                        assertEquals(listOf("close"), response.headers.values("Connection"))
                        assertTrue(response.headers.values("Transfer-Encoding").isEmpty())
                        assertNull(reader.readResponse("GET"))
                    }
                }
            }
            assertEquals(1, calls.get())
        }
    }

    @Test fun entityContractViolationsProduceOnlyOneUncommittedError() {
        for (violation in listOf("unread", "fallback", "twice", "length", "status", "transfer", "quota")) {
            Origin { _, output -> fixed(output, "abc") }.use { origin ->
                val policy = object : NativeExchangePolicy {
                    override fun onResponseEntity(
                        request: NativeProxyRequest, head: Http1ResponseHead, entity: NativeResponseEntity,
                    ): NativePreparedResponse? {
                        if (violation != "unread") entity.read(3)
                        if (violation == "fallback") return null
                        if (violation == "twice") entity.read(3)
                        val fields = listOf(Http1Header("Content-Length", if (violation == "length") " 5" else " 4")) +
                            if (violation == "transfer") listOf(Http1Header("Transfer-Encoding", " chunked")) else emptyList()
                        return NativePreparedResponse(head.copy(status = if (violation == "status") 201 else 200,
                            headers = Http1Headers(fields)), "done".toByteArray())
                    }
                }
                val options = NativeProxyOptions(limits = Http1Limits(maxBodyBytes = if (violation == "quota") 3 else 100))
                server(NativeProxyRoute(origin.address), options, policy = policy).use { proxy ->
                    client(proxy).use { socket ->
                        send(socket, get("/", "plain.test", close = true))
                        val wire = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                        assertTrue(violation, wire.startsWith("HTTP/1.1 502 "))
                        assertEquals(violation, 1, Regex("HTTP/1.1").findAll(wire).count())
                        assertFalse(wire.contains("done"))
                    }
                }
                assertEquals(1, origin.accepted.get())
            }
        }
    }

    @Test fun entityPreparationRejectsTruncationTrailersAndQuotaBeforeAnyFinalHead() {
        for (bodyWire in listOf(
            "Content-Length: 4\r\n\r\nabc",
            "Content-Length: 3\r\n\r\nabc",
            "Transfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
            "Connection: close\r\n\r\nabc",
            "Transfer-Encoding: chunked\r\n\r\n2\r\nabXX0\r\n\r\n",
            "Transfer-Encoding: chunked\r\n\r\n2\r\nab\r\n0\r\nDigest: stale\r\n\r\n",
        )) {
            Origin { _, output -> output.write(("HTTP/1.1 200 OK\r\n" + bodyWire).toByteArray()) }.use { origin ->
                val policy = object : NativeExchangePolicy {
                    override fun onResponseEntity(
                        request: NativeProxyRequest, head: Http1ResponseHead, entity: NativeResponseEntity,
                    ): NativePreparedResponse {
                        try {
                            val limit = if (bodyWire.contains("Digest: stale")) 16
                                else if (bodyWire.startsWith("Content-Length: 4")) 4 else 2
                            entity.read(limit)
                            throw AssertionError("Malformed or over-quota entity accepted")
                        } catch (failure: IOException) {
                            if (bodyWire.contains("Digest: stale")) {
                                assertTrue(failure.message.orEmpty().contains("trailers"))
                            }
                            return NativePreparedResponse(head.copy(headers = Http1Headers(listOf(
                                Http1Header("Content-Length", " 0"),
                            ))), byteArrayOf())
                        }
                    }
                }
                server(NativeProxyRoute(origin.address), policy = policy).use { proxy ->
                    client(proxy).use { socket ->
                        send(socket, get("/", "plain.test", close = true))
                        val wire = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                        assertTrue(bodyWire, wire.startsWith("HTTP/1.1 502 "))
                        assertEquals(1, Regex("HTTP/1.1").findAll(wire).count())
                    }
                }
            }
        }
    }

    companion object {
        private val LOOPBACK = InetAddress.getByName("127.0.0.1")
        private val unlimited = Http1Limits(maxBodyBytes = Long.MAX_VALUE)
        private val downstreamCa by lazy { EphemeralTestCa.create() }
        private val originCa by lazy { EphemeralTestCa.create() }
        private val downstreamLeaves by lazy { LeafCertificateCache(downstreamCa.authority()) }
        private val originLeaves by lazy { LeafCertificateCache(originCa.authority()) }
        private val clientTrust by lazy { trust(downstreamCa) }
        private val originTrust by lazy { trust(originCa) }

        private fun trust(ca: EphemeralTestCa): SSLContext {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ephemeral-root-only", ca.certificate)
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(store)
            }
            return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        }

        private fun fixed(output: OutputStream, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            output.write(bytes)
        }

        private fun send(socket: Socket, text: String) {
            socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
        }

        private fun get(target: String, host: String, close: Boolean = false): String =
            "GET $target HTTP/1.1\r\nHost: $host\r\n" + (if (close) "Connection: close\r\n" else "") + "\r\n"
    }
}
