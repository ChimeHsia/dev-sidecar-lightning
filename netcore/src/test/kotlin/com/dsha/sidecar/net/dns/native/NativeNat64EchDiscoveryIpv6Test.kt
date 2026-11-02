// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.Nat64Address
import com.dsha.sidecar.net.proxy.http.Http1Reader
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

class NativeNat64EchDiscoveryIpv6Test {
    private class Clock(var now: Long = 100) : NativeDnsClock { override fun nowMillis() = now }
    private class Harness(server: Server, context: SSLContext = NativeDnsTestTls.client) : AutoCloseable {
        val clock = Clock()
        val ordinary = AtomicInteger()
        val pinned = AtomicInteger()
        val socket = SocketDnsTransport(NativeDnsEndpointResolver {
            ordinary.incrementAndGet()
            error("No IPv4/system DNS bypass permitted")
        }, context)
        private val dialer = object : NativeNat64DohDialer {
            override fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer> {
                pinned.incrementAndGet()
                assertEquals(Nat64Address.toNat64Address("64:ff9b::/96", "192.0.2.53"), query.ipv6Destination)
                assertEquals(NativeNat64Question.HTTPS, query.type)
                assertEquals("cover.test", query.provider.sni)
                // Only this local fixture substitutes ::1 after verifying the synthesized route.
                return socket.queryNat64Doh(query.copy(ipv6Destination = "::1"))
            }
        }
        val nat64 = NativeNat64Transport(socket, clock, dialer,
            NativeNat64Limits(parallelDelayMillis = 0))
        private val providers = mapOf("local" to mapOf("server" to
            "https://resolver.test:${server.port}/resolve", "sni" to "cover.test"))
        fun discovery(ech: Map<String, Any?> = emptyMap()) = NativeNat64EchDiscovery.create(
            providers, NativeNat64EchDiscoveryTest.options(ech = ech), nat64, clock)
        override fun close() {
            nat64.close()
            assertTrue(nat64.awaitTermination(5_000))
            assertTrue(socket.awaitTermination(5_000))
            assertEquals(0, socket.state().resources)
            assertEquals(0, ordinary.get())
        }
    }

