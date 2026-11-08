package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.config.native.*
import com.dsha.sidecar.net.proxy.http.*
import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class NativeMigrationTest {
    private val ca = EphemeralTestCa.create()
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun config(host: String, rule: Map<String, Any?> = emptyMap(),
        addresses: List<String> = listOf("127.0.0.1")) = NativeRuntimeConfig.decode(mapOf("server" to mapOf(
        "setting" to mapOf("script" to mapOf("enabled" to true)),
        "preSetIpList" to mapOf(host to addresses),
        "intercepts" to mapOf(host to mapOf(".*" to rule)))))

    private fun request(runtime: NativeRuntime, host: String, method: String = "GET", body: String = ""): Pair<Int, String> {
        Socket().use { socket ->
            socket.connect(runtime.localAddress)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(("$method /test HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n" +
                if (body.isEmpty()) "\r\n" else "Content-Length: ${body.length}\r\n\r\n$body").toByteArray())
            socket.getOutputStream().flush()
            val reader = Http1Reader(socket.getInputStream())
            val response = reader.readResponse(method)!!
            val bytes = ByteArrayOutputStream()
            reader.transferBody(bytes, Http1ChunkMode.DECODE)
            return response.head.status to bytes.toString("UTF-8")
        }
    }

    private fun respond(socket: Socket, status: Int, body: String, type: String = "text/plain") {
        val reader = Http1Reader(socket.getInputStream())
        reader.readRequest()!!
        reader.transferBody(ByteArrayOutputStream(), Http1ChunkMode.DECODE)
        socket.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
        socket.getOutputStream().flush()
    }

    @Test fun failedFirstIpRecoversWithinTheSameRequest() {
        ServerSocket(0, 8, loopback).use { origin ->
            origin.soTimeout = 5_000
            val worker = Thread { origin.accept().use { respond(it, 200, "third candidate") } }.apply { start() }
            NativeRuntime(config("candidate.test", addresses = listOf("127.0.0.2", "127.0.0.3", "127.0.0.1")),
                ca.authority(), NativeRuntimeOptions(proxy = NativeProxyOptions(connectTimeoutMillis = 300))).use {
                it.start()
                assertEquals(200 to "third candidate", request(it, "candidate.test:${origin.localPort}"))
                assertEquals(3, it.routes.state().selections.toInt())
            }
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun explicitStatusRetriesBufferOnlyAuthorizedBodies() {
        ServerSocket(0, 8, loopback).use { origin ->
            origin.soTimeout = 5_000
            val count = AtomicInteger()
            val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
            val worker = Thread {
                repeat(2) {
                    origin.accept().use { socket ->
                        val reader = Http1Reader(socket.getInputStream())
                        assertEquals("POST", reader.readRequest()!!.head.method)
                        val bytes = ByteArrayOutputStream()
                        reader.transferBody(bytes)
                        bodies.add(bytes.toString("UTF-8"))
                        val status = if (count.incrementAndGet() == 1) 500 else 200
                        socket.getOutputStream().write("HTTP/1.1 $status Test\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    }
                }
            }.apply { start() }
            val rule = mapOf("retry" to mapOf("times" to 1, "methods" to listOf("POST"), "statuses" to listOf(500)))
            NativeRuntime(config("replay.test", rule), ca.authority()).use {
                it.start()
                assertEquals(200 to "ok", request(it, "replay.test:${origin.localPort}", "POST", "purchase"))
            }
            worker.join(5_000)
            assertEquals(listOf("purchase", "purchase"), bodies)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun backupRebuildsAuthorityAndPathBeforeSending() {
        ServerSocket(0, 8, loopback).use { origin ->
            origin.soTimeout = 5_000
            val worker = Thread {
                origin.accept().use { socket ->
                    val reader = Http1Reader(socket.getInputStream())
                    val head = reader.readRequest()!!.head
                    assertEquals("/mirror/test", head.target.raw)
                    assertEquals(listOf("127.0.0.1:${origin.localPort}"), head.headers.values("Host"))
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nbackup".toByteArray())
                }
            }.apply { start() }
            val rule = mapOf("proxy" to "http://127.0.0.2:${origin.localPort}/unreachable",
                "backup" to listOf("127.0.0.1:${origin.localPort}/mirror"))
            NativeRuntime(config("backup.test", rule), ca.authority()).use {
                it.start()
                assertEquals(200 to "backup", request(it, "backup.test:${origin.localPort}"))
            }
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun configuredHtmlScriptsAreInjectedThroughTheRealResponseBoundary() {
        ServerSocket(0, 8, loopback).use { origin ->
            origin.soTimeout = 5_000
            val worker = Thread {
                origin.accept().use { respond(it, 200, "<html><head></head><body>hello</body></html>", "text/html; charset=utf-8") }
            }.apply { start() }
            val rule = mapOf("script" to "https://assets.test/feature.js",
                "tampermonkeyScript" to "https://assets.test/bootstrap.js")
            NativeRuntime(config("script.test", rule), ca.authority()).use {
                it.start()
                val response = request(it, "script.test:${origin.localPort}")
                assertEquals(200, response.first)
                assertTrue(response.second.contains("/____ds_script_proxy____/https_assets_test_feature.js"))
                assertTrue(response.second.indexOf("bootstrap.js") < response.second.indexOf("feature.js"))
                assertTrue(response.second.contains("<body>hello</body>"))
            }
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun realExportedAndroidDefaultsLoadWithoutDisablingConfiguredFeatures() {
        val file = listOf(File("../lightning-browser/app/src/main/assets/native/default-config.json"),
            File("lightning-browser/app/src/main/assets/native/default-config.json")).first { it.isFile }
        NativeRuntimeConfig.decode(BoundedJson5().parseObject(file.readText()))
        val repository = NativeConfigRepository(remoteFetchDefaults = RemoteFetchDefaults(
            "https://cloudflare-dns.com/dns-query", "cf-DoH", "cloudflare-dns.com"),
            validate = { NativeRuntimeConfig.decode(it.configuration) })
        val update = repository.reload(ConfigDocuments(file.readText())) as ConfigUpdate.Applied
        val snapshot = NativeRuntimeConfig.decode(update.snapshot.configuration)
        assertTrue(snapshot.requiresEchAdapter)
        assertTrue(snapshot.responseHeadersEnabled)
        assertEquals(56, update.snapshot.intercepts.size)
        NativeRuntime(snapshot, ca.authority()).use { assertEquals(NativeRuntimeState.NEW, it.state) }
    }

    @Test fun liveConfigSwapKeepsInflightResponseAndUsesNewRulesOnTheSameListener() {
        ServerSocket(0, 8, loopback).use { origin ->
            origin.soTimeout = 5_000
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val worker = Thread {
                origin.accept().use { socket ->
                    entered.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    respond(socket, 200, "old request")
                }
            }.apply { start() }
            NativeRuntime(config("reload.test"), ca.authority()).use { runtime ->
                runtime.start()
                val address = runtime.localAddress
                val result = java.util.concurrent.atomic.AtomicReference<Pair<Int, String>>()
                val requestThread = Thread { result.set(request(runtime, "reload.test:${origin.localPort}")) }.apply { start() }
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                runtime.reconfigure(config("reload.test", mapOf("success" to mapOf("text" to "new config"))))
                assertEquals(address, runtime.localAddress)
                assertEquals(200 to "new config", request(runtime, "reload.test:${origin.localPort}"))
                release.countDown()
                requestThread.join(5_000)
                assertFalse(requestThread.isAlive)
                assertEquals(200 to "old request", result.get())
                assertThrows(IllegalArgumentException::class.java) {
                    runtime.reconfigure(NativeRuntimeConfig.decode(mapOf("server" to mapOf("port" to 1))))
                }
                assertEquals(200 to "new config", request(runtime, "reload.test:${origin.localPort}"))
            }
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }
}
