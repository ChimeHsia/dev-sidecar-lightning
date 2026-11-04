// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.Nat64Address
import com.dsha.sidecar.net.proxy.http.Http1Reader
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class NativeNat64Ipv6Test {
    private val prefix = "64:ff9b::/96"
    private fun descriptor(port: Int) = DnsProviderDescriptor("builtin:Nat64", "NAT64", "nat64",
        nat64Options = mapOf("prefix" to prefix, "dns" to "local", "providers" to mapOf(
            "local" to mapOf("server" to "https://resolver.test:$port/resolve", "sni" to "cover.test")),
            "bootstrap" to mapOf("resolver.test" to listOf("192.0.2.53"),
                "dns.alidns.com" to emptyList<String>(), "cloudflare-dns.com" to emptyList<String>(),
                "dns.google" to emptyList<String>())))

    private fun socket(context: SSLContext = NativeDnsTestTls.client) =
        SocketDnsTransport(NativeDnsEndpointResolver { error("NAT64 may not use OS bootstrap") }, context)

    private fun testDialer(socket: SocketDnsTransport): NativeNat64DohDialer = object : NativeNat64DohDialer {
        override fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer> {
            assertEquals(Nat64Address.toNat64Address(prefix, "192.0.2.53"), query.ipv6Destination)
            assertTrue(query.provider.server!!.startsWith("https://resolver.test:"))
            assertEquals("cover.test", query.provider.sni)
            // Explicit local test substitution, never the production/default route.
            return socket.queryNat64Doh(query.copy(ipv6Destination = "::1"))
        }
    }

    private fun request(socket: Socket, port: Int): String {
        val head = Http1Reader(socket.getInputStream()).readRequest()!!.head
        assertEquals("GET", head.method)
        assertEquals(listOf("resolver.test:$port"), head.headers.values("Host"))
        assertEquals(listOf("application/dns-json"), head.headers.values("Accept"))
        val params = head.target.raw.substringAfter('?').split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("example.test", params["name"])
        val tls = socket as SSLSocket
        assertEquals("cover.test",
            ((tls.session as ExtendedSSLSession).requestedServerNames.single() as SNIHostName).asciiName)
        return params["type"]!!
    }

    private fun respond(socket: Socket, body: String, chunked: Boolean = false) {
        val bytes = body.toByteArray()
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/dns-json\r\n" +
            (if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${bytes.size}\r\n") + "\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray())
            if (chunked) write((bytes.size.toString(16) + "\r\n").toByteArray())
            write(bytes)
            if (chunked) write("\r\n0\r\n\r\n".toByteArray())
            flush()
        }
    }

    @Test fun realIpv6TlsJsonAQueryPreservesAuthoritySniVerifyHostAndSynthesizesDestinations() {
        Ipv6Server(NativeDnsTestTls.server(), 1) { peer, port ->
            assertEquals("A", request(peer, port))
            respond(peer, Nat64TestJson.answer(), true)
        }.use { server ->
            val socket = socket()
            NativeNat64Transport(socket, NativeDnsClock { 100 }, testDialer(socket)).use { transport ->
                val result = transport.query(NativeDnsQuery(descriptor(server.port), "example.test",
                    NativeDnsFamily.IPV6)).get(5, TimeUnit.SECONDS)
                assertEquals(listOf(NativeDnsRecord(
                    Nat64Address.toNat64Address(prefix, "192.0.2.7")!!, 60_000)), result.records)
                assertEquals(0, socket.state().resources)
                server.finish()
            }
            assertTrue(socket.awaitTermination(5_000))
        }
    }

    @Test fun realIpv6HttpsAndSvcbQueriesPreserveFullEchConfigAndServiceTtl() {
        Ipv6Server(NativeDnsTestTls.server(), 2) { peer, port ->
            val type = request(peer, port)
            respond(peer, Nat64TestJson.https(if (type == "HTTPS") 65 else 64))
        }.use { server ->
            val socket = socket()
            NativeNat64Transport(socket, NativeDnsClock { 100 }, testDialer(socket)).use { transport ->
                for (type in HttpsRecordType.entries) {
                    val result = transport.queryHttps(HttpsRecordQuery(descriptor(server.port),
                        "example.test", type)).get(5, TimeUnit.SECONDS)
                    assertArrayEquals(NativeEchTestData.config(), result.records.single().service.ech)
                    assertEquals(120_000L, result.records.single().ttlMillis)
                }
                server.finish()
            }
            assertTrue(socket.awaitTermination(5_000))
        }
    }

    @Test fun sniOnlyCertificateCannotReplaceDohServiceIdentityOverIpv6() {
        Ipv6Server(NativeDnsTestTls.server(listOf("cover.test")), 1, allowIoFailure = true) { peer, _ ->
            (peer as SSLSocket).startHandshake()
            peer.getInputStream().read()
        }.use { server ->
            val socket = socket()
            NativeNat64Transport(socket, NativeDnsClock { 100 }, testDialer(socket)).use { transport ->
                try {
                    transport.query(NativeDnsQuery(descriptor(server.port), "example.test", NativeDnsFamily.IPV6))
                        .get(5, TimeUnit.SECONDS)
                    fail("SNI-only certificate accepted")
                } catch (error: ExecutionException) {
                    val failures = (error.cause as NativeNat64QueryException).failures
                    assertTrue(failures.single() is SSLHandshakeException)
                }
                assertEquals(0, socket.state().resources)
            }
            assertTrue(socket.awaitTermination(5_000))
        }
    }

    @Test fun ipv6CancellationClosesOwnedTlsAndTcpResources() {
        val received = CountDownLatch(1)
        val eof = CountDownLatch(1)
        Ipv6Server(NativeDnsTestTls.server(), 1) { peer, port ->
            request(peer, port)
            received.countDown()
            try { assertEquals(-1, peer.getInputStream().read()) } catch (_: SSLException) { }
            eof.countDown()
        }.use { server ->
            val socket = socket()
            NativeNat64Transport(socket, NativeDnsClock { 100 }, testDialer(socket)).use { transport ->
                val pending = transport.query(NativeDnsQuery(descriptor(server.port), "example.test",
                    NativeDnsFamily.IPV6))
                assertTrue(received.await(5, TimeUnit.SECONDS))
                assertTrue(pending.cancel(true))
                assertTrue(eof.await(5, TimeUnit.SECONDS))
                assertEquals(0, socket.state().resources)
                assertEquals(0, transport.state().entries)
                server.finish()
            }
            assertTrue(socket.awaitTermination(5_000))
        }
    }

    @Test fun directPinnedInterfaceRejectsIpv4DialDestinationAndUsesNoEndpointResolver() {
        val socket = socket()
        try {
            val provider = DnsProviderDescriptor("local", "local", "https", "https://resolver.test/resolve")
            try {
                socket.queryNat64Doh(NativeNat64DohQuery(provider, "example.test",
                    NativeNat64Question.A, "127.0.0.1")).get(5, TimeUnit.SECONDS)
                fail()
            } catch (error: ExecutionException) { assertTrue(error.cause is IllegalArgumentException) }
            assertEquals(0, socket.state().resources)
        } finally { socket.close(); assertTrue(socket.awaitTermination(5_000)) }
    }

    @Test fun ipv6JsonBodyDeadlineClosesSlowPeerAndReleasesQuery() {
        val eof = CountDownLatch(1)
        Ipv6Server(NativeDnsTestTls.server(), 1) { peer, port ->
            request(peer, port)
            peer.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-json\r\n" +
                    "Content-Length: 100\r\n\r\n{").toByteArray()); flush()
            }
            try { assertEquals(-1, peer.getInputStream().read()) } catch (_: SSLException) { }
            eof.countDown()
        }.use { server ->
            val socket = SocketDnsTransport(NativeDnsEndpointResolver { error("No bootstrap") },
                NativeDnsTestTls.client, SocketDnsLimits(queryTimeoutMillis = 1_000))
            NativeNat64Transport(socket, NativeDnsClock { 100 }, testDialer(socket)).use { transport ->
                try {
                    transport.query(NativeDnsQuery(descriptor(server.port), "example.test", NativeDnsFamily.IPV6))
                        .get(5, TimeUnit.SECONDS)
                    fail()
                } catch (error: ExecutionException) {
                    assertTrue((error.cause as NativeNat64QueryException).failures.single() is java.net.SocketTimeoutException)
                }
                assertTrue(eof.await(5, TimeUnit.SECONDS))
                assertEquals(0, socket.state().resources)
                server.finish()
            }
            assertTrue(socket.awaitTermination(5_000))
        }
    }

    @Test fun literalIpv4ServiceStillVerifiesIpSanWhileDialingExplicitIpv6() {
        Ipv6Server(NativeDnsTestTls.server(ip = "192.0.2.53"), 1) { peer, port ->
            val head = Http1Reader(peer.getInputStream()).readRequest()!!.head
            assertEquals(listOf("192.0.2.53:$port"), head.headers.values("Host"))
            assertTrue(((peer as SSLSocket).session as ExtendedSSLSession).requestedServerNames.isEmpty())
            respond(peer, Nat64TestJson.answer())
        }.use { server ->
            val socket = socket()
            try {
                val provider = DnsProviderDescriptor("literal", "literal", "https",
                    "https://192.0.2.53:${server.port}/resolve")
                val answer = socket.queryNat64Doh(NativeNat64DohQuery(provider, "example.test",
                    NativeNat64Question.A, "::1")).get(5, TimeUnit.SECONDS) as NativeNat64DohAnswer.Addresses
                assertEquals("192.0.2.7", answer.answer.records.single().address)
                server.finish()
            } finally { socket.close(); assertTrue(socket.awaitTermination(5_000)) }
        }
    }

    @Test fun jsonCnameOnlyReplyTriggersRealFollowupAndLimitsFinalRecordTtl() {
        Ipv6Server(NativeDnsTestTls.server(), 2) { peer, _ ->
            val target = Http1Reader(peer.getInputStream()).readRequest()!!.head.target.raw
            val name = target.substringAfter('?').split('&')
                .first { it.substringBefore('=') == "name" }.substringAfter('=')
            if (name == "example.test") respond(peer, Nat64TestJson.answer(
                """{"name":"example.test.","type":5,"TTL":2,"data":"edge.test."}"""))
            else {
                assertEquals("edge.test", name)
                respond(peer, Nat64TestJson.answer(
                    """{"name":"edge.test.","type":1,"TTL":120,"data":"192.0.2.7"}""",
                    name = "edge.test."))
            }
        }.use { server ->
            val socket = socket()
            NativeNat64Transport(socket, NativeDnsClock { 100 }, testDialer(socket)).use { transport ->
                val result = transport.query(NativeDnsQuery(descriptor(server.port), "example.test",
                    NativeDnsFamily.IPV6)).get(5, TimeUnit.SECONDS)
                assertEquals(2_000, result.records.single().ttlMillis)
                server.finish()
            }
            assertTrue(socket.awaitTermination(5_000))
        }
    }
}