    private fun request(peer: Socket, port: Int): String {
        val head = Http1Reader(peer.getInputStream()).readRequest()!!.head
        assertEquals("GET", head.method)
        assertEquals(listOf("resolver.test:$port"), head.headers.values("Host"))
        assertEquals(listOf("application/dns-json"), head.headers.values("Accept"))
        val session = (peer as SSLSocket).session as ExtendedSSLSession
        assertEquals("cover.test", (session.requestedServerNames.single() as SNIHostName).asciiName)
        val params = head.target.raw.substringAfter('?').split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("HTTPS", params["type"])
        return params["name"]!!
    }
    private fun response(peer: Socket, json: String, status: String = "200 OK") {
        val body = json.toByteArray()
        peer.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: application/dns-json\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(body); flush()
        }
    }
    private fun service(name: String, bytes: ByteArray = NativeEchTestData.config(), ttl: Int = 120) =
        Nat64TestJson.answer(
            """{"name":"$name.","type":65,"TTL":$ttl,"data":"1 . ech=${Base64.getEncoder().encodeToString(bytes)}"}""",
            type = 65, name = "$name.")

    @Test fun automaticNat64DiscoveryRunsRealResolverVerifiedTlsJsonTransportAndFullConfigSelection() {
        Server { peer, port ->
            val name = request(peer, port)
            assertEquals("example.test", name)
            response(peer, service(name, NativeEchTestData.config("prefer-x25519-after-p256"), 7))
        }.use { server ->
            Harness(server).use { h ->
                h.discovery().use { discovery ->
                    val result = discovery.resolve("example.test").get(5, TimeUnit.SECONDS)!!
                    assertEquals("nat64", result.providerTransport)
                    assertEquals("public.example", result.publicName)
                    assertEquals(2, result.configs.size)
                    assertEquals(10, result.selectedConfig.contents!!.configId)
                    assertArrayEquals(NativeEchTestData.config("prefer-x25519-after-p256"), result.echConfigList)
                    assertEquals(7_000L, result.ttlMillis)
                    assertEquals(0, h.socket.state().resources)
                    assertEquals(1, h.pinned.get())
                    assertNotNull(discovery.resolve("example.test").get(5, TimeUnit.SECONDS))
                    assertEquals(1, h.pinned.get())
                    server.finish()
                }
            }
        }
    }

    @Test fun actualAliasModeOutsideDomainStaysOnNat64AndRetainsAliasTtlAndPublicName() {
        Server(count = 2) { peer, port ->
            val name = request(peer, port)
            if (name == "example.test") response(peer, Nat64TestJson.answer(
                """{"name":"example.test.","type":65,"TTL":2,"data":"0 alias.test."}""", type = 65))
            else {
                assertEquals("alias.test", name)
                response(peer, service(name))
            }
        }.use { server ->
            Harness(server).use { h ->
                h.discovery().use { discovery ->
                    val result = discovery.resolve("example.test").get(5, TimeUnit.SECONDS)!!
                    assertEquals("alias.test", result.queriedName)
                    assertEquals("public.example", result.publicName)
                    assertEquals(2_000L, result.ttlMillis)
                    assertEquals(2, h.pinned.get())
                    discovery.invalidate("alias.test")
                    assertEquals(0, discovery.state().entries)
                    server.finish()
                }
            }
        }
    }

    @Test fun cleanAbsenceUsesConfiguredSharedNameOverSameVerifiedIpv6Route() {
        Server(count = 2) { peer, port ->
            val name = request(peer, port)
            if (name == "example.test") response(peer, Nat64TestJson.answer("", type = 65))
            else {
                assertEquals("shared.test", name)
                response(peer, service(name))
            }
        }.use { server ->
            Harness(server).use { h ->
                h.discovery(mapOf("publicName" to "shared.test")).use { discovery ->
                    val result = discovery.resolve("example.test").get(5, TimeUnit.SECONDS)!!
                    assertTrue(result.shared)
                    assertEquals("public.example", result.publicName)
                    assertEquals("nat64", result.providerTransport)
                    assertEquals(2, h.pinned.get())
                    server.finish()
                }
            }
        }
    }

    @Test fun redirectIsFailureAndNeverTriggersOrdinaryIpv4OrSharedNameBypass() {
        Server { peer, port ->
            request(peer, port)
            peer.getOutputStream().write(("HTTP/1.1 302 Found\r\n" +
                "Location: https://unreachable.invalid/resolve\r\nContent-Length: 0\r\n\r\n").toByteArray())
        }.use { server ->
            Harness(server).use { h ->
                h.discovery(mapOf("publicName" to "shared.test")).use { discovery ->
                    try { discovery.resolve("example.test").get(5, TimeUnit.SECONDS); fail() }
                    catch (error: ExecutionException) { assertTrue(error.cause is NativeEchLookupException) }
                    assertEquals(1, h.pinned.get())
                    assertEquals(0, discovery.state().entries)
                    server.finish()
                }
            }
        }
    }

    @Test fun serviceIdentityCannotBeReplacedBySniMatchingCertificate() {
        Server(context = NativeDnsTestTls.server(listOf("cover.test")), allowIoFailure = true) { peer, _ ->
            (peer as SSLSocket).startHandshake()
            peer.getInputStream().read()
        }.use { server ->
            Harness(server).use { h ->
                h.discovery().use { discovery ->
                    try { discovery.resolve("example.test").get(5, TimeUnit.SECONDS); fail("Wrong service certificate accepted") }
                    catch (error: ExecutionException) {
                        assertTrue(error.cause is NativeEchLookupException)
                        val failures = (error.cause as NativeEchLookupException).failures
                        assertTrue(failures.any { failure ->
                            generateSequence(failure) { it.cause }.any { it is javax.net.ssl.SSLHandshakeException }
                        })
                    }
                    assertEquals(1, h.pinned.get())
                    assertEquals(0, discovery.state().entries)
                }
            }
        }
    }

    @Test fun defaultPlatformTrustRejectsLocalCaWithoutRetryingOrdinaryDns() {
        Server(allowIoFailure = true) { peer, _ -> (peer as SSLSocket).startHandshake() }.use { server ->
            Harness(server, SSLContext.getDefault()).use { h ->
                h.discovery().use { discovery ->
                    try { discovery.resolve("example.test").get(5, TimeUnit.SECONDS); fail() }
                    catch (error: ExecutionException) { assertTrue(error.cause is NativeEchLookupException) }
                    assertEquals(1, h.pinned.get())
                }
            }
        }
    }

    @Test fun discoveryCancellationReleasesResolverTransportAndTlsPeer() {
        val received = CountDownLatch(1)
        val eof = CountDownLatch(1)
        Server { peer, port ->
            request(peer, port); received.countDown()
            try { assertEquals(-1, peer.getInputStream().read()) } catch (_: SSLException) { }
            eof.countDown()
        }.use { server ->
            Harness(server).use { h ->
                h.discovery().use { discovery ->
                    val first = discovery.resolve("example.test")
                    val second = discovery.resolve("example.test")
                    assertTrue(received.await(5, TimeUnit.SECONDS))
                    first.cancel(true)
                    assertEquals(1, discovery.state().waiters)
                    assertFalse(second.isDone)
                    second.cancel(true)
                    assertTrue(eof.await(5, TimeUnit.SECONDS))
                    assertEquals(0, discovery.state().flights)
                    assertEquals(0, h.nat64.state().flights)
                    assertEquals(0, h.socket.state().resources)
                    server.finish()
                }
            }
        }
    }

    @Test fun malformedFullConfigFailsThroughRealWirePathWithoutCachingOrFallback() {
        Server { peer, port -> response(peer, service(request(peer, port), byteArrayOf(0, 0))) }.use { server ->
            Harness(server).use { h ->
                h.discovery(mapOf("publicName" to "shared.test")).use { discovery ->
                    try { discovery.resolve("example.test").get(5, TimeUnit.SECONDS); fail() }
                    catch (error: ExecutionException) { assertTrue(error.cause is NativeEchLookupException) }
                    assertEquals(1, h.pinned.get())
                    assertEquals(0, discovery.state().entries)
                    server.finish()
                }
            }
        }
    }
}

