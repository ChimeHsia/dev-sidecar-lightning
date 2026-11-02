// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.proxy.http.Http1ChunkMode
import com.dsha.sidecar.net.proxy.http.Http1Reader
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import com.dsha.sidecar.net.dns.native.NativeDnsTestPackets as Packets

class SocketDnsTransportTest {
    private val pinned = NativeDnsEndpointResolver {
        check(it == "resolver.test" || it == "127.0.0.1")
        dnsLoopback
    }
    private fun query(
        type: String,
        port: Int,
        family: NativeDnsFamily = NativeDnsFamily.IPV4,
        sni: String? = null,
        server: String = "resolver.test",
    ) = NativeDnsQuery(
        DnsProviderDescriptor("provider:test", "test", type,
            if (type == "https") "http://$server:$port/dns-query?key=local" else server,
            port = if (type == "https") null else port, sni = sni),
        "example.test", family,
    )

    private fun <T> CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)
    private fun CountDownLatch.awaitChecked() = assertTrue("Latch deadline exceeded", await(5, TimeUnit.SECONDS))
    private inline fun <reified T : Throwable> failure(future: CompletableFuture<*>): Throwable {
        try {
            future.await()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: ExecutionException) {
            assertTrue("Expected ${T::class.java.simpleName}, received ${error.cause}", error.cause is T)
            return error.cause!!
        }
        error("No failure")
    }

    private fun transport(
        limits: SocketDnsLimits = SocketDnsLimits(),
        ssl: SSLContext = SSLContext.getDefault(),
    ) = SocketDnsTransport(pinned, ssl, limits)

    private fun assertEmpty(transport: SocketDnsTransport) {
        assertEquals(0, transport.state().queries)
        assertEquals(0, transport.state().resources)
        assertEquals(0, transport.state().queuedTasks)
    }

    private fun answer(request: ByteArray, ttl: Long = 60): ByteArray {
        val question = Packets.question(request)
        val rr = if (question.type == 1) Packets.a(question.host, ttl) else Packets.aaaa(question.host, ttl)
        return Packets.response(request, listOf(rr))
    }

    @Test fun actualUdpReturnsAddressAndTtlThenReleasesSocket() {
        NativeDnsUdpServer { socket, packet -> socket.reply(packet, answer(packet.bytes(), 9)) }.use { server ->
            transport().use { transport ->
                val answer = transport.query(query("udp", server.port)).await()
                assertEquals(listOf(NativeDnsRecord("192.0.2.7", 9_000)), answer.records)
                assertEmpty(transport)
                server.finish()
            }
        }
    }

    @Test fun defaultBootstrapSupportsLiteralProviderWithoutNameService() {
        NativeDnsUdpServer { socket, packet -> socket.reply(packet, answer(packet.bytes())) }.use { server ->
            SocketDnsTransport().use { transport ->
                val answer = transport.query(query("udp", server.port, server = "127.0.0.1")).await()
                assertEquals("192.0.2.7", answer.records.single().address)
                server.finish()
            }
        }
    }

    @Test fun anyFamilyUsesAThenAaaaAndRetainsBothTtlsUnderOneQuery() {
        val types = mutableListOf<Int>()
        NativeDnsUdpServer(count = 2) { socket, packet ->
            types.add(Packets.question(packet.bytes()).type)
            socket.reply(packet, answer(packet.bytes(), if (types.size == 1) 4 else 8))
        }.use { server ->
            transport().use { transport ->
                val answer = transport.query(query("udp", server.port, NativeDnsFamily.ANY)).await()
                assertEquals(listOf(1, 28), types)
                assertEquals(listOf(4_000L, 8_000L), answer.records.map { it.ttlMillis })
                assertEquals(listOf("192.0.2.7", "2001:db8:0:0:0:0:0:9"), answer.records.map { it.address })
                server.finish()
            }
        }
    }

    @Test fun anyFamilyDoesNotHideSecondQueryFailureOrNameWideNxDomainConflict() {
        for (conflict in listOf(false, true)) {
            NativeDnsUdpServer(count = 2) { socket, packet ->
                val request = packet.bytes()
                val response = if (Packets.question(request).type == 1) answer(request) else
                    Packets.response(request, flags = if (conflict) 0x8183 else 0x8182)
                socket.reply(packet, response)
            }.use { server ->
                transport().use { transport ->
                    failure<NativeDnsProtocolException>(transport.query(query("udp", server.port, NativeDnsFamily.ANY)))
                    assertEmpty(transport)
                    server.finish()
                }
            }
        }
    }

    @Test fun wrongUdpTransactionAndQuestionAreRejectedBeforeAnyFallback() {
        for (wrongId in listOf(true, false)) {
            NativeDnsUdpServer { socket, packet ->
                val request = packet.bytes()
                socket.reply(packet, if (wrongId)
                    Packets.response(request, id = Packets.question(request).id xor 1, flags = 0x8380)
                else Packets.response(request, hostname = "wrong.test"))
            }.use { server ->
                transport().use { transport ->
                    failure<NativeDnsProtocolException>(transport.query(query("udp", server.port)))
                    assertEmpty(transport)
                    server.finish()
                }
            }
        }
    }

    @Test fun connectedUdpIgnoresPacketsFromWrongSourcePort() {
        val rogueSent = CountDownLatch(1)
        val sendReal = CountDownLatch(1)
        NativeDnsUdpServer { socket, packet ->
            DatagramSocket(InetSocketAddress(dnsLoopback, 0)).use { rogue ->
                rogue.reply(packet, Packets.response(packet.bytes(), listOf(Packets.a("example.test", last = 99))))
            }
            rogueSent.countDown()
            sendReal.awaitChecked()
            socket.reply(packet, answer(packet.bytes()))
        }.use { server ->
            try {
                transport().use { transport ->
                    val result = transport.query(query("udp", server.port))
                    rogueSent.awaitChecked()
                    try {
                        result.get(75, TimeUnit.MILLISECONDS)
                        fail("Wrong-peer UDP reply was accepted")
                    } catch (_: TimeoutException) { }
                    sendReal.countDown()
                    assertEquals("192.0.2.7", result.await().records.single().address)
                    server.finish()
                }
            } finally { sendReal.countDown() }
        }
    }

    @Test fun udpTcFallsBackToTcpSameEndpointAndSameTransaction() {
        val tcpRequest = AtomicReference<ByteArray>()
        NativeDnsStreamServer { socket ->
            val request = socket.readDns()
            tcpRequest.set(request)
            socket.writeDns(answer(request), fragmented = true)
        }.use { tcp ->
            val udp = DatagramSocket(InetSocketAddress(dnsLoopback, tcp.port))
            val received = CountDownLatch(1)
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            val udpRequest = AtomicReference<ByteArray>()
            try {
                val serving = worker.submit {
                    val packet = java.net.DatagramPacket(ByteArray(4096), 4096)
                    udp.receive(packet)
                    udpRequest.set(packet.bytes())
                    udp.reply(packet, Packets.response(packet.bytes(), flags = 0x8380).apply { this[7] = 7 })
                    received.countDown()
                }
                transport().use { transport ->
                    val result = transport.query(query("udp", tcp.port)).await()
                    received.awaitChecked()
                    assertEquals("192.0.2.7", result.records.single().address)
                    assertArrayEquals(udpRequest.get(), tcpRequest.get())
                    assertEmpty(transport)
                    serving.get(5, TimeUnit.SECONDS)
                    tcp.finish()
                }
            } finally {
                udp.close()
                worker.shutdownNow()
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun fragmentedTcpPrefixAndBodyAreReadFully() {
        NativeDnsStreamServer { socket ->
            socket.writeDns(answer(socket.readDns(), 11), fragmented = true)
        }.use { server ->
            transport().use { transport ->
                assertEquals(listOf(NativeDnsRecord("192.0.2.7", 11_000)),
                    transport.query(query("tcp", server.port)).await().records)
                assertEmpty(transport)
                server.finish()
            }
        }
    }

    @Test fun tcpShortFrameAndTruncatedBodyFailWithoutLeakingResource() {
        for (shortPrefix in listOf(true, false)) {
            NativeDnsStreamServer { socket ->
                val request = socket.readDns()
                val output = DataOutputStream(socket.getOutputStream())
                output.writeShort(if (shortPrefix) 2 else 100)
                output.write(if (shortPrefix) byteArrayOf(1, 2) else answer(request).copyOf(20))
                output.flush()
            }.use { server ->
                transport().use { transport ->
                    failure<IOException>(transport.query(query("tcp", server.port)))
                    assertEmpty(transport)
                    server.finish()
                }
            }
        }
    }

    @Test fun tcpRejectsWrongIdQuestionAndTruncatedFlag() {
        for (variant in 0..2) {
            NativeDnsStreamServer { socket ->
                val request = socket.readDns()
                socket.writeDns(when (variant) {
                    0 -> Packets.response(request, id = Packets.question(request).id xor 1)
                    1 -> Packets.response(request, type = 28)
                    else -> Packets.response(request, flags = 0x8380)
                })
            }.use { server ->
                transport().use { transport ->
                    failure<NativeDnsProtocolException>(transport.query(query("tcp", server.port)))
                    server.finish()
                }
            }
        }
    }

    @Test fun cnameOnlyResponseTriggersBoundedFollowupWithFreshIdAndMinimumTtl() {
        val requests = mutableListOf<Packets.Question>()
        NativeDnsStreamServer(count = 2) { socket ->
            val bytes = socket.readDns()
            val request = Packets.question(bytes)
            requests.add(request)
            socket.writeDns(if (request.host == "example.test")
                Packets.response(bytes, listOf(Packets.cname(request.host, "edge.test", 3)))
            else Packets.response(bytes, listOf(Packets.a(request.host, 600))))
        }.use { server ->
            transport().use { transport ->
                val result = transport.query(query("tcp", server.port)).await()
                assertEquals(listOf("example.test", "edge.test"), requests.map { it.host })
                assertEquals(listOf(NativeDnsRecord("192.0.2.7", 3_000)), result.records)
                server.finish()
            }
        }
    }

    @Test fun crossResponseCnameCycleIsRejected() {
        NativeDnsStreamServer(count = 2) { socket ->
            val bytes = socket.readDns()
            val host = Packets.question(bytes).host
            socket.writeDns(Packets.response(bytes,
                listOf(Packets.cname(host, if (host == "example.test") "edge.test" else "example.test"))))
        }.use { server ->
            transport().use { transport ->
                failure<NativeDnsProtocolException>(transport.query(query("tcp", server.port)))
                assertEmpty(transport)
                server.finish()
            }
        }
    }

    @Test fun negativeDnsAnswerCarriesRealSoaTtl() {
        NativeDnsStreamServer { socket ->
            val request = socket.readDns()
            socket.writeDns(Packets.response(request, flags = 0x8183,
                authority = listOf(Packets.soa("test", 45, 12))))
        }.use { server ->
            transport().use { transport ->
                val result = transport.query(query("tcp", server.port)).await()
                assertEquals(NativeDnsAnswerCode.NXDOMAIN, result.code)
                assertEquals(12_000L, result.negativeTtlMillis)
                assertTrue(result.records.isEmpty())
                server.finish()
            }
        }
    }

    @Test fun realDotValidatesPrivateChainAndServiceHostAndSendsSeparateSni() {
        val providersBefore = Security.getProviders().map { it.name }
        val observed = AtomicReference<String>()
        NativeDnsStreamServer(tls = NativeDnsTestTls.server()) { socket ->
            val tls = socket as SSLSocket
            tls.startHandshake()
            val session = tls.session as ExtendedSSLSession
            observed.set((session.requestedServerNames.single() as SNIHostName).asciiName)
            socket.writeDns(answer(socket.readDns()))
        }.use { server ->
            transport(ssl = NativeDnsTestTls.client).use { transport ->
                val result = transport.query(query("tls", server.port, sni = "cover.test")).await()
                assertEquals("192.0.2.7", result.records.single().address)
                assertEquals("cover.test", observed.get())
                assertEmpty(transport)
                server.finish()
            }
        }
        assertEquals(providersBefore, Security.getProviders().map { it.name })
    }

    @Test fun dotDefaultTrustRejectsPrivateCaEvenWhenSniIsConfigured() {
        NativeDnsStreamServer(tls = NativeDnsTestTls.server(), allowIoFailure = true) { socket ->
            (socket as SSLSocket).startHandshake()
            socket.writeDns(answer(socket.readDns()))
        }.use { server ->
            transport().use { transport ->
                failure<SSLHandshakeException>(transport.query(query("tls", server.port, sni = "resolver.test")))
                assertEmpty(transport)
            }
        }
    }

    @Test fun certificateMatchingOnlySniCannotReplaceServiceIdentity() {
        NativeDnsStreamServer(tls = NativeDnsTestTls.server(listOf("cover.test")), allowIoFailure = true) { socket ->
            (socket as SSLSocket).startHandshake()
            socket.writeDns(answer(socket.readDns()))
        }.use { server ->
            transport(ssl = NativeDnsTestTls.client).use { transport ->
                failure<SSLHandshakeException>(transport.query(query("tls", server.port, sni = "cover.test")))
                assertEmpty(transport)
            }
        }
    }

    @Test fun expiredTrustedLeafStillFailsTlsValidation() {
        NativeDnsStreamServer(tls = NativeDnsTestTls.server(expired = true), allowIoFailure = true) { socket ->
            (socket as SSLSocket).startHandshake()
        }.use { server ->
            transport(ssl = NativeDnsTestTls.client).use { transport ->
                failure<SSLHandshakeException>(transport.query(query("tls", server.port)))
                assertEmpty(transport)
            }
        }
    }

    @Test fun literalTlsServiceRequiresIpSanAndDoesNotEmitDnsSni() {
        NativeDnsStreamServer(tls = NativeDnsTestTls.server(ip = "127.0.0.1")) { socket ->
            val tls = socket as SSLSocket
            tls.startHandshake()
            assertTrue((tls.session as ExtendedSSLSession).requestedServerNames.isEmpty())
            socket.writeDns(answer(socket.readDns()))
        }.use { server ->
            transport(ssl = NativeDnsTestTls.client).use { transport ->
                assertEquals("192.0.2.7",
                    transport.query(query("tls", server.port, server = "127.0.0.1")).await().records.single().address)
                server.finish()
            }
        }
    }

    private fun httpQuestion(socket: Socket): ByteArray {
        val reader = Http1Reader(socket.getInputStream())
        val head = requireNotNull(reader.readRequest()).head
        assertEquals("POST", head.method)
        assertEquals("/dns-query?key=local", head.target.raw)
        assertEquals(listOf("application/dns-message"), head.headers.values("Content-Type"))
        assertEquals(listOf("application/dns-message"), head.headers.values("Accept"))
        assertTrue(head.headers.values("Host").single().startsWith("resolver.test:"))
        val body = ByteArrayOutputStream()
        reader.transferBody(body, Http1ChunkMode.DECODE)
        return body.toByteArray()
    }

    private fun httpResponse(socket: Socket, body: ByteArray, mode: String = "fixed") {
        val output = socket.getOutputStream()
        val framing = when (mode) {
            "chunked" -> "Transfer-Encoding: chunked\r\n"
            "close" -> ""
            else -> "Content-Length: ${body.size}\r\n"
        }
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n" +
            framing + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        if (mode == "chunked") {
            output.write(("${body.size.toString(16)};local=yes\r\n").toByteArray())
            output.write(body)
            output.write("\r\n0\r\nX-Local: done\r\n\r\n".toByteArray())
        } else output.write(body)
        output.flush()
    }

    @Test fun actualDohHttpPostSupportsFixedChunkedAndCloseDelimitedBodies() {
        for (mode in listOf("fixed", "chunked", "close")) {
            NativeDnsStreamServer { socket -> httpResponse(socket, answer(httpQuestion(socket)), mode) }.use { server ->
                transport().use { transport ->
                    val result = transport.query(query("https", server.port)).await()
                    assertEquals("192.0.2.7", result.records.single().address)
                    assertEmpty(transport)
                    server.finish()
                }
            }
        }
    }

    @Test fun actualDohHttpsUsesPinnedEndpointAndStrictServiceIdentity() {
        NativeDnsStreamServer(tls = NativeDnsTestTls.server()) { socket ->
            httpResponse(socket, answer(httpQuestion(socket)))
        }.use { server ->
            transport(ssl = NativeDnsTestTls.client).use { transport ->
                val plain = query("https", server.port)
                val request = plain.copy(provider = plain.provider.copy(
                    server = plain.provider.server!!.replace("http:", "https:"), sni = "cover.test",
                ))
                assertEquals("192.0.2.7", transport.query(request).await().records.single().address)
                assertEmpty(transport)
                server.finish()
            }
        }
    }

    @Test fun dohRejectsJsonRedirectsCompressionAndAmbiguousFraming() {
        val heads = listOf(
            "200 OK\r\nContent-Type: application/dns-json\r\nContent-Length: 2\r\n\r\n{}",
            "302 Found\r\nLocation: http://external.invalid/\r\nContent-Length: 0\r\n\r\n",
            "200 OK\r\nContent-Type: application/dns-message\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n",
            "200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
            "200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
        )
        for (head in heads) {
            NativeDnsStreamServer { socket ->
                httpQuestion(socket)
                socket.getOutputStream().write("HTTP/1.1 $head".toByteArray())
            }.use { server ->
                transport().use { transport ->
                    failure<Exception>(transport.query(query("https", server.port)))
                    assertEmpty(transport)
                    server.finish()
                }
            }
        }
    }

    @Test fun dohRejectsWrongWireQuestionAndOversizedOrTruncatedBody() {
        for (variant in 0..3) {
            NativeDnsStreamServer { socket ->
                val request = httpQuestion(socket)
                when (variant) {
                    0 -> httpResponse(socket, Packets.response(request, hostname = "wrong.test"))
                    1 -> socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 513\r\n\r\n".toByteArray())
                    2 -> socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n" +
                            "Content-Length: 100\r\n\r\nshort").toByteArray())
                    3 -> httpResponse(socket, ByteArray(513), "chunked")
                }
            }.use { server ->
                transport(SocketDnsLimits(maxPacketBytes = 512)).use { transport ->
                    failure<IOException>(transport.query(query("https", server.port)))
                    assertEmpty(transport)
                    server.finish()
                }
            }
        }
    }

    @Test fun unsupportedTransportAndUrlFailBeforeBootstrap() {
        val calls = AtomicInteger()
        SocketDnsTransport(NativeDnsEndpointResolver { calls.incrementAndGet(); dnsLoopback }).use { transport ->
            for (type in listOf("nat64", "preset", "unknown")) {
                val request = query("udp", 53).copy(provider = DnsProviderDescriptor("x", "x", type))
                failure<UnsupportedOperationException>(transport.query(request))
            }
            for (url in listOf("ftp://resolver.test/x", "http://user@resolver.test/x",
                "https://resolver.test:0/x", "https://resolver.test/x#fragment")) {
                val plain = query("https", 53)
                failure<IllegalArgumentException>(transport.query(plain.copy(provider = plain.provider.copy(server = url))))
            }
            assertEquals(0, calls.get())
            assertEmpty(transport)
        }
    }

    @Test fun cancellationClosesOwnedUdpPortAndReleasesWorker() {
        val received = CountDownLatch(1)
        val client = AtomicReference<InetSocketAddress>()
        NativeDnsUdpServer { _, packet -> client.set(packet.socketAddress as InetSocketAddress); received.countDown() }.use { server ->
            val transport = transport()
            try {
                val result = transport.query(query("udp", server.port))
                received.awaitChecked()
                assertTrue(result.cancel(true))
                assertTrue(result.isCancelled)
                assertEmpty(transport)
                DatagramSocket(InetSocketAddress(dnsLoopback, client.get().port)).close()
                server.finish()
            } finally {
                transport.close()
                assertTrue(transport.awaitTermination(5_000))
            }
        }
    }

    @Test fun cancellationClosesOwnedTcpAndHttpSocketsAndReleasesPeerReads() {
        for (type in listOf("tcp", "https")) {
            val received = CountDownLatch(1)
            val eof = CountDownLatch(1)
            NativeDnsStreamServer { socket ->
                if (type == "tcp") socket.readDns() else httpQuestion(socket)
                received.countDown()
                assertEquals(-1, socket.getInputStream().read())
                eof.countDown()
            }.use { server ->
                val transport = transport()
                try {
                    val result = transport.query(query(type, server.port))
                    received.awaitChecked()
                    assertTrue(result.cancel(true))
                    eof.awaitChecked()
                    assertEmpty(transport)
                    server.finish()
                } finally {
                    transport.close()
                    assertTrue(transport.awaitTermination(5_000))
                }
            }
        }
    }

    @Test fun cancellationDuringTlsHandshakeClosesUnderlyingTcpSocket() {
        val received = CountDownLatch(1)
        val eof = CountDownLatch(1)
        NativeDnsStreamServer { socket ->
            assertTrue(socket.getInputStream().read() >= 0)
            received.countDown()
            val buffer = ByteArray(2048)
            while (socket.getInputStream().read(buffer) != -1) { }
            eof.countDown()
        }.use { server ->
            val transport = transport(ssl = NativeDnsTestTls.client)
            try {
                val request = transport.query(query("tls", server.port))
                received.awaitChecked()
                assertTrue(request.cancel(true))
                eof.awaitChecked()
                assertEmpty(transport)
                server.finish()
            } finally {
                transport.close()
                assertTrue(transport.awaitTermination(5_000))
            }
        }
    }

    @Test fun udpTcpAndHttpTotalDeadlinesFailAndCloseResources() {
        NativeDnsUdpServer { _, _ -> }.use { server ->
            val transport = transport(SocketDnsLimits(queryTimeoutMillis = 250))
            try {
                failure<SocketTimeoutException>(transport.query(query("udp", server.port)))
                assertEmpty(transport)
                server.finish()
            } finally {
                transport.close()
                assertTrue(transport.awaitTermination(5_000))
            }
        }
        for (type in listOf("tcp", "https")) {
            val eof = CountDownLatch(1)
            NativeDnsStreamServer { socket ->
                if (type == "tcp") socket.readDns() else httpQuestion(socket)
                if (type == "https") socket.getOutputStream().write("HTTP/1.1 200".toByteArray())
                assertEquals(-1, socket.getInputStream().read())
                eof.countDown()
            }.use { server ->
                val transport = transport(SocketDnsLimits(queryTimeoutMillis = 500))
                try {
                    failure<SocketTimeoutException>(transport.query(query(type, server.port)))
                    eof.awaitChecked()
                    assertEmpty(transport)
                    server.finish()
                } finally {
                    transport.close()
                    assertTrue(transport.awaitTermination(5_000))
                }
            }
        }
    }

    @Test fun boundedWorkersQueueAndQueuedCancellationRecoverCapacity() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resolver = NativeDnsEndpointResolver {
            calls.incrementAndGet()
            entered.countDown()
            release.awaitChecked()
            dnsLoopback
        }
        val transport = SocketDnsTransport(resolver, limits = SocketDnsLimits(workers = 1, queueCapacity = 1))
        try {
            val running = transport.query(query("tcp", 1))
            entered.awaitChecked()
            val queued = transport.query(query("tcp", 1))
            failure<NativeDnsCapacityException>(transport.query(query("tcp", 1)))
            assertEquals(1, transport.state().queuedTasks)
            assertTrue(queued.cancel(true))
            assertEquals(0, transport.state().queuedTasks)
            val replacement = transport.query(query("tcp", 1))
            assertEquals(1, transport.state().queuedTasks)
            transport.close()
            failure<NativeDnsClosedException>(running)
            failure<NativeDnsClosedException>(replacement)
            failure<NativeDnsClosedException>(transport.query(query("tcp", 1)))
            assertEquals(1, calls.get())
            assertEmpty(transport)
        } finally {
            release.countDown()
            transport.close()
            assertTrue(transport.awaitTermination(5_000))
        }
    }

    @Test fun deadlineIncludesQueuedAndUninterruptibleBootstrapWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = SocketDnsTransport(NativeDnsEndpointResolver {
            entered.countDown()
            var done = false
            while (!done) {
                try { done = release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            dnsLoopback
        }, limits = SocketDnsLimits(workers = 1, queueCapacity = 1, queryTimeoutMillis = 250))
        try {
            val running = transport.query(query("tcp", 1))
            entered.awaitChecked()
            val queued = transport.query(query("tcp", 1))
            failure<SocketTimeoutException>(running)
            failure<SocketTimeoutException>(queued)
            assertEmpty(transport)
        } finally {
            release.countDown()
            transport.close()
            assertTrue(transport.awaitTermination(5_000))
        }
    }

    @Test fun closeReleasesActiveAndQueuedCallersAndIsIdempotent() {
        val received = CountDownLatch(1)
        val eof = CountDownLatch(1)
        NativeDnsStreamServer { socket ->
            socket.readDns()
            received.countDown()
            assertEquals(-1, socket.getInputStream().read())
            eof.countDown()
        }.use { server ->
            val transport = transport(SocketDnsLimits(workers = 1, queueCapacity = 2))
            try {
                val active = transport.query(query("tcp", server.port))
                received.awaitChecked()
                val queued = transport.query(query("tcp", server.port))
                transport.close()
                transport.close()
                failure<NativeDnsClosedException>(active)
                failure<NativeDnsClosedException>(queued)
                eof.awaitChecked()
                assertEmpty(transport)
                assertTrue(transport.state().closed)
                assertTrue(transport.awaitTermination(5_000))
                server.finish()
            } finally { transport.close() }
        }
    }

    @Test fun establishedDotAndHttpsCancellationReleaseBothTlsAndRawResources() {
        for (type in listOf("tls", "https")) {
            val received = CountDownLatch(1)
            val eof = CountDownLatch(1)
            NativeDnsStreamServer(tls = NativeDnsTestTls.server()) { socket ->
                if (type == "tls") socket.readDns() else httpQuestion(socket)
                received.countDown()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (_: SSLException) {
                    // Closing the raw TCP connection may omit TLS close_notify.
                }
                eof.countDown()
            }.use { server ->
                val transport = transport(ssl = NativeDnsTestTls.client)
                try {
                    val plain = query(type, server.port)
                    val request = if (type == "https") plain.copy(provider = plain.provider.copy(
                        server = plain.provider.server!!.replace("http:", "https:"),
                    )) else plain
                    val result = transport.query(request)
                    received.awaitChecked()
                    assertTrue(result.cancel(true))
                    eof.awaitChecked()
                    assertEmpty(transport)
                    server.finish()
                } finally {
                    transport.close()
                    assertTrue(transport.awaitTermination(5_000))
                }
            }
        }
    }

    @Test fun tlsHandshakeDeadlineClosesRawPeerAndReleasesCaller() {
        val eof = CountDownLatch(1)
        NativeDnsStreamServer { socket ->
            val buffer = ByteArray(2048)
            while (socket.getInputStream().read(buffer) != -1) { }
            eof.countDown()
        }.use { server ->
            val transport = transport(SocketDnsLimits(queryTimeoutMillis = 1_000), NativeDnsTestTls.client)
            try {
                failure<SocketTimeoutException>(transport.query(query("tls", server.port)))
                eof.awaitChecked()
                assertEmpty(transport)
                server.finish()
            } finally {
                transport.close()
                assertTrue(transport.awaitTermination(5_000))
            }
        }
    }

    @Test fun oversizedUdpDatagramAndTcpLengthAreRejectedBeforeDecodeOrAllocation() {
        NativeDnsUdpServer { socket, packet ->
            socket.reply(packet, Packets.response(packet.bytes()).copyOf(513))
        }.use { server ->
            transport(SocketDnsLimits(maxPacketBytes = 512)).use { transport ->
                failure<NativeDnsProtocolException>(transport.query(query("udp", server.port)))
                assertEmpty(transport)
                server.finish()
            }
        }
        NativeDnsStreamServer { socket ->
            socket.readDns()
            DataOutputStream(socket.getOutputStream()).apply { writeShort(513); flush() }
        }.use { server ->
            transport(SocketDnsLimits(maxPacketBytes = 512)).use { transport ->
                failure<NativeDnsProtocolException>(transport.query(query("tcp", server.port)))
                assertEmpty(transport)
                server.finish()
            }
        }
    }

    @Test fun actualTransportWorksThroughFrozenResolverWithIndependentPortFeedback() {
        NativeDnsStreamServer(count = 2) { socket ->
            val request = socket.readDns()
            socket.writeDns(Packets.response(request, listOf(
                Packets.a("example.test", last = 1), Packets.a("example.test", last = 2),
            )))
        }.use { server ->
            val catalog = com.dsha.sidecar.net.dns.DnsProviderConfig.normalize(mapOf(
                "test" to mapOf("server" to "tcp://resolver.test:${server.port}", "family" to 4),
            ))
            val transport = transport()
            NativeDnsResolver(catalog, transport, NativeDnsClock { 100 },
                mapping = mapOf("example.test" to mapOf("dnsName" to "test"))).use { resolver ->
                val http = resolver.resolve("example.test", destinationPort = 80)
                val https = resolver.resolve("example.test", destinationPort = 443)
                assertEquals("192.0.2.1", http.await().selectedIp)
                assertEquals("192.0.2.1", https.await().selectedIp)
                assertTrue(resolver.feedback(http.await(), false))
                assertEquals("192.0.2.2", resolver.resolve("example.test", destinationPort = 80).await().selectedIp)
                assertEquals("192.0.2.1", resolver.resolve("example.test").await().selectedIp)
                resolver.invalidate("example.test")
                assertEquals(0, resolver.state().entries)
                assertEmpty(transport)
                server.finish()
            }
            assertTrue(transport.awaitTermination(5_000))
        }
    }

    @Test fun providerBootstrapFamilyIsSeparateFromRequestedRecordFamily() {
        val seenFamily = AtomicReference<NativeDnsFamily>()
        val endpoints = object : NativeDnsEndpointResolver {
            override fun resolve(hostname: String): java.net.InetAddress = error("Use family-aware overload")
            override fun resolve(hostname: String, family: NativeDnsFamily): java.net.InetAddress {
                assertEquals("resolver.test", hostname)
                seenFamily.set(family)
                return dnsLoopback
            }
        }
        NativeDnsUdpServer { socket, packet ->
            assertEquals(28, Packets.question(packet.bytes()).type)
            socket.reply(packet, answer(packet.bytes()))
        }.use { server ->
            SocketDnsTransport(endpoints).use { transport ->
                val plain = query("udp", server.port, NativeDnsFamily.IPV6)
                val request = plain.copy(provider = plain.provider.copy(family = 4))
                assertEquals("2001:db8:0:0:0:0:0:9", transport.query(request).await().records.single().address)
                assertEquals(NativeDnsFamily.IPV4, seenFamily.get())
                assertEmpty(transport)
                server.finish()
            }
        }
    }

    @Test fun defaultLiteralBootstrapHonorsFamilyWithoutExternalResolution() {
        val resolver = NativeDnsEndpointResolver.SYSTEM
        assertTrue(resolver.resolve("127.0.0.1", NativeDnsFamily.IPV4) is java.net.Inet4Address)
        assertTrue(resolver.resolve("::1", NativeDnsFamily.IPV6) is java.net.Inet6Address)
        try {
            resolver.resolve("127.0.0.1", NativeDnsFamily.IPV6)
            fail("Expected literal bootstrap family mismatch")
        } catch (_: java.net.UnknownHostException) { }
    }
}
