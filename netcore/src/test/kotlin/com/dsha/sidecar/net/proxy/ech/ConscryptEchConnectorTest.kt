package com.dsha.sidecar.net.proxy.ech

import org.conscrypt.Conscrypt
import org.conscrypt.NativeEchPolicyAdapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class ConscryptEchConnectorTest {
    private fun proof() = ConscryptEchConnector()

    @Test
    fun officialArtifactVersionNativeAvailabilityAndExplicitContext() {
        Conscrypt.checkAvailability()
        val version = Conscrypt.version()
        assertEquals(2, version.major())
        assertEquals(8, version.minor())
        assertEquals(0, version.patch())
        assertTrue(Conscrypt.isConscrypt(ConscryptEchConnector.newExplicitContext(EchPeerTrust.SystemRoots.manager())))
    }

    @Test
    fun setterAloneSilentlyCompletesOrdinaryTlsWithNoEch() {
        Peer("accepted").use { peer ->
            val context = ConscryptEchConnector.newExplicitContext(peer.trust.manager())
            val factory = context.socketFactory
            Conscrypt.setUseEngineSocket(factory, true)
            val tls = factory.createSocket(peer.raw(), "inner.example", peer.port, true) as SSLSocket
            tls.use {
                it.soTimeout = 2000
                it.useClientMode = true
                it.enabledProtocols = arrayOf("TLSv1.3")
                Conscrypt.setHostname(it, "inner.example")
                Conscrypt.setEchConfigList(it, peer.config.wire)
                it.startHandshake()
                exchange(it)
            }
            val result = peer.result()
            assertFalse(result.echAccepted)
            assertEquals("inner.example", result.serverName)
            assertEquals(5, result.applicationBytes)
            evidence("setter-alone", result)
        }
    }

    @Test
    fun requiredAdapterRejectsUnusableConfigInsteadOfPlainTlsFallback() {
        Peer("accepted").use { peer ->
            val raw = peer.raw()
            val unsupported = byteArrayOf(0, 4, 1, 2, 0, 0)
            val manager = NativeEchPolicyAdapter.requireEch(peer.trust.manager(), unsupported, "inner.example")
            val factory = ConscryptEchConnector.newExplicitContext(manager).socketFactory
            Conscrypt.setUseEngineSocket(factory, true)
            val socket = factory.createSocket(raw, "inner.example", peer.port, true) as SSLSocket
            socket.use {
                it.useClientMode = true
                it.enabledProtocols = arrayOf("TLSv1.3")
                Conscrypt.setHostname(it, "inner.example")
                Conscrypt.setEchConfigList(it, unsupported)
                assertThrows(javax.net.ssl.SSLException::class.java) { it.startHandshake() }
            }
            assertTrue(raw.isClosed)
            val result = peer.result()
            assertFalse(result.echAccepted)
            assertEquals("", result.serverName)
            assertEquals(0, result.applicationBytes)
            evidence("required-unusable-config", result)
        }
    }

    @Test
    fun productionPinnedAdapterNegotiatesEchAndExchangesDataWithIndependentGo() {
        Peer("accepted").use { peer ->
            proof().connect(peer.raw(), peer.config, "inner.example", peerTrust = peer.trust).use {
                assertEquals(EchStatus.ACCEPTED_WITH_PINNED_REQUIRED_POLICY, it.echStatus)
                assertTrue(Conscrypt.isConscrypt(it.socket))
                assertEquals("TLSv1.3", it.socket.session.protocol)
                assertEquals("http/1.1", Conscrypt.getApplicationProtocol(it.socket))
                exchange(it.socket)
            }
            val result = peer.result()
            assertTrue(result.echAccepted)
            assertEquals("inner.example", result.serverName)
            assertEquals(5, result.applicationBytes)
            evidence("pinned-required-accepted", result)
        }
    }

    @Test
    fun strictVerificationHostMayDifferFromInnerSni() {
        Peer("accepted").use { peer ->
            proof().connect(peer.raw(), peer.config, "inner.example", "verify.example", peer.trust).use {
                exchange(it.socket)
            }
            val result = peer.result()
            assertTrue(result.echAccepted)
            assertEquals("inner.example", result.serverName)
            assertEquals(5, result.applicationBytes)
            evidence("separate-verify-host", result)
        }
    }

    @Test
    fun echKeyMismatchRejectionDoesNotReturnSocketOrSendApplicationData() {
        rejected("reject", EchFailure.ECH_REJECTED)
    }

    @Test
    fun serverWithoutEchFailsClosed() {
        rejected("noech", EchFailure.ECH_REJECTED)
    }

    @Test
    fun corruptedEncryptedClientHelloPayloadFailsClosed() {
        val result = rejected("tamper", null)
        assertTrue(result.tampered)
        assertFalse(result.echAccepted)
    }

    @Test
    fun encryptedServerRecordTamperingFailsClosed() {
        val result = rejected("record-tamper", EchFailure.HANDSHAKE)
        assertTrue(result.tampered)
    }

    @Test
    fun wrongCertificateIdentityFailsClosed() {
        rejected("wrong-host", EchFailure.PEER_AUTHENTICATION)
    }

    @Test
    fun expiredCertificateFailsClosed() {
        rejected("expired", EchFailure.PEER_AUTHENTICATION)
    }

    @Test
    fun systemRootsDefaultRejectsEphemeralRoot() {
        Peer("accepted").use { peer ->
            val raw = peer.raw()
            val failure = assertThrows(EchConnectionException::class.java) {
                proof().connect(raw, peer.config, "inner.example")
            }
            assertEquals(EchFailure.PEER_AUTHENTICATION, failure.failure)
            assertTrue(raw.isClosed)
            val result = peer.result()
            assertEquals(0, result.applicationBytes)
            evidence("untrusted-root", result)
        }
    }

    @Test
    fun separateWrongVerifyHostRejectsEvenWhenChainAndInnerSniAreValid() {
        Peer("accepted").use { peer ->
            val failure = assertThrows(EchConnectionException::class.java) {
                proof().connect(peer.raw(), peer.config, "inner.example", "missing.example", peer.trust)
            }
            assertEquals(EchFailure.PEER_AUTHENTICATION, failure.failure)
            val result = peer.result()
            assertEquals(0, result.applicationBytes)
            evidence("wrong-separate-verify-host", result)
        }
    }

    @Test
    fun handshakeDeadlineClosesRawTransport() {
        Peer("stall").use { peer ->
            val raw = peer.raw()
            val start = System.nanoTime()
            val failure = assertThrows(EchConnectionException::class.java) {
                proof().connect(raw, peer.config, "inner.example", peerTrust = peer.trust, handshakeTimeoutMillis = 200)
            }
            assertEquals(EchFailure.TIMEOUT, failure.failure)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
            assertTrue(raw.isClosed)
            val result = peer.result()
            assertEquals(0, result.applicationBytes)
            evidence("deadline", result)
        }
    }

    @Test
    fun cancellationDuringHandshakeClosesRawTransport() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            Peer("stall").use { peer ->
                val token = EchCancellation()
                val raw = peer.raw()
                executor.schedule({ token.cancel() }, 150, TimeUnit.MILLISECONDS)
                val failure = assertThrows(EchConnectionException::class.java) {
                    proof().connect(raw, peer.config, "inner.example", peerTrust = peer.trust, cancellation = token)
                }
                assertEquals(EchFailure.CANCELLED, failure.failure)
                assertTrue(raw.isClosed)
                val result = peer.result()
                assertEquals(0, result.applicationBytes)
                evidence("cancel", result)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun preCancelledRequestSendsNoClientHello() {
        Peer("accepted").use { peer ->
            val cancellation = EchCancellation().apply { cancel() }
            val failure = assertThrows(EchConnectionException::class.java) {
                proof().connect(peer.raw(), peer.config, "inner.example", peerTrust = peer.trust, cancellation = cancellation)
            }
            assertEquals(EchFailure.CANCELLED, failure.failure)
            val result = peer.result()
            assertEquals("", result.serverName)
            assertEquals(0, result.applicationBytes)
        }
    }

    @Test
    fun completingConnectDetachesCancellation() {
        Peer("accepted").use { peer ->
            val token = EchCancellation()
            proof().connect(peer.raw(), peer.config, "inner.example", peerTrust = peer.trust, cancellation = token).use {
                token.cancel()
                exchange(it.socket)
            }
            assertEquals(5, peer.result().applicationBytes)
        }
    }

    @Test
    fun noGlobalProviderContextOrHttpsDefaultsAreChanged() {
        val providers = Security.getProviders().toList()
        val defaultContext = SSLContext.getDefault()
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        Peer("accepted").use { peer ->
            proof().connect(peer.raw(), peer.config, "inner.example", peerTrust = peer.trust).use {
                exchange(it.socket)
            }
            assertTrue(peer.result().echAccepted)
        }
        assertEquals(providers, Security.getProviders().toList())
        assertSame(defaultContext, SSLContext.getDefault())
        assertSame(defaultFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(defaultVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }

    @Test
    fun typedConfigInputAndReturnedWireAreDetached() {
        Peer("accepted").use { peer ->
            val input = peer.config.wire
            val copy = input.copyOf()
            val parsed = TypedEchConfigList.parse(input)
            input.fill(0)
            parsed.wire.fill(0)
            (parsed.configs as MutableList).clear()
            assertArrayEquals(copy, parsed.wire)
            assertEquals(1, parsed.configs.size)
            proof().connect(peer.raw(), parsed, "inner.example", peerTrust = peer.trust).use { exchange(it.socket) }
            assertTrue(peer.result().echAccepted)
        }
    }

    @Test
    fun malformedAndUnsupportedConfigListsAreRejectedBeforeConnect() {
        for (wire in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(0, 4, 1, 2, 0, 0))) {
            assertThrows(IllegalArgumentException::class.java) { TypedEchConfigList.parse(wire) }
        }
    }

    @Test
    fun invalidHostsAndTimeoutsCloseOwnedSocket() {
        for (host in listOf("", "127.0.0.1", "*.example", "bad..example", "é.example")) {
            Peer("accepted").use { peer ->
                val raw = peer.raw()
                val failure = assertThrows(EchConnectionException::class.java) {
                    proof().connect(raw, peer.config, host, peerTrust = peer.trust)
                }
                assertEquals(EchFailure.INVALID_REQUEST, failure.failure)
                assertTrue(raw.isClosed)
                assertEquals("", peer.result().serverName)
            }
        }
        Peer("accepted").use { peer ->
            val failure = assertThrows(EchConnectionException::class.java) {
                proof().connect(peer.raw(), peer.config, "inner.example", peerTrust = peer.trust, handshakeTimeoutMillis = 0)
            }
            assertEquals(EchFailure.INVALID_REQUEST, failure.failure)
            assertEquals("", peer.result().serverName)
        }
    }

    @Test
    fun concurrentIndependentConnectionsHaveSeparateContextsAndNoFallback() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = pool.invokeAll(List(3) {
                Callable {
                    Peer("accepted").use { peer ->
                        proof().connect(peer.raw(), peer.config, "inner.example", peerTrust = peer.trust).use {
                            exchange(it.socket)
                        }
                        peer.result()
                    }
                }
            }).map { it.get(12, TimeUnit.SECONDS) }
            assertTrue(results.all { it.echAccepted && it.applicationBytes == 5 })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun rejected(mode: String, expected: EchFailure?): PeerResult {
        Peer(mode).use { peer ->
            val raw = peer.raw()
            val failure = assertThrows(EchConnectionException::class.java) {
                proof().connect(raw, peer.config, "inner.example", peerTrust = peer.trust)
            }
            if (expected != null) assertEquals("mode $mode; cause=${failure.cause}", expected, failure.failure)
            assertTrue(raw.isClosed)
            val result = peer.result()
            assertEquals(0, result.applicationBytes)
            evidence(mode, result, failure.failure)
            return result
        }
    }

    private fun exchange(socket: SSLSocket) {
        socket.soTimeout = 2000
        socket.outputStream.write("PING\n".toByteArray(Charsets.US_ASCII))
        socket.outputStream.flush()
        val reply = ByteArray(5)
        var offset = 0
        while (offset < reply.size) {
            val count = socket.inputStream.read(reply, offset, reply.size - offset)
            check(count > 0) { "Peer closed before reply" }
            offset += count
        }
        assertArrayEquals("PONG\n".toByteArray(Charsets.US_ASCII), reply)
    }

    companion object {
        @Synchronized
        private fun evidence(name: String, result: PeerResult, failure: EchFailure? = null) {
            val output = File(System.getProperty("ech.proof.output"))
            output.appendText("$name echAccepted=${result.echAccepted} serverName=${result.serverName} " +
                "applicationBytes=${result.applicationBytes} tampered=${result.tampered} failure=$failure\n")
        }
    }
}

private data class PeerResult(
    val echAccepted: Boolean,
    val applicationBytes: Int,
    val serverName: String,
    val tampered: Boolean
)

/** Consumes only public config and root-cert stdout. Private keys remain in the Go peer's memory. */
private class Peer(mode: String) : AutoCloseable {
    private val process = ProcessBuilder(System.getProperty("ech.peer.exe"), "-mode", mode)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val reader = process.inputStream.bufferedReader()
    private val lines = Executors.newSingleThreadExecutor()
    private val ready = line()
    val port = number(ready, "port")
    val config = TypedEchConfigList.parse(Base64.getDecoder().decode(text(ready, "config_b64")))
    private val root = CertificateFactory.getInstance("X.509")
        .generateCertificate(Base64.getDecoder().decode(text(ready, "ca_b64")).inputStream()) as X509Certificate
    val trust = EchPeerTrust.TrustAnchors(listOf(root))

    fun raw(): Socket = Socket().apply {
        connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), this@Peer.port), 1000)
    }

    fun result(): PeerResult {
        val result = line()
        check(text(result, "event") == "result")
        check(process.waitFor(3, TimeUnit.SECONDS)) { "Go peer did not exit" }
        check(process.exitValue() == 0) { "Go peer failed" }
        return PeerResult(
            bool(result, "ech_accepted"), number(result, "application_bytes"),
            text(result, "server_name"), bool(result, "tampered")
        )
    }

    private fun line(): String = lines.submit(Callable { reader.readLine() })
        .get(10, TimeUnit.SECONDS) ?: error("Go peer exited without event")

    override fun close() {
        if (process.isAlive) process.destroyForcibly()
        lines.shutdownNow()
        reader.close()
    }

    private fun text(json: String, key: String): String =
        Regex("\"$key\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: error("Missing $key")

    private fun number(json: String, key: String): Int =
        Regex("\"$key\":([0-9]+)").find(json)?.groupValues?.get(1)?.toInt() ?: error("Missing $key")

    private fun bool(json: String, key: String): Boolean = json.contains("\"$key\":true")
}
