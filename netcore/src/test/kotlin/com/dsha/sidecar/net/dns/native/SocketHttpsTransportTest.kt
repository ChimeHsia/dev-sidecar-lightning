// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.http.Http1ChunkMode
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import com.dsha.sidecar.net.dns.native.NativeDnsTestPackets as Packets

class SocketHttpsTransportTest {
    private val pins = NativeDnsEndpointResolver { check(it == "resolver.test"); dnsLoopback }
    private fun query(type: String, port: Int, secure: Boolean = false) = HttpsRecordQuery(
        DnsProviderDescriptor("provider:a", "a", type,
            if (type == "https") "${if (secure) "https" else "http"}://resolver.test:$port/dns-query" else "resolver.test",
            port = if (type == "https") null else port),
        "example.test",
    )
    private fun reply(bytes: ByteArray): ByteArray {
        assertTrue(Packets.question(bytes).type in listOf(64, 65))
        return Packets.response(bytes, listOf(Packets.Rr(Packets.question(bytes).host,
            Packets.question(bytes).type, NativeEchTestData.rdata(), 11)))
    }
    private fun assertReply(answer: HttpsRecordAnswer) {
        assertArrayEquals(NativeEchTestData.config(), answer.records.single().service.ech)
        assertEquals(11_000L, answer.records.single().ttlMillis)
    }

    @Test fun udpHttpsAndSvcbAreActualTypedQueries() {
        NativeDnsUdpServer(count = 2) { socket, packet -> socket.reply(packet, reply(packet.bytes())) }.use { server ->
            SocketDnsTransport(pins).use { transport ->
                for (type in HttpsRecordType.entries) {
                    assertReply(transport.queryHttps(query("udp", server.port).copy(type = type)).get(5, TimeUnit.SECONDS))
                }
                server.finish()
            }
        }
    }

    @Test fun tcpAndDotDecodeFragmentedServiceRecords() {
        for (type in listOf("tcp", "tls")) {
            NativeDnsStreamServer(tls = if (type == "tls") NativeDnsTestTls.server() else null) { socket ->
                socket.writeDns(reply(socket.readDns()), fragmented = true)
            }.use { server ->
                SocketDnsTransport(pins, NativeDnsTestTls.client).use { transport ->
                    assertReply(transport.queryHttps(query(type, server.port)).get(5, TimeUnit.SECONDS))
                    assertEquals(0, transport.state().resources)
                    server.finish()
                }
            }
        }
    }

    @Test fun httpAndHttpsDohPostCarryType65WithRealWireBody() {
        for (secure in listOf(false, true)) {
            NativeDnsStreamServer(tls = if (secure) NativeDnsTestTls.server() else null) { socket ->
                val reader = Http1Reader(socket.getInputStream())
                assertEquals("POST", reader.readRequest()!!.head.method)
                val request = ByteArrayOutputStream()
                reader.transferBody(request, Http1ChunkMode.DECODE)
                val answer = reply(request.toByteArray())
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n" +
                        "Content-Length: ${answer.size}\r\n\r\n").toByteArray())
                    write(answer); flush()
                }
            }.use { server ->
                SocketDnsTransport(pins, NativeDnsTestTls.client).use { transport ->
                    assertReply(transport.queryHttps(query("https", server.port, secure)).get(5, TimeUnit.SECONDS))
                    server.finish()
                }
            }
        }
    }

    @Test fun udpServiceTcRetriesSameQuestionOverTcp() {
        NativeDnsStreamServer { socket -> socket.writeDns(reply(socket.readDns())) }.use { server ->
            val udp = DatagramSocket(InetSocketAddress(dnsLoopback, server.port))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val served = pool.submit {
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    udp.receive(packet)
                    udp.reply(packet, Packets.response(packet.bytes(), flags = 0x8380))
                }
                SocketDnsTransport(pins).use { transport ->
                    assertReply(transport.queryHttps(query("udp", server.port)).get(5, TimeUnit.SECONDS))
                    served.get(5, TimeUnit.SECONDS)
                    server.finish()
                }
            } finally {
                udp.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun serviceCnameFollowupCarriesShortestTtlAndDependencyNames() {
        NativeDnsStreamServer(count = 2) { socket ->
            val request = socket.readDns()
            if (Packets.question(request).host == "example.test") socket.writeDns(Packets.response(request,
                listOf(Packets.cname("example.test", "edge.test", 2))))
            else socket.writeDns(reply(request))
        }.use { server ->
            SocketDnsTransport(pins).use { transport ->
                val result = transport.queryHttps(query("tcp", server.port)).get(5, TimeUnit.SECONDS)
                assertEquals(2_000L, result.records.single().ttlMillis)
                assertEquals(listOf("edge.test"), result.canonicalNames)
                server.finish()
            }
        }
    }

    @Test fun canceledTypedQueryClosesPeerAndCloseFailsOrdinaryAndTypedPendingQueries() {
        val received = CountDownLatch(1)
        NativeDnsStreamServer { socket ->
            socket.readDns(); received.countDown()
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            val transport = SocketDnsTransport(pins, limits = SocketDnsLimits(workers = 1, queueCapacity = 1))
            try {
                val active = transport.queryHttps(query("tcp", server.port))
                assertTrue(received.await(5, TimeUnit.SECONDS))
                val queued = transport.query(NativeDnsQuery(query("tcp", server.port).provider,
                    "other.test", NativeDnsFamily.IPV4))
                assertTrue(active.cancel(true))
                transport.close()
                try { queued.get(5, TimeUnit.SECONDS); fail() } catch (_: ExecutionException) { }
                assertEquals(0, transport.state().resources)
                assertTrue(transport.awaitTermination(5_000))
                server.finish()
            } finally { transport.close() }
        }
    }

    @Test fun typedDeadlineAndMalformedResponseFailWithoutInventedServiceRecords() {
        for (malformed in listOf(true, false)) {
            NativeDnsUdpServer { socket, packet ->
                if (malformed) socket.reply(packet, Packets.response(packet.bytes(), type = 1))
            }.use { server ->
                val transport = SocketDnsTransport(pins, limits = SocketDnsLimits(queryTimeoutMillis = 250))
                try {
                    try { transport.queryHttps(query("udp", server.port)).get(5, TimeUnit.SECONDS); fail() }
                    catch (failure: ExecutionException) {
                        assertTrue(if (malformed) failure.cause is NativeDnsProtocolException
                            else failure.cause is java.net.SocketTimeoutException)
                    }
                    assertEquals(0, transport.state().resources)
                    server.finish()
                } finally { transport.close(); assertTrue(transport.awaitTermination(5_000)) }
            }
        }
    }
}