private class Server(
    context: SSLContext = NativeDnsTestTls.server(),
    count: Int = 1,
    private val allowIoFailure: Boolean = false,
    handler: (Socket, Int) -> Unit,
) : Closeable {
    private val listener: ServerSocket = context.serverSocketFactory.createServerSocket()
    val port: Int get() = listener.localPort
    @Volatile private var active: Socket? = null
    @Volatile private var closing = false
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "nat64-auto-ech-localhost").apply { isDaemon = true }
    }
    private val serving: java.util.concurrent.Future<*>
    init {
        try {
            listener.bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 }), 0))
        } catch (unavailable: IOException) {
            listener.close(); executor.shutdownNow()
            Assume.assumeNoException("LOCAL_IPV6_CAPABILITY_UNAVAILABLE: cannot bind ::1", unavailable)
            throw unavailable
        }
        listener.soTimeout = 5_000
        serving = executor.submit {
            repeat(count) {
                if (!closing) try {
                    listener.accept().use {
                        active = it; it.soTimeout = 5_000
                        handler(it, port)
                    }
                } catch (error: IOException) {
                    if (!closing && !allowIoFailure) throw error
                } finally { active = null }
            }
        }
    }
    fun finish() { serving.get(5, TimeUnit.SECONDS) }
    override fun close() {
        closing = true; listener.close(); active?.close(); executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
        serving.get(5, TimeUnit.SECONDS)
    }
}
