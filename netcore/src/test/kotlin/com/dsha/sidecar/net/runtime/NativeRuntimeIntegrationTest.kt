package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.proxy.http.*
import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import com.dsha.sidecar.net.proxy.tls.LeafCertificateCache
import com.dsha.sidecar.net.dns.native.SocketDnsLimits
import com.dsha.sidecar.net.config.ConfigMerge
import com.dsha.sidecar.net.config.native.ConfigDocuments
import com.dsha.sidecar.net.config.native.ConfigUpdate
import com.dsha.sidecar.net.config.native.NativeConfigRepository
import com.dsha.sidecar.net.http.native.script.CatalogSource
import com.dsha.sidecar.net.http.native.script.ScriptLimits
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

class NativeRuntimeIntegrationTest {
    private data class Seen(val head: Http1RequestHead, val sni: String?, val bodyBytes: Long = 0)
    private data class Reply(val head: Http1ResponseHead, val body: String)

    /** Independent UDP peer: parses questions and emits wire A responses without the DNS codec. */
    private class DnsPeer(
        private val answer: Boolean = true,
        private val addresses: List<ByteArray> = listOf(byteArrayOf(127, 0, 0, 1)),
    ) : Closeable {
        val questions = CopyOnWriteArrayList<String>()
        val types = CopyOnWriteArrayList<Int>()
        val received = CountDownLatch(1)
        private val socket = DatagramSocket(InetSocketAddress(LOOPBACK, 0))
        val port: Int get() = socket.localPort
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(packet)
                    val request = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    questions.add(questionHost(request))
                    types.add(questionType(request))
                    received.countDown()
                    if (answer) {
                        val bytes = dnsAnswer(request, addresses)
                        socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    }
                } catch (_: SocketException) { }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }.apply { isDaemon = true; name = "runtime-test-udp"; start() }
        override fun close() {
            socket.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError("DNS fixture failed", it) }
        }
    }

    private class Origin(
        tls: Boolean = false,
        private val respond: (Seen, OutputStream) -> Unit = { seen, output -> fixed(output, seen.head.target.raw) },
    ) : Closeable {
        val requests = CopyOnWriteArrayList<Seen>()
        val accepted = AtomicInteger()
        private val server = if (tls) originLeaves.get("origin.test").newServerSocket().apply {
            bind(InetSocketAddress(LOOPBACK, 0))
        } else ServerSocket(0, 8, LOOPBACK)
        val port: Int get() = server.localPort
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    accepted.incrementAndGet()
                    sockets.add(socket)
                    socket.use {
                        socket.soTimeout = 5_000
                        val sni = if (socket is SSLSocket) {
                            socket.startHandshake()
                            ((socket.session as ExtendedSSLSession).requestedServerNames.firstOrNull() as? SNIHostName)?.asciiName
                        } else null
                        val reader = Http1Reader(socket.getInputStream())
                        val request = reader.readRequest() ?: return@use
                        val body = reader.transferBody(object : OutputStream() { override fun write(b: Int) = Unit })
                        val seen = Seen(request.head, sni, body.bodyBytes)
                        requests.add(seen)
                        respond(seen, socket.getOutputStream())
                        socket.getOutputStream().flush()
                    }
                    sockets.remove(socket)
                } catch (_: java.io.IOException) {
                    // Deliberately failed trust and stop paths terminate the socket.
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }.apply { isDaemon = true; name = "runtime-test-origin"; start() }
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            worker.join(2_000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError("Origin fixture failed", it) }
        }
    }

    private fun decoded(
        dns: DnsPeer,
        rules: Map<String, Any?> = emptyMap(),
        gate: Boolean = false,
    ): Map<String, Any?> = mapOf("server" to mapOf(
        "dns" to mapOf("providers" to mapOf("fixture" to mapOf("type" to "udp", "server" to "127.0.0.1",
            "port" to dns.port, "family" to 4)), "mapping" to mapOf("*" to mapOf("dnsName" to "fixture"))),
        "setting" to mapOf("script" to mapOf("enabled" to gate)),
        "intercepts" to rules,
    ))

    private fun runtime(
        config: Map<String, Any?>,
        proxyOptions: NativeProxyOptions = NativeProxyOptions(readTimeoutMillis = 3_000),
        dnsTimeoutMillis: Int = 1_000,
        trust: SSLContext? = null,
        dialer: NativeTlsDialer = NativeTlsConnector(),
        scriptRoutes: NativeRuntimeScriptRoutes? = null,
    ): NativeRuntime = NativeRuntime.fromDecoded(config, clientCa.authority(),
        NativeRuntimeOptions(proxy = proxyOptions, dnsTimeoutMillis = dnsTimeoutMillis,
            dnsTransport = SocketDnsLimits(queryTimeoutMillis = 2_000)),
        tlsDialer = dialer, upstreamTrustContext = trust, scriptRoutes = scriptRoutes)

    private fun socket(runtime: NativeRuntime): Socket = Socket().apply {
        connect(runtime.localAddress)
        soTimeout = 5_000
    }

    private fun secure(runtime: NativeRuntime): SSLSocket {
        val raw = socket(runtime)
        send(raw, "CONNECT browser.test:443 HTTP/1.1\r\nHost: browser.test:443\r\n\r\n")
        assertEquals(200, Http1HeadCodec.readResponse(raw.getInputStream(), "CONNECT")!!.head.status)
        return (clientTrust.socketFactory.createSocket(raw, "browser.test", 443, true) as SSLSocket).apply {
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            soTimeout = 5_000
            startHandshake()
        }
    }

    private fun reply(socket: Socket, method: String = "GET"): Reply {
        val reader = Http1Reader(socket.getInputStream())
        val response = reader.readResponse(method) ?: throw AssertionError("Missing runtime response")
        val bytes = ByteArrayOutputStream()
        reader.transferBody(bytes, Http1ChunkMode.DECODE)
        return Reply(response.head, bytes.toString("UTF-8"))
    }

    @Test fun actualUdpDnsSelectsHttpOriginAndReportsRealTcpSuccess() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                runtime(decoded(dns)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/real-dns", "origin.test:${origin.port}"))
                        val response = reply(socket)
                        assertEquals(200, response.head.status)
                        assertEquals("/real-dns", response.body)
                    }
                    assertEquals(listOf("origin.test"), dns.questions)
                    assertEquals(1, origin.requests.size)
                    assertEquals(1L, runtime.routes.state().selections)
                    assertEquals(1L, runtime.routes.state().feedbackReports)
                    assertEquals(0, runtime.routes.state().pending)
                }
            }
        }
    }

    @Test fun validatedRepositorySnapshotDrivesRealProxyAndRejectsUnsupportedReload() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val repository = NativeConfigRepository(validate = { candidate ->
                    NativeRuntimeConfig.decode(candidate.configuration)
                })
                val defaults = """
                    {
                      server: {
                        dns: {
                          providers: { fixture: { type: 'udp', server: '127.0.0.1', port: ${dns.port}, family: 4 } },
                          mapping: { '*': { dnsName: 'fixture' } }
                        },
                        setting: { script: { enabled: false } },
                        intercepts: {}
                      }
                    }
                """.trimIndent()
                val seeded = repository.reload(ConfigDocuments(defaults)) as ConfigUpdate.Applied
                assertEquals(31181, NativeRuntimeConfig.decode(seeded.snapshot.configuration).listenPort)
                val disabled = repository.reload(ConfigDocuments(defaults, user = "{server:{enabled:false}}"))
                    as ConfigUpdate.Rejected
                assertSame(seeded.snapshot, disabled.previous)
                val applied = repository.reload(ConfigDocuments(defaults, user = "{}")) as ConfigUpdate.Applied
                runtime(applied.snapshot.configuration).use { runtime ->
                    runtime.start()
                    socket(runtime).use { connection ->
                        send(connection, get("/repository-first", "origin.test:${origin.port}"))
                        assertEquals("/repository-first", reply(connection).body)
                    }

                    val rejected = repository.reload(ConfigDocuments(defaults, user = """
                        { server: { intercepts: { 'origin.test': { '.*': { unknownScript: true } } } } }
                    """.trimIndent())) as ConfigUpdate.Rejected
                    assertSame(applied.snapshot, rejected.previous)
                    assertSame(applied.snapshot, repository.current())

                    socket(runtime).use { connection ->
                        send(connection, get("/repository-after-reject", "origin.test:${origin.port}"))
                        assertEquals("/repository-after-reject", reply(connection).body)
                    }
                    assertEquals(2, origin.requests.size)
                    assertEquals(NativeRuntimeState.LISTENING, runtime.state)
                }
            }
        }
    }

    @Test fun configuredProxyCapturesHeadersSniAndVerifyHostReachIndependentTlsOrigin() {
        DnsPeer().use { dns ->
            Origin(tls = true).use { origin ->
                val rule = mapOf("^/source/(\\w+)$" to mapOf(
                    "requestReplace" to mapOf("headers" to mapOf("X-Rule" to "applied", "Cookie" to "[remove]")),
                    "proxy" to "https://origin.test:${origin.port}/mirror/\${p[1]}",
                    "sni" to "routing.test", "verifyHost" to "origin.test",
                ))
                val calls = AtomicInteger()
                val base = NativeTlsConnector()
                val injected = NativeTlsDialer { raw, policy, timeout ->
                    calls.incrementAndGet()
                    assertEquals(UpstreamTrustMode.STRICT, policy.trustMode)
                    assertEquals("origin.test", policy.verifyHost)
                    assertEquals("routing.test", policy.sniHost)
                    base.connect(raw, policy, timeout)
                }
                runtime(decoded(dns, mapOf("browser.test" to rule)), trust = originTrust,
                    dialer = injected).use { runtime ->
                    runtime.start()
                    secure(runtime).use { socket ->
                        send(socket, "GET /source/captured HTTP/1.1\r\nHost: browser.test\r\nCookie: private\r\nConnection: close\r\n\r\n")
                        assertEquals("/mirror/captured", reply(socket).body)
                    }
                    assertEquals(listOf("origin.test"), dns.questions.toList())
                    assertEquals(1, calls.get())
                    val actual = origin.requests.single()
                    assertEquals(listOf("applied"), actual.head.headers.values("X-Rule"))
                    assertTrue(actual.head.headers.values("Cookie").isEmpty())
                    assertEquals(listOf("origin.test:${origin.port}"), actual.head.headers.values("Host"))
                    assertEquals("routing.test", actual.sni)
                }
            }
        }
    }

    @Test fun wrongVerifyHostRejectsDespiteTrustedChainAndRuntimeContinuesServing() {
        DnsPeer().use { dns ->
            Origin(tls = true).use { origin ->
                val rules = mapOf("browser.test" to mapOf(".*" to mapOf(
                    "proxy" to "https://origin.test:${origin.port}", "verifyHost" to "wrong.test")))
                runtime(decoded(dns, rules), trust = originTrust).use { runtime ->
                    runtime.start()
                    repeat(2) {
                        secure(runtime).use { socket ->
                            send(socket, get("/strict", "browser.test"))
                            assertEquals(502, reply(socket).head.status)
                        }
                        assertEquals(NativeRuntimeState.LISTENING, runtime.state)
                    }
                    assertTrue(origin.requests.isEmpty())
                }
            }
        }
    }

    @Test fun successAbortRedirectAndOptionsNeverResolveOrConnect() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val rules = mapOf("browser.test" to linkedMapOf(
                    "^/success$" to mapOf("success" to mapOf("text" to "synthetic", "status" to 201)),
                    "^/abort$" to mapOf("abort" to mapOf("text" to "blocked", "status" to 403)),
                    "^/redirect$" to mapOf("redirect" to "https://redirect.test/target"),
                    "^/options$" to mapOf("options" to true),
                ))
                runtime(decoded(dns, rules)).use { runtime ->
                    runtime.start()
                    for ((path, status) in listOf("/success" to 201, "/abort" to 403,
                        "/redirect" to 302, "/options" to 200)) {
                        socket(runtime).use { socket ->
                            val method = if (path == "/options") "OPTIONS" else "GET"
                            send(socket, "$method $path HTTP/1.1\r\nHost: browser.test\r\nOrigin: https://caller.test\r\n\r\n")
                            val response = reply(socket, method)
                            assertEquals(status, response.head.status)
                            if (path == "/success") assertEquals("synthetic", response.body)
                            if (path == "/abort") assertEquals("blocked", response.body)
                            if (path == "/redirect") assertEquals(listOf("https://redirect.test/target"),
                                response.head.headers.values("Location"))
                            if (path == "/options") assertFalse(response.head.headers.values("Date").isEmpty())
                        }
                    }
                    assertEquals(0L, runtime.routes.state().selections)
                    assertTrue(dns.questions.isEmpty())
                    assertEquals(0, origin.accepted.get())
                }
            }
        }
    }

    @Test fun staticHeadPreservesRepresentationLengthWithoutSendingBody() {
        DnsPeer().use { dns ->
            val rules = mapOf("browser.test" to mapOf(".*" to mapOf("success" to mapOf("text" to "hello"))))
            runtime(decoded(dns, rules)).use { runtime ->
                runtime.start()
                socket(runtime).use { socket ->
                    send(socket, "HEAD / HTTP/1.1\r\nHost: browser.test\r\n\r\n")
                    val response = reply(socket, "HEAD")
                    assertEquals(200, response.head.status)
                    assertEquals(listOf("5"), response.head.headers.values("Content-Length"))
                    assertEquals("", response.body)
                }
            }
        }
    }

    @Test fun realResponseGateKeepsHeadersUnchangedWhenDisabled() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf(
                    "options" to true, "responseReplace" to mapOf("headers" to mapOf("X-Rule" to "yes")))))
                for (gate in listOf(false, true)) runtime(decoded(dns, rules, gate)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, "GET /gate HTTP/1.1\r\nHost: origin.test:${origin.port}\r\nOrigin: https://caller.test\r\nConnection: close\r\n\r\n")
                        val response = reply(socket)
                        assertEquals(if (gate) listOf("yes") else emptyList<String>(), response.head.headers.values("X-Rule"))
                        assertEquals(if (gate) listOf("https://caller.test") else emptyList<String>(),
                            response.head.headers.values("Access-Control-Allow-Origin"))
                    }
                }
            }
        }
    }

    @Test fun corsAndResponseHeadersApplyTo65MiBWithoutEntityBuffering() {
        val size = 65L * 1024 * 1024
        DnsPeer().use { dns ->
            Origin { _, output ->
                output.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                val buffer = ByteArray(8192) { 9 }
                var left = size
                while (left > 0) {
                    val count = minOf(left, buffer.size.toLong()).toInt()
                    output.write(buffer, 0, count)
                    left -= count
                }
            }.use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("options" to true, "cacheDays" to 1,
                    "responseReplace" to mapOf("headers" to mapOf("X-Stream" to "untouched")))))
                runtime(decoded(dns, rules, gate = true)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, "GET /large HTTP/1.1\r\nHost: origin.test:${origin.port}\r\nOrigin: https://caller.test\r\nConnection: close\r\n\r\n")
                        val reader = Http1Reader(socket.getInputStream(), Http1Limits(maxBodyBytes = Long.MAX_VALUE))
                        val response = reader.readResponse("GET")!!.head
                        assertEquals(listOf("untouched"), response.headers.values("X-Stream"))
                        assertEquals(listOf("https://caller.test"), response.headers.values("Access-Control-Allow-Origin"))
                        assertEquals(listOf(size.toString()), response.headers.values("Content-Length"))
                        assertEquals(listOf("public, max-age=86401, immutable"), response.headers.values("Cache-Control"))
                        var copied = 0L
                        reader.transferBody(object : OutputStream() {
                            override fun write(value: Int) { assertEquals(9, value); copied++ }
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                assertTrue((offset until offset + length).all { bytes[it].toInt() == 9 })
                                copied += length
                            }
                        })
                        assertEquals(size, copied)
                        assertFalse(runtime.cacheState().memoryStorageEnabled)
                    }
                }
            }
        }
    }

    @Test fun configLoadRejectsUnsupportedActionsBeforeAnyTraffic() {
        DnsPeer().use { dns ->
            for (rule in listOf(
                mapOf("unknownScript" to true), mapOf("nativeMemoryCache" to true),
                mapOf("responseReplace" to mapOf("body" to "modified")),
                mapOf("responseReplace" to mapOf("headers" to mapOf("Content-Length" to "5"))),
            )) {
                val failure = assertThrows(NativeRuntimeConfigurationException::class.java) {
                    NativeRuntimeConfig.decode(decoded(dns, mapOf("browser.test" to mapOf(".*" to rule))))
                }
                assertTrue(failure.problems.isNotEmpty())
            }
            assertTrue(dns.questions.isEmpty())
        }
    }

    @Test fun globalScriptGateEnablesBoundedResponseProcessing() {
        DnsPeer().use { dns ->
            val snapshot = NativeRuntimeConfig.decode(decoded(dns, gate = true))
            assertTrue(snapshot.responseHeadersEnabled)
            assertTrue(snapshot.diagnostics.any { it.contains("bounded HTML") })
            assertTrue(snapshot.diagnostics.any { it.contains("retries") })
        }
    }

    @Test fun configSnapshotDoesNotAliasCallersAndLayersResolveBeforePlanning() {
        DnsPeer().use { dns ->
            val rules = mutableMapOf<String, Any?>("browser.test" to mapOf(".*" to mapOf("success" to mapOf("text" to "before"))))
            val base = decoded(dns, rules)
            val overrideRules = mapOf("browser.test" to mapOf(".*" to mapOf("success" to mapOf("text" to "layered"))))
            val overlay = mapOf("server" to mapOf("intercepts" to overrideRules))
            val snapshot = NativeRuntimeConfig.decodeLayers(listOf(base, overlay))
            rules.clear()
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (snapshot.decoded as MutableMap<String, Any?>).clear()
            }
            NativeRuntime(snapshot, clientCa.authority()).use { runtime ->
                runtime.start()
                socket(runtime).use { socket ->
                    send(socket, get("/", "browser.test"))
                    assertEquals("layered", reply(socket).body)
                }
            }
        }
    }

    @Test fun lifecycleHasIsolatedPortsBindFailureAndIdempotentClose() {
        DnsPeer().use { dns ->
            val snapshot = NativeRuntimeConfig.decode(decoded(dns))
            val one = NativeRuntime(snapshot, clientCa.authority())
            val two = NativeRuntime(snapshot, clientCa.authority())
            try {
                assertEquals(NativeRuntimeState.NEW, one.state)
                one.start(); two.start()
                assertNotEquals(one.localAddress!!.port, two.localAddress!!.port)
                val conflict = NativeRuntime(snapshot, clientCa.authority(),
                    NativeRuntimeOptions(proxy = NativeProxyOptions(port = one.localAddress!!.port)))
                try {
                    assertThrows(BindException::class.java) { conflict.start() }
                    assertEquals(NativeRuntimeState.FAILED, conflict.state)
                    assertNotNull(conflict.failure)
                    assertTrue(conflict.dnsState().closed)
                    assertTrue(conflict.transportState().closed)
                } finally { conflict.close() }
                one.close(); one.close()
                assertEquals(NativeRuntimeState.STOPPED, one.state)
                assertEquals(NativeRuntimeState.LISTENING, two.state)
                assertThrows(IllegalStateException::class.java) { one.start() }
            } finally { one.close(); two.close() }
        }
    }

    @Test fun dnsTimeoutIsBounded504AndStopCancelsRealUdpQuery() {
        DnsPeer(answer = false).use { dns ->
            runtime(decoded(dns), dnsTimeoutMillis = 150).use { runtime ->
                runtime.start()
                socket(runtime).use { socket ->
                    send(socket, get("/", "timeout.test"))
                    assertEquals(504, reply(socket).head.status)
                    assertEquals(0, runtime.routes.state().pending)
                }
            }
        }
        DnsPeer(answer = false).use { dns ->
            val runtime = runtime(decoded(dns), dnsTimeoutMillis = 10_000)
            runtime.start()
            try {
                socket(runtime).use { socket ->
                    send(socket, get("/", "blocked-dns.test"))
                    assertTrue(dns.received.await(2, TimeUnit.SECONDS))
                    val start = System.nanoTime()
                    runtime.close()
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
                    assertEquals(0, runtime.activeSocketCount)
                    assertEquals(0, runtime.transportState().resources)
                    assertEquals(0, runtime.dnsState().flights)
                    assertTrue(runtime.transportState().closed)
                }
            } finally { runtime.close() }
        }
    }

    @Test fun missingProviderOrNegativeDnsNeverFallsBackToJvmHostnameResolution() {
        val config = mapOf("server" to mapOf("intercepts" to emptyMap<String, Any?>()))
        runtime(config).use { runtime ->
            runtime.start()
            socket(runtime).use { socket ->
                send(socket, get("/", "no-provider.invalid"))
                assertEquals(502, reply(socket).head.status)
            }
            assertEquals(0L, runtime.routes.state().selections)
            assertEquals(0L, runtime.routes.state().feedbackReports)
        }
        DnsPeer(addresses = emptyList()).use { dns ->
            runtime(decoded(dns)).use { runtime ->
                runtime.start()
                socket(runtime).use { socket ->
                    send(socket, get("/", "nodata.test"))
                    assertEquals(502, reply(socket).head.status)
                }
                assertEquals(listOf("nodata.test"), dns.questions)
                assertEquals(0L, runtime.routes.state().selections)
            }
        }
    }

    @Test fun realConnectFailureRetriesCurrentRequestAndKeepsHealthyCandidate() {
        DnsPeer(addresses = listOf(byteArrayOf(127, 0, 0, 2), byteArrayOf(127, 0, 0, 1))).use { dns ->
            Origin().use { origin ->
                runtime(decoded(dns)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/", "candidate.test:${origin.port}"))
                        assertEquals(200, reply(socket).head.status)
                    }
                    assertEquals(1, origin.accepted.get())
                    assertEquals(2L, runtime.routes.state().selections)
                    assertEquals(2L, runtime.routes.state().feedbackReports)
                    socket(runtime).use { socket ->
                        send(socket, get("/next", "candidate.test:${origin.port}"))
                        assertEquals("/next", reply(socket).body)
                    }
                    assertEquals(2, origin.accepted.get())
                    assertEquals(3L, runtime.routes.state().selections)
                    assertEquals(3L, runtime.routes.state().feedbackReports)
                }
            }
        }
    }

    @Test fun explicitFeedbackOnlyAfterRealSocketOutcomeAdvancesNextRequestCandidate() {
        DnsPeer(addresses = listOf(byteArrayOf(127, 0, 0, 2), byteArrayOf(127, 0, 0, 1))).use { dns ->
            Origin().use { origin ->
                runtime(decoded(dns)).use { runtime ->
                    runtime.start()
                    val attempt = runtime.routes.select("candidate.test", origin.port, null)
                    assertEquals("127.0.0.2", attempt.route.destination.address.hostAddress)
                    Socket().use { actual ->
                        assertThrows(java.io.IOException::class.java) {
                            actual.connect(attempt.route.destination, 300)
                        }
                    }
                    assertTrue(runtime.routes.feedback(attempt, NativeRuntimeSocketOutcome.CONNECT_FAILED))
                    assertFalse(runtime.routes.feedback(attempt, NativeRuntimeSocketOutcome.CONNECT_FAILED))
                    socket(runtime).use { socket ->
                        send(socket, get("/next-candidate", "candidate.test:${origin.port}"))
                        assertEquals("/next-candidate", reply(socket).body)
                    }
                    assertEquals(1, origin.requests.size)
                    assertEquals(2L, runtime.routes.state().feedbackReports)
                }
            }
        }
    }

    @Test fun requiredEchAutoAssemblyStillRejectsExplicitOrdinaryConnector() {
        val config = NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf(
            "ech" to mapOf("enabled" to true, "domains" to listOf("browser.test"))))))
        assertTrue(config.requiresEchAdapter)
        NativeRuntime(config, clientCa.authority()).use { assertEquals(NativeRuntimeState.NEW, it.state) }
        assertThrows(NativeRuntimeConfigurationException::class.java) {
            NativeRuntime(config, clientCa.authority(), tlsDialer = NativeTlsConnector(), echRequirement = NativeRuntimeEchRequirement { })
        }
    }

    @Test fun explicitDotTrustContextIsIndependentOfOriginTrustAndGlobals() {
        val tlsServer = dnsLeaves.get("127.0.0.1").newServerSocket().apply { bind(InetSocketAddress(LOOPBACK, 0)) }
        val closed = CountDownLatch(1)
        val peer = Thread {
            tlsServer.accept().use { socket ->
                socket.soTimeout = 3_000
                val input = DataInputStream(socket.getInputStream())
                val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                val response = dnsAnswer(query, listOf(byteArrayOf(127, 0, 0, 1)))
                DataOutputStream(socket.getOutputStream()).apply { writeShort(response.size); write(response); flush() }
            }
            closed.countDown()
        }.apply { isDaemon = true; start() }
        try {
            Origin().use { origin ->
                val dnsConfig = mapOf(
                    "providers" to mapOf("dot" to mapOf("type" to "tls", "server" to "127.0.0.1",
                        "port" to tlsServer.localPort, "family" to 4, "sni" to "dns.test")),
                    "mapping" to mapOf("*" to mapOf("dnsName" to "dot")))
                val config = mapOf("server" to mapOf("dns" to dnsConfig))
                NativeRuntime(NativeRuntimeConfig.decode(config), clientCa.authority(),
                    dnsTrustContext = dnsTrust, upstreamTrustContext = originTrust).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/dot-trust", "origin.test:${origin.port}"))
                        assertEquals("/dot-trust", reply(socket).body)
                    }
                    assertTrue(closed.await(2, TimeUnit.SECONDS))
                }
            }
        } finally { tlsServer.close(); peer.join(3_000) }
    }

    @Test fun injectedClockControlsOptionsDateButDoesNotReplaceTheAuthorityClock() {
        DnsPeer().use { dns ->
            val rules = mapOf("browser.test" to mapOf(".*" to mapOf("options" to true)))
            val now = Instant.now().plusSeconds(3600)
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules)), clientCa.authority(), clock = clock).use { runtime ->
                runtime.start()
                socket(runtime).use { socket ->
                    send(socket, "OPTIONS / HTTP/1.1\r\nHost: browser.test\r\nOrigin: https://caller.test\r\n\r\n")
                    val response = reply(socket, "OPTIONS")
                    val value = response.head.headers.values("Date").single()
                    assertEquals(now.epochSecond, java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond)
                }
            }
        }
    }

    @Test fun decodedPortIsAppliedAndConflictingExplicitPortsAreRejected() {
        ServerSocket(0, 1, LOOPBACK).use { reserved ->
            val snapshot = NativeRuntimeConfig.decode(mapOf("server" to mapOf("port" to reserved.localPort)))
            NativeRuntime(snapshot, clientCa.authority()).use { runtime ->
                assertEquals(reserved.localPort, runtime.proxyOptions.port)
                assertThrows(BindException::class.java) { runtime.start() }
                assertEquals(NativeRuntimeState.FAILED, runtime.state)
            }
            val other = if (reserved.localPort == 65535) 65534 else reserved.localPort + 1
            assertThrows(IllegalArgumentException::class.java) {
                NativeRuntime(snapshot, clientCa.authority(),
                    NativeRuntimeOptions(proxy = NativeProxyOptions(port = other)))
            }
        }
    }

    @Test fun stopClosesAnActiveBlockedOriginWithoutNativeRuntimeThreadLeaks() {
        val before = nativeThreads()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        DnsPeer().use { dns ->
            Origin { _, _ ->
                entered.countDown()
                unblock.await(3, TimeUnit.SECONDS)
            }.use { origin ->
                val runtime = runtime(decoded(dns))
                try {
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/", "blocked-origin.test:${origin.port}"))
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        runtime.close()
                        assertEquals(0, runtime.activeSocketCount)
                        assertEquals(0, runtime.routes.state().pending)
                        assertEquals(-1, socket.getInputStream().read())
                    }
                } finally { runtime.close(); unblock.countDown() }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while ((nativeThreads() - before).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(emptySet<Long>(), nativeThreads() - before)
    }

    @Test fun separateOriginAndProviderTrustDoNotChangeGlobalSslDefaultsOrProviders() {
        val globalContext = SSLContext.getDefault()
        val globalVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        val providers = java.security.Security.getProviders().map { it.name }
        DnsPeer().use { dns ->
            Origin(tls = true).use { origin ->
                val rules = mapOf("browser.test" to mapOf(".*" to mapOf("proxy" to
                    "origin.test:${origin.port}")))
                NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules)), clientCa.authority(),
                    upstreamTrustContext = originTrust, dnsTrustContext = dnsTrust).use { runtime ->
                    runtime.start()
                    secure(runtime).use { socket ->
                        send(socket, get("/isolated", "browser.test"))
                        assertEquals("/isolated", reply(socket).body)
                    }
                }
            }
        }
        assertSame(globalContext, SSLContext.getDefault())
        assertSame(globalVerifier, javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier())
        assertEquals(providers, java.security.Security.getProviders().map { it.name })
    }

    @Test fun defaultBlankPrefixNat64StaysInactiveAndOrdinaryUdpHttpStillWorks() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val config = ConfigMerge.merge(decoded(dns), mapOf("server" to mapOf("dns" to mapOf(
                    "nat64" to mapOf("enabled" to true, "prefix" to "", "domains" to listOf("*chatgpt.com"))))))
                val snapshot = NativeRuntimeConfig.decode(config)
                assertEquals(false, NativeRuntimeConfig.objectAt(snapshot.dns, "nat64")["enabled"])
                assertNull(snapshot.catalog.providerKey("Nat64"))
                assertFalse(snapshot.catalog.descriptors.any { it.transport == "nat64" })
                assertFalse(snapshot.requiresEchAdapter)
                assertTrue(snapshot.diagnostics.any { it.contains("nat64") && it.contains("blank") })
                val rawServer = NativeRuntimeConfig.objectAt(snapshot.decoded, "server")
                assertEquals(true, NativeRuntimeConfig.objectAt(
                    NativeRuntimeConfig.objectAt(rawServer, "dns"), "nat64")["enabled"])
                NativeRuntime(snapshot, clientCa.authority()).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/nat64-inactive", "origin.test:${origin.port}"))
                        assertEquals("/nat64-inactive", reply(socket).body)
                    }
                    assertEquals(listOf("origin.test"), dns.questions)
                    assertEquals(listOf(1), dns.types)
                }
            }
        }
    }

    @Test fun nat64OptionsGateRequiresStrictEnabledNonemptyArrayAndNonblankPrefix() {
        val inactive = listOf(
            mapOf("enabled" to true, "prefix" to "", "domains" to listOf("*")),
            mapOf("enabled" to true, "prefix" to "\t \ufeff", "domains" to listOf("*")),
            mapOf("enabled" to true, "domains" to listOf("*")),
            mapOf("enabled" to true, "prefix" to "64:ff9b::", "domains" to emptyList<String>()),
            mapOf("enabled" to true, "prefix" to "64:ff9b::"),
            mapOf("enabled" to true, "prefix" to "64:ff9b::", "domains" to mapOf("*" to true)),
            mapOf("enabled" to false, "prefix" to "64:ff9b::", "domains" to listOf("*")),
            mapOf("enabled" to "true", "prefix" to "64:ff9b::", "domains" to listOf("*")),
            mapOf("enabled" to false, "prefix" to 42, "domains" to listOf("*")),
        )
        for (nat64 in inactive) {
            val snapshot = NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf("nat64" to nat64))))
            assertEquals(nat64.toString(), false, NativeRuntimeConfig.objectAt(snapshot.dns, "nat64")["enabled"])
            assertNull(snapshot.catalog.providerKey("Nat64"))
            assertFalse(snapshot.requiresEchAdapter)
            assertFalse(snapshot.planner.plan("origin.test", "/").echRequired)
        }
    }

    @Test fun configuredNat64UsesNativeTransportAndRejectsMalformedPrefixes() {
        val snapshot = NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf("nat64" to mapOf(
            "enabled" to true, "prefix" to "64:ff9b::", "domains" to listOf("*"))))))
        assertNotNull(snapshot.catalog.providerKey("Nat64"))
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf("nat64" to mapOf(
                "enabled" to true, "prefix" to "not-an-ipv6-prefix", "domains" to listOf("*"))))))
        }
    }

    @Test fun legacyStringDnsMappingsConsumeParseIntFamilyOverridesWithIpv4Default() {
        val cases = listOf<Pair<Any?, Int>>(
            null to 4, 6 to 6, 6.9 to 6, 4 to 4, 4.9 to 4, true to 4,
            "6" to 6, "06" to 6, " +6suffix " to 6, "\ufeff6.9" to 6,
            "0x6tail" to 6, "+0X06" to 6, "-6" to 4, "-0x6" to 4,
            "0b110" to 4, "ipv6" to 4, "" to 4, "6e0" to 6,
            0.0000006 to 6, 0.000006 to 4,
        )
        for ((family, expected) in cases) {
            val snapshot = NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf(
                "mapping" to mapOf("origin.test" to "fixture"), "familyMapping" to mapOf("origin.test" to family)))))
            val mapping = NativeRuntimeConfig.objectAt(snapshot.dns, "mapping")
            assertEquals("family=$family", mapOf("dnsName" to "fixture", "family" to expected), mapping["origin.test"])
        }
    }

    @Test fun objectDnsMappingsKeepOwnFamilyAndLegacyFamilyKeysMatchExactly() {
        val snapshot = NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf(
            "mapping" to linkedMapOf(
                "object.test" to mapOf("dnsName" to "fixture", "family" to 4),
                "provider-default.test" to mapOf("dnsName" to "fixture"),
                "*.test" to "fixture",
                "deleted.test" to null,
            ),
            "familyMapping" to mapOf("object.test" to 6, "provider-default.test" to 6, "other.test" to 6)))))
        val mapping = NativeRuntimeConfig.objectAt(snapshot.dns, "mapping")
        assertEquals(mapOf("dnsName" to "fixture", "family" to 4), mapping["object.test"])
        assertEquals(mapOf("dnsName" to "fixture"), mapping["provider-default.test"])
        assertEquals(mapOf("dnsName" to "fixture", "family" to 4), mapping["*.test"])
        assertFalse(mapping.containsKey("deleted.test"))
    }

    @Test fun familyMappingOverridesAnyFamilyProviderWithActualIpv4UdpLookup() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val config = ConfigMerge.merge(decoded(dns), mapOf("server" to mapOf("dns" to mapOf(
                    "providers" to mapOf("fixture" to mapOf("family" to 0)),
                    "mapping" to mapOf("*" to "fixture"), "familyMapping" to mapOf("*" to "4")))))
                runtime(config).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/family-v4", "origin.test:${origin.port}"))
                        assertEquals("/family-v4", reply(socket).body)
                    }
                    assertEquals(listOf(1), dns.types)
                }
            }
        }
    }

    @Test fun familyMappingIpv6ActuallyQueriesAaaaWithoutSilentlyFallingBackToA() {
        DnsPeer().use { dns ->
            val config = ConfigMerge.merge(decoded(dns), mapOf("server" to mapOf("dns" to mapOf(
                "mapping" to mapOf("*" to "fixture"), "familyMapping" to mapOf("*" to "6suffix")))))
            runtime(config).use { runtime ->
                runtime.start()
                socket(runtime).use { socket ->
                    send(socket, get("/", "ipv6-only.test"))
                    assertEquals(502, reply(socket).head.status) // Fixture intentionally has no AAAA record.
                }
                assertEquals(listOf(28), dns.types)
            }
        }
    }

    @Test fun activeUnboundFeaturesAreCountedAsBlockingConfigIssuesBeforeTraffic() {
        val failure = assertThrows(NativeRuntimeConfigurationException::class.java) {
            NativeRuntimeConfig.decode(mapOf("server" to mapOf(
                "unboundWhitelist" to listOf("bypass.test"),
                "newFeature" to mapOf("enabled" to true),
                "setting" to mapOf("newTimeoutPolicy" to mapOf("*" to 1000)),
                "dns" to mapOf("newSpeedTest" to mapOf("enabled" to true)),
            )))
        }
        assertEquals(4, failure.problems.size)
        assertTrue(failure.problems.any { it.contains("server.unboundWhitelist") })
        assertTrue(failure.problems.any { it.contains("server.newFeature") })
        assertTrue(failure.problems.any { it.contains("setting.newTimeoutPolicy") })
        assertTrue(failure.problems.any { it.contains("dns.newSpeedTest") })
    }

    @Test fun disabledUnboundFeaturesDoNotRequireAnImplementation() {
        val snapshot = NativeRuntimeConfig.decode(mapOf("server" to mapOf(
            "whiteList" to emptyList<String>(),
            "newFeature" to mapOf("enabled" to false, "unsupportedData" to "unused"),
            "plugin" to mapOf("overwall" to mapOf("enabled" to false)),
            "setting" to mapOf("timeoutMapping" to emptyMap<String, Any?>(),
                "script" to mapOf("enabled" to false, "defaultDir" to "unused")),
            "dns" to mapOf("familyMapping" to emptyMap<String, Any?>(),
                "speedTest" to mapOf("enabled" to false)),
        )))
        assertFalse(snapshot.responseHeadersEnabled)
    }

    @Test fun invalidEnabledMappingDataIsRejectedRatherThanDroppedOrWarnedOnly() {
        val failure = assertThrows(NativeRuntimeConfigurationException::class.java) {
            NativeRuntimeConfig.decode(mapOf("server" to mapOf("dns" to mapOf(
                "mapping" to mapOf(
                    "scalar.test" to "fixture",
                    "missing-name.test" to mapOf("family" to 4),
                    "unknown-field.test" to mapOf("dnsName" to "fixture", "newOption" to true),
                ),
                "familyMapping" to mapOf("scalar.test" to listOf(6)),
            ))))
        }
        assertEquals(3, failure.problems.size)
        assertTrue(failure.problems.any { it.contains("familyMapping.scalar.test") })
        assertTrue(failure.problems.any { it.contains("mapping.missing-name.test") })
        assertTrue(failure.problems.any { it.contains("mapping.unknown-field.test.newOption") })
    }

    @Test fun cacheDaysFetchThenTimestampHitIs304WhileUnconditionalGetStillContactsOrigin() {
        val clock = RuntimeClock()
        val calls = AtomicInteger()
        DnsPeer().use { dns ->
            Origin { _, output -> fixed(output, "origin-${calls.incrementAndGet()}") }.use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("cacheDays" to 1)))
                NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules, gate = true)),
                    clientCa.authority(), clock = clock).use { runtime ->
                    runtime.start()
                    val modified = socket(runtime).use { socket ->
                        send(socket, get("/cached", "origin.test:${origin.port}"))
                        val response = reply(socket)
                        assertEquals("origin-1", response.body)
                        assertEquals(listOf("public, max-age=86401, immutable"), response.head.headers.values("Cache-Control"))
                        response.head.headers.values("Last-Modified").single()
                    }
                    socket(runtime).use { socket ->
                        send(socket, "GET /cached HTTP/1.1\r\nHost: origin.test:${origin.port}\r\n" +
                            "If-Modified-Since: $modified\r\nConnection: close\r\n\r\n")
                        val response = reply(socket)
                        assertEquals(304, response.head.status)
                        assertEquals("", response.body)
                        assertEquals(listOf("cache: 86400"), response.head.headers.values("DS-Interceptor"))
                    }
                    assertEquals(1, calls.get())
                    socket(runtime).use { socket ->
                        send(socket, get("/cached", "origin.test:${origin.port}"))
                        assertEquals("origin-2", reply(socket).body)
                    }
                    assertEquals(2, origin.requests.size)
                    assertEquals(1L, runtime.cacheState().conditionalHits)
                    assertFalse(runtime.cacheState().memoryStorageEnabled)
                }
            }
        }
    }

    @Test fun verifiedMitmProxyCacheHitSkipsSecondUpstreamTlsAttemptAndKeepsRouteIdentity() {
        val dialed = AtomicInteger()
        val delegate = NativeTlsConnector()
        val dialer = NativeTlsDialer { raw, policy, timeout ->
            dialed.incrementAndGet()
            assertEquals("origin.test", policy.verifyHost)
            assertEquals("routing.test", policy.sniHost)
            delegate.connect(raw, policy, timeout)
        }
        DnsPeer().use { dns ->
            Origin(tls = true).use { origin ->
                val rules = mapOf("browser.test" to mapOf(".*" to mapOf(
                    "cacheDays" to 1, "proxy" to "origin.test:${origin.port}",
                    "sni" to "routing.test", "verifyHost" to "origin.test")))
                runtime(decoded(dns, rules, gate = true), trust = originTrust, dialer = dialer).use { runtime ->
                    runtime.start()
                    val modified = secure(runtime).use { socket ->
                        send(socket, get("/tls-cache", "browser.test"))
                        val response = reply(socket)
                        assertEquals("/tls-cache", response.body)
                        response.head.headers.values("Last-Modified").single()
                    }
                    secure(runtime).use { socket ->
                        send(socket, "GET /tls-cache HTTP/1.1\r\nHost: browser.test\r\n" +
                            "If-Modified-Since: $modified\r\nConnection: close\r\n\r\n")
                        assertEquals(304, reply(socket).head.status)
                    }
                    assertEquals(1, dialed.get())
                    assertEquals(1, origin.requests.size)
                    assertEquals(listOf("origin.test:${origin.port}"), origin.requests.single().head.headers.values("Host"))
                    assertEquals("routing.test", origin.requests.single().sni)
                    assertEquals(1L, runtime.cacheState().conditionalHits)
                }
            }
        }
    }

    @Test fun timestampCacheKeepsInclusiveBoundaryFutureTimesAndRequestDirectiveBypasses() {
        val clock = RuntimeClock()
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("cacheSeconds" to 60)))
                NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules, gate = true)), clientCa.authority(),
                    clock = clock).use { runtime ->
                    runtime.start()
                    val cases = listOf(
                        "If-Modified-Since: ${clock.millisNow - 60_000}\r\n" to 304,
                        "If-Modified-Since: ${clock.millisNow - 60_001}\r\n" to 200,
                        "If-Modified-Since: ${clock.millisNow + 5_000}\r\n" to 304,
                        "If-Modified-Since: invalid-date\r\n" to 200,
                        "If-Modified-Since: ${clock.millisNow}\r\nCache-Control: no-store\r\n" to 200,
                        "If-Modified-Since: ${clock.millisNow}\r\nPragma: no-cache\r\n" to 200,
                    )
                    for ((headers, status) in cases) socket(runtime).use { socket ->
                        send(socket, "GET /boundary HTTP/1.1\r\nHost: origin.test:${origin.port}\r\n$headers" +
                            "Connection: close\r\n\r\n")
                        assertEquals(status, reply(socket).head.status)
                    }
                    assertEquals(4, origin.requests.size)
                    assertEquals(2L, runtime.cacheState().conditionalHits)
                }
            }
        }
    }

    @Test fun cacheLookupOrderingKeepsSuccessAbortAheadOf304And304AheadOfRedirect() {
        val clock = RuntimeClock()
        DnsPeer().use { dns ->
            val rules = mapOf("browser.test" to linkedMapOf(
                "^/success$" to mapOf("cacheDays" to 1, "success" to mapOf("text" to "success-first")),
                "^/abort$" to mapOf("cacheDays" to 1, "abort" to mapOf("text" to "abort-first")),
                "^/redirect$" to mapOf("cacheDays" to 1, "redirect" to "https://redirect.test/target"),
            ))
            NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules)), clientCa.authority(), clock = clock).use { runtime ->
                runtime.start()
                for ((path, status) in listOf("/success" to 200, "/abort" to 403, "/redirect" to 304)) {
                    socket(runtime).use { socket ->
                        send(socket, "GET $path HTTP/1.1\r\nHost: browser.test\r\n" +
                            "If-Modified-Since: ${clock.millisNow}\r\nConnection: close\r\n\r\n")
                        val response = reply(socket)
                        assertEquals(status, response.head.status)
                        if (path == "/redirect") assertTrue(response.head.headers.values("Location").isEmpty())
                    }
                }
                socket(runtime).use { socket ->
                    send(socket, "GET /redirect HTTP/1.1\r\nHost: browser.test\r\n" +
                        "If-Modified-Since: ${clock.millisNow}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                    val response = reply(socket)
                    assertEquals(302, response.head.status)
                    assertEquals(listOf("https://redirect.test/target"), response.head.headers.values("Location"))
                }
                assertTrue(dns.questions.isEmpty())
                assertEquals(0L, runtime.routes.state().selections)
                assertEquals(2L, runtime.cacheState().conditionalChecks)
                assertEquals(1L, runtime.cacheState().conditionalHits)
            }
        }
    }

    @Test fun responseCacheGateDoesNotDisableTheSeparateRequest304Operation() {
        val clock = RuntimeClock()
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("cacheDays" to 1)))
                NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules, gate = false)), clientCa.authority(),
                    clock = clock).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/gate", "origin.test:${origin.port}"))
                        val response = reply(socket)
                        assertEquals(200, response.head.status)
                        assertTrue(response.head.headers.values("Last-Modified").isEmpty())
                        assertTrue(response.head.headers.values("DS-Cache-Response-Interceptor").isEmpty())
                    }
                    socket(runtime).use { socket ->
                        send(socket, "GET /gate HTTP/1.1\r\nHost: origin.test:${origin.port}\r\n" +
                            "If-Modified-Since: ${clock.millisNow}\r\nConnection: close\r\n\r\n")
                        assertEquals(304, reply(socket).head.status)
                    }
                    assertEquals(1, origin.requests.size)
                    assertEquals(0L, runtime.cacheState().responseHeaderChanges)
                }
            }
        }
    }

    @Test fun headCacheRuleNeverClaimsA304OrBuffersALargeRepresentation() {
        val size = 65L * 1024 * 1024
        val clock = RuntimeClock()
        DnsPeer().use { dns ->
            Origin { _, output ->
                output.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\n\r\n".toByteArray())
            }.use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("cacheDays" to 1)))
                NativeRuntime(NativeRuntimeConfig.decode(decoded(dns, rules, gate = true)), clientCa.authority(),
                    clock = clock).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, "HEAD /large HTTP/1.1\r\nHost: origin.test:${origin.port}\r\n" +
                            "If-Modified-Since: ${clock.millisNow}\r\nConnection: close\r\n\r\n")
                        val response = reply(socket, "HEAD")
                        assertEquals(200, response.head.status)
                        assertEquals(listOf(size.toString()), response.head.headers.values("Content-Length"))
                        assertEquals("", response.body)
                        assertTrue(response.head.headers.values("DS-Cache-Response-Interceptor").isEmpty())
                    }
                    assertEquals(1, origin.requests.size)
                    assertEquals(0L, runtime.cacheState().conditionalHits)
                    assertEquals(0L, runtime.cacheState().responseHeaderChanges)
                    assertFalse(runtime.cacheState().memoryStorageEnabled)
                }
            }
        }
    }

    @Test fun responseReplaceRunsAfterCacheHeadersAndNoStoreIsNotAnEntityStoragePermission() {
        DnsPeer().use { dns ->
            Origin { _, output ->
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nCache-Control: no-store\r\n\r\nbody".toByteArray())
            }.use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf(
                    "options" to true, "cacheSeconds" to 60,
                    "responseReplace" to mapOf("headers" to mapOf("Cache-Control" to "private, max-age=17")))))
                runtime(decoded(dns, rules, gate = true)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, "GET /ordered HTTP/1.1\r\nHost: origin.test:${origin.port}\r\n" +
                            "Origin: https://caller.test\r\nConnection: close\r\n\r\n")
                        val response = reply(socket)
                        assertEquals("body", response.body)
                        assertEquals(listOf("private, max-age=17"), response.head.headers.values("Cache-Control"))
                        assertEquals(listOf("60"), response.head.headers.values("DS-Cache-Response-Interceptor"))
                        assertEquals(listOf("https://caller.test"), response.head.headers.values("Access-Control-Allow-Origin"))
                        assertFalse(runtime.cacheState().memoryStorageEnabled)
                    }
                }
            }
        }
    }

    @Test fun unknownEntityCacheAndLegacyCacheCoercionRemainRejectedBeforeTraffic() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                for ((rule, issue) in listOf(
                    mapOf("cacheDays" to 1, "nativeMemoryCache" to true) to "nativeMemoryCache",
                    mapOf("cacheDays" to 1, "cacheImmutable" to "false") to "cacheImmutable",
                )) {
                    val failure = assertThrows(NativeRuntimeConfigurationException::class.java) {
                        runtime(decoded(dns, mapOf("origin.test" to mapOf(".*" to rule)), gate = true))
                    }
                    assertTrue(failure.problems.any { it.contains(issue) })
                }
                assertTrue(dns.questions.isEmpty())
                assertEquals(0, origin.accepted.get())
            }
        }
    }

    @Test fun actualStatusFailuresAreOneAttemptAndPostSideEffectsAreNotDuplicated() {
        val effects = AtomicInteger()
        DnsPeer().use { dns ->
            Origin { seen, output ->
                if (seen.head.method == "POST") effects.incrementAndGet()
                output.write("HTTP/1.1 500 Origin Failure\r\nContent-Length: 4\r\n\r\n".toByteArray())
                if (seen.head.method != "HEAD") output.write("once".toByteArray())
            }.use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("cacheDays" to 1)))
                runtime(decoded(dns, rules, gate = true)).use { runtime ->
                    runtime.start()
                    for (method in listOf("GET", "HEAD", "POST")) socket(runtime).use { socket ->
                        send(socket, "$method /failure HTTP/1.1\r\nHost: origin.test:${origin.port}\r\n" +
                            (if (method == "POST") "Content-Length: 8\r\n" else "") +
                            "Connection: close\r\n\r\n" + if (method == "POST") "purchase" else "")
                        val response = reply(socket, method)
                        assertEquals(500, response.head.status)
                        assertEquals(if (method == "HEAD") "" else "once", response.body)
                    }
                    assertEquals(3, origin.requests.size)
                    assertEquals(1, effects.get())
                    assertEquals(8L, origin.requests.single { it.head.method == "POST" }.bodyBytes)
                    assertEquals(0L, runtime.cacheState().responseHeaderChanges)
                }
            }
        }
    }

    @Test fun truncatedResponseAfterCommitNeverReplaysOrAppendsAnotherResponse() {
        DnsPeer().use { dns ->
            Origin { _, output ->
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial".toByteArray())
            }.use { origin ->
                val rules = mapOf("origin.test" to mapOf(".*" to mapOf("cacheDays" to 1)))
                runtime(decoded(dns, rules, gate = true)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/partial", "origin.test:${origin.port}"))
                        val wire = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                        assertTrue(wire.startsWith("HTTP/1.1 200 "))
                        assertTrue(wire.endsWith("partial"))
                        assertEquals(1, Regex("HTTP/1.1").findAll(wire).count())
                    }
                    assertEquals(1, origin.requests.size)
                    assertFalse(runtime.cacheState().memoryStorageEnabled)
                }
            }
        }
    }

    @Test fun authorizedScriptRoutesServeRealProxyRequestsWithoutOriginTraffic() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val routes = NativeRuntimeScriptRoutes(
                    mapOf("alpha" to CatalogSource("window.localScript = 1;")),
                    setOf("http://origin.test:${origin.port}"),
                    mapOf("/____ds_script_proxy____/approved" to "alpha"),
                )
                runtime(decoded(dns, gate = true), scriptRoutes = routes).use { runtime ->
                    runtime.start()
                    val host = "origin.test:${origin.port}"
                    fun request(method: String, path: String): Reply = socket(runtime).use { socket ->
                        send(socket, "$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                        reply(socket, method)
                    }
                    val get = request("GET", "/____ds_script____/alpha")
                    assertEquals(200, get.head.status)
                    assertEquals("window.localScript = 1;", get.body)
                    assertEquals(listOf(get.body.toByteArray().size.toString()), get.head.headers.values("Content-Length"))
                    assertEquals(listOf("nosniff"), get.head.headers.values("X-Content-Type-Options"))
                    val head = request("HEAD", "/____ds_script____/alpha")
                    assertEquals(200, head.head.status)
                    assertEquals("", head.body)
                    assertEquals(get.head.headers.values("Content-Length"), head.head.headers.values("Content-Length"))
                    assertEquals("window.localScript = 1;", request("GET", "/____ds_script_proxy____/approved").body)
                    assertEquals(404, request("GET", "/____ds_script____/missing").head.status)
                    assertEquals(405, request("POST", "/____ds_script____/alpha").head.status)
                    assertEquals(502, request("GET", "/____ds_script____/alpha?x=1").head.status)
                    assertTrue(origin.requests.isEmpty())
                    assertTrue(dns.questions.isEmpty())
                    assertEquals("/normal", request("GET", "/normal").body)
                    assertEquals(1, origin.requests.size)
                }
            }
        }
    }

    @Test fun disabledAndUnauthorizedScriptRoutesRemainOrdinaryOriginRequests() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                val path = "/____ds_script____/alpha"
                for ((enabled, host, allowedOrigin) in listOf(
                    Triple(false, "origin.test", "http://origin.test:${origin.port}"),
                    Triple(true, "other.test", "http://origin.test:${origin.port}"),
                    Triple(true, "origin.test", "http://origin.test"),
                    Triple(true, "origin.test", "https://origin.test:${origin.port}"),
                )) {
                    val routes = NativeRuntimeScriptRoutes(
                        mapOf("alpha" to CatalogSource("not served")), setOf(allowedOrigin),
                    )
                    runtime(decoded(dns, gate = enabled), scriptRoutes = routes).use { runtime ->
                        runtime.start()
                        socket(runtime).use { socket ->
                            send(socket, get(path, "$host:${origin.port}"))
                            assertEquals(path, reply(socket).body)
                        }
                    }
                }
                assertEquals(4, origin.requests.size)
            }
        }
    }

    @Test fun authorizedScriptRoutesWorkInsideVerifiedConnectMitm() {
        DnsPeer().use { dns ->
            val routes = NativeRuntimeScriptRoutes(
                mapOf("tampermonkey" to CatalogSource("window.bootstrap = true;")),
                setOf("https://browser.test"),
            )
            runtime(decoded(dns, gate = true), scriptRoutes = routes).use { runtime ->
                runtime.start()
                secure(runtime).use { socket ->
                    send(socket, get("/____ds_script____/global", "browser.test"))
                    val response = reply(socket)
                    assertEquals(200, response.head.status)
                    assertEquals("window.bootstrap = true;", response.body)
                    assertEquals(listOf("ds_script"), response.head.headers.values("DS-Middleware"))
                }
                assertEquals(0L, runtime.routes.state().selections)
                assertTrue(dns.questions.isEmpty())
            }
        }
    }

    @Test fun absentCatalogNeverInterceptsAndInjectedCatalogDoesNotEnableBodyActions() {
        DnsPeer().use { dns ->
            Origin().use { origin ->
                runtime(decoded(dns, gate = true)).use { runtime ->
                    runtime.start()
                    socket(runtime).use { socket ->
                        send(socket, get("/____ds_script____/alpha", "origin.test:${origin.port}"))
                        assertEquals("/____ds_script____/alpha", reply(socket).body)
                    }
                }
                assertEquals(1, origin.requests.size)
            }
            val routes = NativeRuntimeScriptRoutes(
                mapOf("alpha" to CatalogSource("window.localScript = 1;")),
                setOf("https://browser.test"),
            )
            val queriesBefore = dns.questions.size
            for (rule in listOf(
                mapOf("responseReplace" to mapOf("body" to "changed")),
            )) {
                assertThrows(NativeRuntimeConfigurationException::class.java) {
                    runtime(decoded(dns, mapOf("browser.test" to mapOf(".*" to rule)), gate = true),
                        scriptRoutes = routes)
                }
            }
            assertEquals(queriesBefore, dns.questions.size)
        }
    }

    @Test fun scriptRouteByteCapAndHeadMetadataSurviveRealFraming() {
        val content = "é".repeat(32_768)
        val sources = mutableMapOf("limit" to CatalogSource(content))
        val routes = NativeRuntimeScriptRoutes(sources, setOf("http://browser.test"))
        sources["limit"] = CatalogSource("mutated")
        runtime(emptyMap<String, Any?>() + ("server" to mapOf(
            "setting" to mapOf("script" to mapOf("enabled" to true)),
        )), scriptRoutes = routes).use { runtime ->
            runtime.start()
            for (method in listOf("GET", "HEAD")) socket(runtime).use { socket ->
                send(socket, "$method /____ds_script____/limit HTTP/1.1\r\nHost: browser.test\r\nConnection: close\r\n\r\n")
                val response = reply(socket, method)
                assertEquals(200, response.head.status)
                assertEquals(listOf("65536"), response.head.headers.values("Content-Length"))
                assertEquals(if (method == "GET") content else "", response.body)
            }
            assertEquals(0L, runtime.routes.state().selections)
        }
        assertThrows(com.dsha.sidecar.net.http.native.script.ScriptInjectionException::class.java) {
            NativeRuntimeScriptRoutes(mapOf("limit" to CatalogSource(content + "x")), setOf("http://browser.test"))
        }
    }

    @Test fun scriptRouteConstructionRejectsAmbiguousOriginsAndIncompatibleQuotas() {
        val sources = mapOf("alpha" to CatalogSource("x"))
        for (origin in listOf("", "https://user@host.test", "https://host.test/path",
            "https://host.test?x=1", "https://host.test#fragment", "https://host.test:",
            "https://host.test:0", "file://host.test")) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeRuntimeScriptRoutes(sources, setOf(origin))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeRuntimeScriptRoutes(sources, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuntimeScriptRoutes(sources, setOf("https://host.test"), limits = ScriptLimits())
        }
        val routes = NativeRuntimeScriptRoutes(sources, setOf("https://host.test"),
            limits = ScriptLimits(maximumAssetBytes = 128))
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuntime.fromDecoded(emptyMap(), clientCa.authority(),
                NativeRuntimeOptions(proxy = NativeProxyOptions(limits = Http1Limits(maxBodyBytes = 64))),
                scriptRoutes = routes)
        }
    }

    private class RuntimeClock(var millisNow: Long = Instant.now().toEpochMilli()) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(millisNow)
        override fun millis(): Long = millisNow
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    companion object {
        private val LOOPBACK = InetAddress.getByName("127.0.0.1")
        private val clientCa by lazy { EphemeralTestCa.create() }
        private val originCa by lazy { EphemeralTestCa.create() }
        private val dnsCa by lazy { EphemeralTestCa.create() }
        private val originLeaves by lazy { LeafCertificateCache(originCa.authority()) }
        private val dnsLeaves by lazy { LeafCertificateCache(dnsCa.authority()) }
        private val clientTrust by lazy { trust(clientCa) }
        private val originTrust by lazy { trust(originCa) }
        private val dnsTrust by lazy { trust(dnsCa) }
        private fun trust(ca: EphemeralTestCa): SSLContext {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null); setCertificateEntry("ephemeral-test-only", ca.certificate)
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        }
        private fun get(path: String, authority: String): String =
            "GET $path HTTP/1.1\r\nHost: $authority\r\nConnection: close\r\n\r\n"
        private fun send(socket: Socket, text: String) {
            socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
        }
        private fun fixed(output: OutputStream, body: String) {
            val bytes = body.toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            output.write(bytes)
        }
        private fun questionHost(query: ByteArray): String {
            var offset = 12
            val labels = mutableListOf<String>()
            while (query[offset].toInt() != 0) {
                val size = query[offset++].toInt() and 255
                labels.add(query.copyOfRange(offset, offset + size).toString(Charsets.US_ASCII))
                offset += size
            }
            return labels.joinToString(".")
        }
        private fun questionType(query: ByteArray): Int {
            var offset = 12
            while (query[offset].toInt() != 0) offset += 1 + (query[offset].toInt() and 255)
            return (query[offset + 1].toInt() and 255) * 256 + (query[offset + 2].toInt() and 255)
        }
        private fun dnsAnswer(query: ByteArray, addresses: List<ByteArray>): ByteArray {
            var end = 12
            while (query[end].toInt() != 0) end += 1 + (query[end].toInt() and 255)
            end += 5
            val type = (query[end - 4].toInt() and 255) * 256 + (query[end - 3].toInt() and 255)
            val a = if (type == 1) addresses else emptyList()
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.write(query, 0, 2); out.writeShort(0x8180); out.writeShort(1)
                out.writeShort(a.size); out.writeShort(0); out.writeShort(0)
                out.write(query, 12, end - 12)
                for (address in a) {
                    out.writeShort(0xc00c); out.writeShort(1); out.writeShort(1)
                    out.writeInt(60); out.writeShort(4); out.write(address)
                }
            }
            return bytes.toByteArray()
        }
        private fun nativeThreads(): Set<Long> = Thread.getAllStackTraces().keys
            .filter { it.isAlive && (it.name.startsWith("native-dns-") || it.name.startsWith("native-proxy-")) }
            .map { it.threadId() }.toSet()
    }
}
