package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.ech.EchConnectionException
import com.dsha.sidecar.net.proxy.ech.EchFailure
import com.dsha.sidecar.net.proxy.ech.EchPeerTrust
import com.dsha.sidecar.net.proxy.ech.TypedEchConfigList
import com.dsha.sidecar.net.proxy.http.Http1HeadCodec
import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import com.dsha.sidecar.net.proxy.tls.LeafCertificateCache
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class ConscryptNativeTlsDialerTest {
    @Test
    fun independentHttpPeerClosesTlsCleanlyForAndroidReadToEofProbe() {
        HttpPeer("accepted").use { peer ->
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.Required(peer.config) },
                NativeEchTrustSupplier { peer.trust }
            )
            dialer.connect(peer.raw(), NativeTlsPolicy("verify.example", "inner.example"), 2000).use { socket ->
                socket.soTimeout = 3000
                socket.outputStream.write(
                    "GET /native/android-ech-proof?q=24 HTTP/1.1\r\nHost: inner.example\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                val response = socket.inputStream.readBytes().toString(Charsets.US_ASCII)
                assertTrue(response.startsWith("HTTP/1.1 200 "))
                assertEquals(
                    "GO-ECH GET /native/android-ech-proof?q=24 HTTP/1.1\r\n",
                    response.substringAfter("\r\n\r\n")
                )
            }
            val result = peer.result()
            assertTrue(result.ech)
            assertEquals("GET /native/android-ech-proof?q=24 HTTP/1.1\r\n", result.requestLine)
            record("android-http-read-to-eof-host-proof", result)
        }
    }

    @Test
    fun verifiedBrowserConnectThroughMitmAndConscryptToIndependentGoOrigin() {
        HttpPeer("accepted").use { peer ->
            val seen = AtomicReference<NativeEchRouteKey>()
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { key -> seen.set(key); NativeEchSelection.Required(peer.config) },
                NativeEchTrustSupplier { peer.trust }
            )
            proxy(peer, dialer).use { proxy ->
                browser(proxy).use { browser ->
                    val (status, body) = get(browser)
                    assertEquals(200, status)
                    assertEquals("GO-ECH GET /native/ech-proof?q=2 HTTP/1.1\r\n", body)
                }
            }
            assertEquals(NativeEchRouteKey("inner.example", "verify.example", peer.port), seen.get())
            val result = peer.result()
            assertTrue(result.ech)
            assertEquals("inner.example", result.sni)
            assertEquals("GET /native/ech-proof?q=2 HTTP/1.1\r\n", result.requestLine)
            assertTrue(result.bytes > 0)
            record("connect-mitm-ech-go-http", result)
        }
    }

    @Test
    fun explicitNotRequiredUsesStrictPlatformTlsWithoutClaimingEch() {
        HttpPeer("noech").use { peer ->
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.NotRequired },
                NativeEchTrustSupplier { error("ECH trust supplier must not run") }
            )
            val policy = NativeTlsPolicy("verify.example", "inner.example", trustContext = trust(peer.root))
            proxy(peer, dialer, policy).use { proxy ->
                browser(proxy).use { browser -> assertEquals(200, get(browser).first) }
            }
            val result = peer.result()
            assertFalse(result.ech)
            assertTrue(result.bytes > 0)
            record("explicit-no-ech-strict-platform", result)
        }
    }

    @Test fun echRejectionBecomes502WithZeroOriginApplicationBytes() =
        failureThroughProxy("reject", EchFailure.ECH_REJECTED)

    @Test fun noEchServerCannotCauseFallback() =
        failureThroughProxy("noech", EchFailure.ECH_REJECTED)

    @Test fun wrongOriginCertificateBecomes502BeforeForwardingRequest() =
        failureThroughProxy("wrong-host", EchFailure.PEER_AUTHENTICATION)

    @Test fun expiredOriginCertificateBecomes502BeforeForwardingRequest() =
        failureThroughProxy("expired", EchFailure.PEER_AUTHENTICATION)

    @Test fun encryptedRecordTamperCannotDeliverRequest() =
        failureThroughProxy("record-tamper", EchFailure.HANDSHAKE)

    @Test fun encryptedClientHelloTamperCannotDeliverRequest() =
        failureThroughProxy("tamper", null)

    @Test fun handshakeTimeoutCannotDeliverRequest() =
        failureThroughProxy("stall", null, timeout = 200)

    @Test
    fun defaultSystemRootsCannotTrustPeerEphemeralCa() {
        HttpPeer("accepted").use { peer ->
            val dialer = ConscryptNativeTlsDialer(NativeEchConfigResolver { NativeEchSelection.Required(peer.config) })
            val failure = assertThrows(EchConnectionException::class.java) {
                dialer.connect(peer.raw(), NativeTlsPolicy("verify.example", "inner.example"), 2000)
            }
            assertEquals(EchFailure.PEER_AUTHENTICATION, failure.failure)
            assertEquals(0, peer.result().bytes)
        }
    }

    @Test
    fun missingRequiredConfigIsAnExplicitTerminalFailure() {
        for (mode in UpstreamTrustMode.entries) {
            val raw = Socket()
            val dialer = ConscryptNativeTlsDialer(NativeEchConfigResolver { NativeEchSelection.RequiredButUnavailable })
            val failure = assertThrows(NativeEchDialException::class.java) {
                dialer.connect(raw, NativeTlsPolicy("inner.example", trustMode = mode), 1000)
            }
            assertEquals(NativeEchDialFailure.REQUIRED_CONFIG_UNAVAILABLE, failure.failure)
            assertTrue(raw.isClosed)
        }
    }

    @Test
    fun resolverFailureCannotBeReinterpretedAsNoEch() {
        val raw = Socket()
        val dialer = ConscryptNativeTlsDialer(NativeEchConfigResolver { error("configuration unavailable") })
        val failure = assertThrows(NativeEchDialException::class.java) {
            dialer.connect(raw, NativeTlsPolicy("inner.example"), 1000)
        }
        assertEquals(NativeEchDialFailure.CONFIG_RESOLUTION, failure.failure)
        assertTrue(raw.isClosed)
    }

    @Test
    fun requiredEchRejectsUnverifiedAndChainOnlyBeforeHandshake() {
        for (mode in listOf(UpstreamTrustMode.UNVERIFIED, UpstreamTrustMode.CHAIN_ONLY)) {
            requiredPolicyFailure(
                NativeTlsPolicy("inner.example", trustMode = mode), NativeEchDialFailure.UNSUPPORTED_TRUST_POLICY
            )
        }
    }

    @Test
    fun notRequiredExplicitUnverifiedPolicyDelegatesOnlyToOrdinaryTls() {
        HttpPeer("wrong-host").use { peer ->
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.NotRequired },
                NativeEchTrustSupplier { error("Non-ECH must not request ECH trust") }
            )
            val policy = NativeTlsPolicy("verify.example", "inner.example", trustMode = UpstreamTrustMode.UNVERIFIED)
            proxy(peer, dialer, policy).use { proxy ->
                browser(proxy).use { assertEquals(200, get(it).first) }
            }
            val result = peer.result()
            assertFalse(result.ech)
            assertTrue(result.bytes > 0)
            record("explicit-non-ech-unverified", result)
        }
    }

    @Test
    fun notRequiredExplicitChainOnlyPolicyRetainsItsTrustContext() {
        HttpPeer("wrong-host").use { peer ->
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.NotRequired },
                NativeEchTrustSupplier { error("Non-ECH must not request ECH trust") }
            )
            val policy = NativeTlsPolicy(
                "verify.example", "inner.example", UpstreamTrustMode.CHAIN_ONLY, trust(peer.root)
            )
            proxy(peer, dialer, policy).use { proxy ->
                browser(proxy).use { assertEquals(200, get(it).first) }
            }
            val result = peer.result()
            assertFalse(result.ech)
            assertTrue(result.bytes > 0)
            record("explicit-non-ech-chain-only", result)
        }
    }

    @Test
    fun opaqueTrustContextCannotBeSilentlyReplacedByEchAnchors() {
        requiredPolicyFailure(
            NativeTlsPolicy("verify.example", "inner.example", trustContext = SSLContext.getDefault()),
            NativeEchDialFailure.UNSUPPORTED_TRUST_POLICY
        )
    }

    @Test
    fun echCannotOverrideTls12OnlyRoute() {
        requiredPolicyFailure(
            NativeTlsPolicy("verify.example", "inner.example", protocols = listOf("TLSv1.2")),
            NativeEchDialFailure.UNSUPPORTED_PROTOCOL_POLICY
        )
    }

    @Test
    fun echCannotInventAnInnerSniForSuppressedSniPolicy() {
        requiredPolicyFailure(NativeTlsPolicy("verify.example", sniHost = null), NativeEchDialFailure.MISSING_INNER_HOST)
    }

    @Test
    fun engineStopCancelsStalledConscryptHandshake() {
        HttpPeer("stall").use { peer ->
            val entered = CountDownLatch(1)
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { entered.countDown(); NativeEchSelection.Required(peer.config) },
                NativeEchTrustSupplier { peer.trust }
            )
            val proxy = proxy(peer, dialer)
            val executor = Executors.newSingleThreadExecutor()
            try {
                browser(proxy).use { browser ->
                    val result = executor.submit(Callable { runCatching { get(browser) } })
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    proxy.close()
                    result.get(4, TimeUnit.SECONDS)
                    assertEquals(0, proxy.activeSocketCount)
                }
                val result = peer.result()
                assertEquals(0, result.bytes)
                record("engine-stop", result)
            } finally {
                proxy.close()
                executor.shutdownNow()
            }
        }
    }

    private fun requiredPolicyFailure(policy: NativeTlsPolicy, expected: NativeEchDialFailure) {
        HttpPeer("accepted").use { peer ->
            val dialer = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.Required(peer.config) },
                NativeEchTrustSupplier { error("Unsupported policy must not fetch trust") }
            )
            val failure = assertThrows(NativeEchDialException::class.java) {
                dialer.connect(peer.raw(), policy, 1000)
            }
            assertEquals(expected, failure.failure)
            val result = peer.result()
            assertEquals(0, result.bytes)
            assertEquals("", result.sni)
            assertFalse(result.ech)
        }
    }

    private fun failureThroughProxy(mode: String, expected: EchFailure?, timeout: Int = 2000) {
        HttpPeer(mode).use { peer ->
            val failure = AtomicReference<Throwable>()
            val composite = ConscryptNativeTlsDialer(
                NativeEchConfigResolver { NativeEchSelection.Required(peer.config) },
                NativeEchTrustSupplier { peer.trust }
            )
            val dialer = NativeTlsDialer { raw, policy, millis ->
                try { composite.connect(raw, policy, millis) } catch (error: Exception) {
                    failure.set(error)
                    throw error
                }
            }
            proxy(peer, dialer, timeout = timeout).use { proxy ->
                browser(proxy).use { browser -> assertEquals(if (mode == "stall") 504 else 502, get(browser).first) }
            }
            assertNotNull(failure.get())
            if (expected != null) assertEquals(expected, (failure.get() as EchConnectionException).failure)
            val result = peer.result()
            assertEquals(0, result.bytes)
            record("mitm-$mode", result)
        }
    }

    private fun proxy(
        peer: HttpPeer,
        dialer: NativeTlsDialer,
        policy: NativeTlsPolicy = NativeTlsPolicy("verify.example", "inner.example"),
        timeout: Int = 2000
    ): NativeProxyServer = NativeProxyServer(
        RouteResolver { NativeProxyRoute(peer.address, tls = policy) }, leaves,
        NativeProxyOptions(handshakeTimeoutMillis = timeout, readTimeoutMillis = 4000, workers = 1),
        tlsConnector = dialer
    ).apply { start() }

    private fun browser(proxy: NativeProxyServer): SSLSocket {
        val raw = Socket().apply { connect(proxy.localAddress); soTimeout = 4000 }
        raw.outputStream.write("CONNECT browser.test:443 HTTP/1.1\r\nHost: browser.test:443\r\n\r\n".toByteArray())
        assertEquals(200, Http1HeadCodec.readResponse(raw.inputStream, "CONNECT")!!.head.status)
        return (browserTrust.socketFactory.createSocket(raw, "browser.test", 443, true) as SSLSocket).apply {
            soTimeout = 5000
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
            val certificate = session.peerCertificates.first() as X509Certificate
            assertTrue(certificate.subjectAlternativeNames.any { it[0] == 2 && it[1] == "browser.test" })
        }
    }

    private fun get(socket: SSLSocket): Pair<Int, String> {
        socket.outputStream.write(
            "GET /native/ech-proof?q=2 HTTP/1.1\r\nHost: browser.test\r\nConnection: close\r\n\r\n".toByteArray()
        )
        socket.outputStream.flush()
        val reader = Http1Reader(socket.inputStream)
        val response = checkNotNull(reader.readResponse("GET"))
        val body = ByteArrayOutputStream()
        reader.transferBody(body)
        return response.head.status to body.toString("UTF-8")
    }

    companion object {
        private val ca = EphemeralTestCa.create()
        private val leaves = LeafCertificateCache(ca.authority())
        private val browserTrust = trust(ca.certificate)

        private fun trust(certificate: X509Certificate): SSLContext {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("test-root", certificate)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }

        private fun record(scenario: String, result: HttpPeer.Result) {
            File(System.getProperty("ech.integration.output")).appendText(
                "$scenario echAccepted=${result.ech} sni=${result.sni} applicationBytes=${result.bytes} " +
                    "request=${result.requestLine.trim()}\n"
            )
        }
    }
}

