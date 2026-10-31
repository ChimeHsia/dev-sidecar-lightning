package com.dsha.sidecar.net.proxy

import com.dsha.sidecar.net.dns.DnsCache
import com.dsha.sidecar.net.dns.HostResolver
import com.dsha.sidecar.net.rules.RuleEngine
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用本地回环服务端打通整条链路：WebView → 本地代理 → 目标站。
 * 不连外网，保证测试稳定。
 */
class LocalProxyServerTest {

    private val servers = mutableListOf<ServerSocket>()
    private val proxies = mutableListOf<LocalProxyServer>()

    @AfterTest
    fun tearDown() {
        proxies.forEach { it.stop() }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `普通 HTTP 请求被改写成 origin-form 后转发`() {
        val upstream = ServerSocket(0)
        servers += upstream
        val seen = CountDownLatch(1)
        var received = ""
        thread(isDaemon = true) {
            upstream.accept().use { s ->
                val head = readHead(s)
                received = head
                s.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                )
                s.getOutputStream().flush()
            }
            seen.countDown()
        }

        val proxyPort = freePort()
        val proxy = LocalProxyServer(proxyPort, HostResolver(cache = DnsCache()), RuleEngine(emptyList()))
        proxies += proxy
        assertTrue(proxy.start(), "代理未启动")

        Socket("127.0.0.1", proxyPort).use { client ->
            client.soTimeout = 5000
            client.getOutputStream().write(
                ("GET http://127.0.0.1:${upstream.localPort}/hello?a=1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${upstream.localPort}\r\n\r\n").toByteArray(),
            )
            client.getOutputStream().flush()
            val resp = client.getInputStream().readBytes().decodeToString()
            assertTrue(resp.endsWith("ok"), "响应异常: $resp")
        }
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        assertTrue(received.startsWith("GET /hello?a=1 HTTP/1.1"), "请求行未改写: $received")
        assertEquals(1, proxy.stats().requests)
    }

    @Test
    fun `CONNECT 隧道双向透传`() {
        val upstream = ServerSocket(0)
        servers += upstream
        thread(isDaemon = true) {
            upstream.accept().use { s ->
                val buf = ByteArray(16)
                val n = s.getInputStream().read(buf)
                s.getOutputStream().write(buf, 0, n)   // 回声
                s.getOutputStream().flush()
                Thread.sleep(200)
            }
        }

        val proxyPort = freePort()
        val proxy = LocalProxyServer(proxyPort, HostResolver(cache = DnsCache()), RuleEngine(emptyList()))
        proxies += proxy
        proxy.start()

        Socket("127.0.0.1", proxyPort).use { client ->
            client.soTimeout = 5000
            val out = client.getOutputStream()
            out.write("CONNECT 127.0.0.1:${upstream.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            out.flush()

            // 先读掉 200 响应头
            val input = BufferedInputStream(client.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                head.append(b.toChar())
            }
            assertTrue(head.toString().startsWith("HTTP/1.1 200"), "CONNECT 未建立: $head")

            out.write("ping-".toByteArray())
            out.write("pong".toByteArray())
            out.flush()

            val echoed = ByteArray(9)
            var read = 0
            while (read < echoed.size) {
                val n = input.read(echoed, read, echoed.size - read)
                if (n < 0) break
                read += n
            }
            assertEquals("ping-pong", String(echoed, 0, read))
        }
    }

    private fun readHead(s: Socket): String {
        val sb = StringBuilder()
        val input = s.getInputStream()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
