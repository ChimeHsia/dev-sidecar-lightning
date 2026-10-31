package com.dsha.sidecar.net.proxy

import com.dsha.sidecar.net.dns.DnsCache
import com.dsha.sidecar.net.dns.HostResolver
import com.dsha.sidecar.net.rules.RuleEngine
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * 真实链路冒烟测试（默认跳过）：自建 DNS → IP 测速 → CONNECT → TLS 握手 → HTTP 请求。
 * 需要联网，用环境变量开启：
 *
 *   -Pdsha.live=1 gradle :netcore:test --tests "*LiveAccelSmokeTest*"
 */
class LiveAccelSmokeTest {

    private val LIVE_HOST: String = System.getProperty("dsha.liveHost")?.takeIf { it.isNotBlank() } ?: "github.com"

    @Test
    fun `经本地代理访问 github com 能完成 TLS 握手并拿到响应`() {
        assumeTrue("需要 -Pdsha.live=1 才执行联网测试", System.getProperty("dsha.live") == "1")

        val host = LIVE_HOST
        val proxyPort = ServerSocket(0).use { it.localPort }
        val resolver = HostResolver(cache = DnsCache())
        val proxy = LocalProxyServer(proxyPort, resolver, RuleEngine())
        assertTrue(proxy.start(), "代理启动失败")
        try {
            Socket("127.0.0.1", proxyPort).use { raw ->
                raw.soTimeout = 20_000
                raw.getOutputStream().write(
                    "CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray(),
                )
                raw.getOutputStream().flush()

                // 逐字节读，不能用 BufferedInputStream：它会预读并吞掉后续 TLS 字节
                val rawIn = raw.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = rawIn.read()
                    if (b < 0) break
                    head.append(b.toChar())
                }
                assertTrue(head.startsWith("HTTP/1.1 200"), "CONNECT 失败：$head")

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
                ssl.use { socket ->
                    // 强制校验证书链与主机名，顺带证明 SNI/选路没有把证书搞坏
                    val params = socket.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    socket.sslParameters = params
                    socket.startHandshake()
                    assertEquals(host, socket.session.peerHost)

                    socket.getOutputStream().write(
                        "HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    val status = socket.getInputStream().readBytes().decodeToString()
                        .lineSequence().first()
                    assertTrue(status.contains("200") || status.contains("301"), "响应异常：$status")
                }
            }

            val stats = proxy.stats()
            val last = resolver.lastResult
            println("加速统计: $stats")
            println("优选结果: $last")
            assertTrue(stats.requests >= 1, "代理未记录请求")
            assertTrue(last != null, "未产出优选 IP")
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `DNS 优选能返回可用 IP`() {
        assumeTrue("需要 -Pdsha.live=1 才执行联网测试", System.getProperty("dsha.live") == "1")
        val resolver = HostResolver(cache = DnsCache())
        val r = resolver.resolve(LIVE_HOST)
        println("raw.githubusercontent.com -> $r")
        assertTrue(r != null, "解析失败")
        assertTrue(r.candidates.isNotEmpty(), "无候选 IP")
    }
}