internal class HttpPeer(mode: String) : AutoCloseable {
    private val process = ProcessBuilder(System.getProperty("ech.http.peer.exe"), "-mode", mode)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = process.inputStream.bufferedReader()
    private val executor = Executors.newSingleThreadExecutor()
    private fun line(): String = executor.submit(Callable { input.readLine() }).get(12, TimeUnit.SECONDS)
        ?: error("Independent Go HTTP peer exited")
    private val ready = line()
    val port = number(ready, "port")
    val address = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    val config = TypedEchConfigList.parse(Base64.getDecoder().decode(text(ready, "config_b64")))
    val root = CertificateFactory.getInstance("X.509").generateCertificate(
        Base64.getDecoder().decode(text(ready, "ca_b64")).inputStream()
    ) as X509Certificate
    val trust = EchPeerTrust.TrustAnchors(listOf(root))
    fun raw(): Socket = Socket().apply { connect(address, 1000) }
    data class Result(val ech: Boolean, val bytes: Int, val sni: String, val requestLine: String)
    fun result(): Result {
        val result = line()
        check(process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0)
        return Result(
            result.contains("\"ech_accepted\":true"), number(result, "application_bytes"),
            text(result, "server_name"), text(result, "request_line").replace("\\r", "\r").replace("\\n", "\n")
        )
    }
    override fun close() {
        if (process.isAlive) process.destroyForcibly()
        executor.shutdownNow()
        input.close()
    }
    private fun text(json: String, key: String): String =
        Regex("\"$key\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
    private fun number(json: String, key: String): Int =
        Regex("\"$key\":([0-9]+)").find(json)?.groupValues?.get(1)?.toInt() ?: error("Missing $key")
}
