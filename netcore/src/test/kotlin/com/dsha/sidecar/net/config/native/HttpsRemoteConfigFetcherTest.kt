package com.dsha.sidecar.net.config.native

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class HttpsRemoteConfigFetcherTest {
    companion object {
        private lateinit var directory: java.nio.file.Path
        private lateinit var serverContext: SSLContext
        private lateinit var clientContext: SSLContext

        @JvmStatic @BeforeClass fun certificate() {
            directory = Files.createTempDirectory("native-config-https-fixture")
            val store = directory.resolve("synthetic.p12")
            val executable = java.io.File(System.getProperty("java.home"), "bin/keytool.exe")
            val keytool = if (executable.exists()) executable else java.io.File(System.getProperty("java.home"), "bin/keytool")
            val process = ProcessBuilder(
                keytool.absolutePath, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "fixturepass", "-keypass", "fixturepass", "-noprompt",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
            val keys = KeyStore.getInstance("PKCS12")
            Files.newInputStream(store).use { keys.load(it, "fixturepass".toCharArray()) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(keys, "fixturepass".toCharArray())
            serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(keys)
            clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }

        @JvmStatic @AfterClass fun removeCertificate() {
            if (::directory.isInitialized) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    private lateinit var server: HttpsServer
    private lateinit var handlers: java.util.concurrent.ExecutorService
    private lateinit var base: String
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val peerClosed = CountDownLatch(1)

    @Before fun start() {
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverContext)
        handlers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "synthetic-https").apply { isDaemon = true } }
        server.executor = handlers
        base = "https://localhost:${server.address.port}"
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            try {
                when (path) {
                    "/ok" -> send(exchange, 200, "{server:{port:4321}}".toByteArray())
                    "/status" -> send(exchange, 404, "secret body".toByteArray())
                    "/redirect" -> {
                        exchange.responseHeaders.add("Location", "/ok")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/loop" -> {
                        exchange.responseHeaders.add("Location", "/loop")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/chain" -> {
                        val step = exchange.requestURI.rawQuery?.toIntOrNull() ?: 0
                        exchange.responseHeaders.add("Location", "/chain?${step + 1}")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/cross" -> {
                        exchange.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/ok")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/downgrade" -> {
                        exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/ok")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/large" -> send(exchange, 200, ByteArray(1024) { 65 })
                    "/chunkedlarge" -> {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write(ByteArray(1024) { 65 })
                    }
                    "/gzip" -> {
                        val bytes = ByteArrayOutputStream()
                        GZIPOutputStream(bytes).use { it.write("{value:'${"A".repeat(400)}'}".toByteArray()) }
                        exchange.responseHeaders.add("Content-Encoding", "gzip")
                        send(exchange, 200, bytes.toByteArray())
                    }
                    "/badcharset" -> {
                        exchange.responseHeaders.add("Content-Type", "text/plain; charset=iso-8859-1")
                        send(exchange, 200, "{}".toByteArray())
                    }
                    "/invalidutf8" -> send(exchange, 200, byteArrayOf(0xc3.toByte(), 0x28))
                    "/wait" -> {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write("{".toByteArray())
                        exchange.responseBody.flush()
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        repeat(2048) {
                            exchange.responseBody.write(ByteArray(8192))
                            exchange.responseBody.flush()
                            Thread.sleep(1)
                        }
                    }
                    "/drip" -> {
                        exchange.sendResponseHeaders(200, 0)
                        entered.countDown()
                        repeat(50) {
                            exchange.responseBody.write("a".toByteArray())
                            exchange.responseBody.flush()
                            Thread.sleep(80)
                        }
                    }
                    else -> send(exchange, 500, byteArrayOf())
                }
            } catch (error: Exception) {
                // Expected when the client aborts a synthetic streaming response.
                if (path == "/wait" && error is java.io.IOException) peerClosed.countDown()
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    @After fun stop() {
        release.countDown()
        server.stop(0)
        handlers.shutdownNow()
    }

    private fun send(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun fetcher(limits: HttpsConfigLimits = HttpsConfigLimits()) = HttpsRemoteConfigFetcher(
        limits,
        { uri -> uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection },
        { clientContext },
    )

    private fun address(path: String) =
        RemoteConfigAddress(RemoteChannel.SHARED, URI("$base$path"), false)

    private fun failure(error: HttpsConfigError, action: () -> Unit) {
        try {
            action()
            fail("Expected $error")
        } catch (failure: HttpsConfigFailure) {
            assertEquals(error, failure.error)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(base))
        }
    }

    @Test fun explicitLoopbackProxyFetchesThroughTheNativeMitmWithoutGlobalTrustChanges() {
        val ca = com.dsha.sidecar.net.proxy.tls.EphemeralTestCa.create()
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("local-proxy", ca.certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val proxyTrust = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val config = com.dsha.sidecar.net.runtime.NativeRuntimeConfig.decode(mapOf("server" to mapOf(
            "preSetIpList" to mapOf("localhost" to listOf("127.0.0.1")),
            "intercepts" to mapOf("localhost" to mapOf(".*" to emptyMap<String, Any?>())))))
        com.dsha.sidecar.net.runtime.NativeRuntime(config, ca.authority(), upstreamTrustContext = clientContext).use { runtime ->
            val proxy = Proxy(Proxy.Type.HTTP, runtime.start())
            HttpsRemoteConfigFetcher(HttpsConfigLimits(), proxy, proxyTrust).use { client ->
                assertEquals("{server:{port:4321}}", client.fetch(address("/ok"), 1024).toString(Charsets.UTF_8))
            }
        }
    }

    @Test fun verifiedGetAndSameOriginRedirectSucceed() {
        fetcher().use { client ->
            val expected = "{server:{port:4321}}"
            assertEquals(expected, client.fetch(address("/ok"), 1024).toString(Charsets.UTF_8))
            assertEquals(expected, client.fetch(address("/redirect"), 1024).toString(Charsets.UTF_8))
        }
    }

    @Test fun statusAndRejectedRedirectsDoNotExposeUrlOrBody() {
        fetcher().use { client ->
            failure(HttpsConfigError.STATUS) { client.fetch(address("/status?secret=fixture"), 1024) }
            failure(HttpsConfigError.REDIRECT) { client.fetch(address("/loop"), 1024) }
            failure(HttpsConfigError.REDIRECT) { client.fetch(address("/chain?0"), 1024) }
            failure(HttpsConfigError.REDIRECT) { client.fetch(address("/cross"), 1024) }
            failure(HttpsConfigError.REDIRECT) { client.fetch(address("/downgrade"), 1024) }
        }
    }

    @Test fun explicitlyRejectsPlaintextCredentialsAndFragmentsBeforeConnection() {
        fetcher().use { client ->
            for (uri in listOf("http://localhost/", "https://u:p@localhost/", "https://localhost/#secret")) {
                failure(HttpsConfigError.ADDRESS) {
                    client.fetch(RemoteConfigAddress(RemoteChannel.SHARED, URI(uri), false), 1024)
                }
            }
        }
    }

    @Test fun defaultSystemTrustRejectsSyntheticCertificate() {
        HttpsRemoteConfigFetcher().use { client ->
            failure(HttpsConfigError.TLS) { client.fetch(address("/ok"), 1024) }
        }
    }

    @Test fun trustedCertificateStillRejectsWrongHostname() {
        fetcher().use { client ->
            val wrongHost = RemoteConfigAddress(
                RemoteChannel.SHARED, URI("https://127.0.0.1:${server.address.port}/ok"), false,
            )
            failure(HttpsConfigError.TLS) { client.fetch(wrongHost, 1024) }
        }
    }

    @Test fun boundedIdentityAndGzipRejectOversizeAndAcceptSmallGzip() {
        fetcher().use { client ->
            failure(HttpsConfigError.LIMIT) { client.fetch(address("/large"), 100) }
            failure(HttpsConfigError.LIMIT) { client.fetch(address("/chunkedlarge"), 100) }
            failure(HttpsConfigError.LIMIT) { client.fetch(address("/gzip"), 100) }
            assertTrue(client.fetch(address("/gzip"), 1024).toString(Charsets.UTF_8).contains("A".repeat(400)))
        }
        fetcher(HttpsConfigLimits(maxWireBytes = 16)).use { client ->
            failure(HttpsConfigError.LIMIT) { client.fetch(address("/gzip"), 1024) }
        }
    }

    @Test fun charsetAndInvalidUtf8AreStrict() {
        fetcher().use { client ->
            failure(HttpsConfigError.ENCODING) { client.fetch(address("/badcharset"), 1024) }
            failure(HttpsConfigError.ENCODING) { client.fetch(address("/invalidutf8"), 1024) }
        }
    }

    @Test fun readTimeoutAndTotalTimeoutBoundStreaming() {
        fetcher(HttpsConfigLimits(readTimeoutMillis = 200, totalTimeoutMillis = 4000)).use { client ->
            failure(HttpsConfigError.READ_TIMEOUT) { client.fetch(address("/wait"), 1024) }
        }
    }

    @Test fun totalDeadlineStopsSlowDripEvenWhenReadTimeoutNeverExpires() {
        fetcher(HttpsConfigLimits(readTimeoutMillis = 2000, totalTimeoutMillis = 600)).use { client ->
            val start = System.nanoTime()
            failure(HttpsConfigError.TOTAL_TIMEOUT) { client.fetch(address("/drip"), 1024) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
        }
    }

    @Test fun concurrentCancellationRejectsRepositoryTransactionAndKeepsOldSnapshot() {
        val bootstrap = RemoteFetchDefaults("https://resolver.example.test/", "dns", "resolver.example.test")
        val repository = NativeConfigRepository(remoteFetchDefaults = bootstrap)
        val documents = ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'$base/wait'}},server:{port:9}}", "{}",
        )
        val previous = (repository.reload(documents) as ConfigUpdate.Applied).snapshot
        fetcher().use { client ->
            client.newSession().use { session ->
                val result = AtomicReference<ConfigUpdate>()
                val thread = Thread { result.set(repository.reloadWithFetcher(documents, session)) }
                thread.start()
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val wins = AtomicInteger()
                val cancelers = (1..4).map { Thread { if (session.cancel()) wins.incrementAndGet() }.also(Thread::start) }
                cancelers.forEach { it.join(3000); assertFalse(it.isAlive) }
                thread.join(3000)
                assertFalse(thread.isAlive)
                assertTrue(result.get() is ConfigUpdate.Rejected)
                assertEquals(ConfigError.FETCH, (result.get() as ConfigUpdate.Rejected).error)
                assertSame(previous, repository.current())
                assertEquals(1, wins.get())
                assertFalse(session.cancel())
                failure(HttpsConfigError.CANCELLED) { session.fetch(address("/ok"), 1024) }
                release.countDown()
                assertTrue("Server did not observe client socket closure", peerClosed.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun successfulFetchIsAppliedByRepositoryAndFailedSecondLayerIsAtomic() {
        val bootstrap = RemoteFetchDefaults("https://resolver.example.test/", "dns", "resolver.example.test")
        val repository = NativeConfigRepository(remoteFetchDefaults = bootstrap)
        val single = ConfigDocuments("{app:{remoteConfig:{enabled:true,url:'$base/ok'}}}", "{}")
        val two = ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'$base/ok',personalUrl:'$base/status'}}}", "{}",
        )
        fetcher().use { client ->
            client.newSession().use { session ->
                val applied = repository.reloadWithFetcher(single, session) as ConfigUpdate.Applied
                assertEquals(4321.0, applied.snapshot.server["port"])
                val rejected = repository.reloadWithFetcher(two, session) as ConfigUpdate.Rejected
                assertEquals(ConfigError.FETCH, rejected.error)
                assertSame(applied.snapshot, repository.current())
            }
        }
    }

    @Test fun deadlineCancelsBlockedConnectionAndDisconnectsIt() {
        val enteredConnect = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val fake = object : HttpsURLConnection(URI("$base/mock").toURL()) {
            override fun connect() {
                enteredConnect.countDown()
                while (disconnected.count != 0L) {
                    try { disconnected.await() } catch (_: InterruptedException) {}
                }
            }
            override fun disconnect() { disconnected.countDown() }
            override fun usingProxy(): Boolean = false
            override fun getCipherSuite(): String = ""
            override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
            override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        }
        HttpsRemoteConfigFetcher(
            HttpsConfigLimits(connectTimeoutMillis = 100, totalTimeoutMillis = 300),
            { fake }, { clientContext },
        ).use { client ->
            failure(HttpsConfigError.TOTAL_TIMEOUT) { client.fetch(address("/mock"), 1024) }
            assertTrue(enteredConnect.await(1, TimeUnit.SECONDS))
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            assertEquals(100, fake.connectTimeout)
        }
    }

    @Test fun stalledTlsHandshakeIsBoundedOnARealLocalSocket() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
            val accepted = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val serverThread = Thread {
                try {
                    socket.accept().use {
                        accepted.countDown()
                        finished.await(5, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {}
            }
            serverThread.isDaemon = true
            serverThread.start()
            try {
                val target = RemoteConfigAddress(
                    RemoteChannel.SHARED, URI("https://localhost:${socket.localPort}/"), false,
                )
                fetcher(HttpsConfigLimits(
                    connectTimeoutMillis = 500, readTimeoutMillis = 250, totalTimeoutMillis = 2000,
                )).use { client ->
                    val started = System.nanoTime()
                    try {
                        client.fetch(target, 1024)
                        fail("Expected stalled TLS handshake to time out")
                    } catch (error: HttpsConfigFailure) {
                        assertTrue(error.error in setOf(
                            HttpsConfigError.CONNECT_TIMEOUT, HttpsConfigError.READ_TIMEOUT,
                        ))
                        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1900)
                    }
                }
                assertTrue(accepted.await(1, TimeUnit.SECONDS))
            } finally {
                finished.countDown()
                serverThread.join(3000)
            }
        }
    }

    @Test fun closingSessionCancelsActiveFetchAndCannotRestart() {
        fetcher().use { client ->
            val session = client.newSession()
            val failure = AtomicReference<HttpsConfigError>()
            val thread = Thread {
                try { session.fetch(address("/wait"), 1024) }
                catch (error: HttpsConfigFailure) { failure.set(error.error) }
            }
            thread.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            session.close()
            thread.join(3000)
            assertFalse(thread.isAlive)
            assertEquals(HttpsConfigError.CANCELLED, failure.get())
            failure(HttpsConfigError.CLOSED) { session.fetch(address("/ok"), 1024) }
        }
    }
}
