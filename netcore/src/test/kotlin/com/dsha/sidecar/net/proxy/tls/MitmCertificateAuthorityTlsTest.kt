package com.dsha.sidecar.net.proxy.tls

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

class MitmCertificateAuthorityTlsTest {
    @Test fun `trusted localhost leaf completes TLS12 and TLS13 with HTTPS hostname verification`() {
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
            val leaf = LeafCertificateCache(fixture.authority()).get("localhost")
            exchange(leaf, fixture.certificate, "localhost", protocol)
        }
    }

    @Test fun `trusted IP SAN completes verified TLS12 and TLS13`() {
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
            exchange(fixture.authority().issue("127.0.0.1"), fixture.certificate, "127.0.0.1", protocol)
        }
    }

    @Test fun `trusted IPv6 SAN verified over loopback transport without IPv6 networking requirement`() {
        exchange(fixture.authority().issue("::1"), fixture.certificate, "::1", "TLSv1.3")
    }

    @Test fun `untrusted issuer fails ordinary trust validation`() {
        val unrelated = EphemeralTestCa.create()
        assertThrows(SSLHandshakeException::class.java) {
            exchange(fixture.authority().issue("localhost"), unrelated.certificate, "localhost", "TLSv1.3")
        }
    }

    @Test fun `trusted issuer with wrong DNS hostname fails HTTPS verification`() {
        assertThrows(SSLHandshakeException::class.java) {
            exchange(fixture.authority().issue("localhost"), fixture.certificate, "wrong.test", "TLSv1.3")
        }
    }

    @Test fun `trusted DNS SAN cannot impersonate an IP identity`() {
        assertThrows(SSLHandshakeException::class.java) {
            exchange(fixture.authority().issue("localhost"), fixture.certificate, "127.0.0.1", "TLSv1.2")
        }
    }

    @Test fun `trusted issuer with wrong IP SAN fails HTTPS verification`() {
        assertThrows(SSLHandshakeException::class.java) {
            exchange(fixture.authority().issue("127.0.0.2"), fixture.certificate, "127.0.0.1", "TLSv1.3")
        }
    }

    @Test fun `expired leaf fails real client validity verification`() {
        val past = Clock.fixed(EphemeralTestCa.referenceTime.minusSeconds(7200), ZoneOffset.UTC)
        val leaf = fixture.authority(past, leafLifetime = Duration.ofMinutes(10)).issue("localhost")
        assertThrows(SSLHandshakeException::class.java) {
            exchange(leaf, fixture.certificate, "localhost", "TLSv1.3")
        }
    }

    private fun exchange(leaf: LeafCertificate, trusted: X509Certificate, peerHost: String, protocol: String) {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("test-only-root", trusted)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        leaf.newServerSocket().use { server ->
            assertEquals(setOf("TLSv1.2", "TLSv1.3"), server.enabledProtocols.toSet())
            server.soTimeout = 5000
            server.bind(InetSocketAddress(loopback, 0))
            val worker = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ephemeral-ca-tls-test").apply { isDaemon = true }
            }
            val result = worker.submit(Callable {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        socket.startHandshake()
                        check(socket.inputStream.read() == 7)
                        socket.outputStream.write(9)
                        socket.outputStream.flush()
                    }
                    null
                } catch (failure: Exception) {
                    failure
                }
            })
            try {
                Socket().use { transport ->
                    transport.connect(InetSocketAddress(loopback, server.localPort), 5000)
                    (clientContext.socketFactory.createSocket(transport, peerHost, server.localPort, true) as SSLSocket).use { client ->
                        client.soTimeout = 5000
                        client.enabledProtocols = arrayOf(protocol)
                        client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        client.startHandshake()
                        assertEquals(protocol, client.session.protocol)
                        val chain = client.session.peerCertificates.map { it as X509Certificate }
                        assertEquals(2, chain.size)
                        assertEquals(leaf.certificate, chain[0])
                        assertEquals(trusted, chain[1])
                        chain[0].verify(trusted.publicKey)
                        client.outputStream.write(7)
                        client.outputStream.flush()
                        assertEquals(9, client.inputStream.read())
                    }
                }
                assertNull("Server must also finish its authenticated exchange", result.get(10, TimeUnit.SECONDS))
            } finally {
                server.close()
                result.get(10, TimeUnit.SECONDS)
                worker.shutdownNow()
            }
        }
    }

    companion object {
        private val fixture by lazy { EphemeralTestCa.create() }
    }
}