private class Ipv6Server(
    context: SSLContext, count: Int, private val allowIoFailure: Boolean = false,
    handler: (Socket, Int) -> Unit,
) : Closeable {
    private val listener: ServerSocket
    val port: Int get() = listener.localPort
    @Volatile private var active: Socket? = null
    @Volatile private var closing = false
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "nat64-ipv6-fixture").apply { isDaemon = true } }
    private val served: java.util.concurrent.Future<*>
    init {
        val address = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        listener = context.serverSocketFactory.createServerSocket()
        try {
            listener.bind(InetSocketAddress(address, 0))
        } catch (unavailable: IOException) {
            listener.close(); executor.shutdownNow()
            Assume.assumeNoException("LOCAL_IPV6_CAPABILITY_UNAVAILABLE: cannot bind ::1", unavailable)
            throw unavailable
        }
        listener.soTimeout = 5_000
        served = executor.submit {
            repeat(count) {
                if (!closing) try {
                    listener.accept().use { socket ->
                        active = socket; socket.soTimeout = 5_000
                        handler(socket, port)
                    }
                } catch (error: IOException) {
                    if (!closing && !allowIoFailure) throw error
                } finally { active = null }
            }
        }
    }
    fun finish() { served.get(5, TimeUnit.SECONDS) }
    override fun close() {
        closing = true; listener.close(); active?.close()
        executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS))
        served.get(5, TimeUnit.SECONDS)
    }
}

/** Reports JUnit assumptions distinctly so absent host IPv6 is never described as an exercised pass. */
object NativeNat64TestRunner {
    @JvmStatic fun main(args: Array<String>) {
        var skipped = 0
        val runner = JUnitCore()
        runner.addListener(object : RunListener() {
            override fun testAssumptionFailure(failure: Failure) {
                skipped++
                println("SKIPPED_CAPABILITY ${failure.description}: ${failure.message}")
            }
        })
        val result = runner.run(*args.map { Class.forName(it) }.toTypedArray())
        result.failures.forEach { System.err.println(it.trace) }
        println("JUnit: ${result.runCount} tests, ${result.failureCount} failures, $skipped capability skips")
        println("Exercised passes: ${result.runCount - result.failureCount - skipped}")
        if (!result.wasSuccessful()) kotlin.system.exitProcess(1)
    }
}
