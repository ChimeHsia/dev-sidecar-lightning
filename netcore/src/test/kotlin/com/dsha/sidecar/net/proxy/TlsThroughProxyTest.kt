package com.dsha.sidecar.net.proxy

import com.dsha.sidecar.net.dns.DnsCache
import com.dsha.sidecar.net.dns.HostResolver
import com.dsha.sidecar.net.rules.Rule
import com.dsha.sidecar.net.rules.RuleAction
import com.dsha.sidecar.net.rules.RuleEngine
import java.io.FileInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用本地真实 TLS 栈验证加速链路（不依赖外网/真机）：
 * JSSE 客户端 → CONNECT → 代理嗅探并改写 ClientHello → 嗅探中继(记录 SNI) → 真实 TLS 服务端。
 *
 * 握手能否成功，反证长度字段改对了；中继解析出的 SNI，反证改写真的生效。
 */
class TlsThroughProxyTest {

    private val proxies = mutableListOf<LocalProxyServer>()
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        proxies.forEach { it.stop() }
        servers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `PROXY 规则改连镜像时握手成功且 SNI 保持原样`() {
        val seen = AtomicReference<String?>(null)
        val ready = CountDownLatch(1)
        val tlsPort = startTlsServer(ready)
        val sniffPort = startSniffer(tlsPort, seen, rewriteTo = null)

        val proxyPort = freePort()
        val engine = RuleEngine(
            listOf(Rule("^raw\\.example\\.com$", RuleAction.PROXY, target = "127.0.0.1:$sniffPort")),
        )
        val proxy = LocalProxyServer(proxyPort, HostResolver(cache = DnsCache()), engine)
        proxies += proxy
        assertTrue(proxy.start())

        val (statusLine, body) = runHttpsThroughProxy(proxyPort, TEST_HOST, 443)
        assertTrue(ready.await(10, TimeUnit.SECONDS), "TLS 服务端未完成握手")
        assertEquals(TEST_HOST, seen.get(), "SNI 必须保持原域名")
        assertTrue(statusLine.contains("200"), "响应异常：$statusLine")
        assertTrue(body.endsWith("hello"), "body 异常：$body")
    }

    /**
     * 回归护栏：链路上改写 ClientHello 的 SNI 会让客户端与服务端的握手转录不一致，
     * TLS 1.3 下必然在 Finished 校验时报 tag mismatch。这个测试证明"不能改"，
     * 若哪天它变成 PASS（握手成功），说明结论需要重新评估。
     */
    @Test
    fun `链路上改写 SNI 会导致 TLS 握手失败`() {
        val seen = AtomicReference<String?>(null)
        val ready = CountDownLatch(1)
        val tlsPort = startTlsServer(ready)
        val sniffPort = startSniffer(tlsPort, seen, rewriteTo = "alt.example")

        val proxyPort = freePort()
        val engine = RuleEngine(
            listOf(Rule("^raw\\.example\\.com$", RuleAction.PROXY, target = "127.0.0.1:$sniffPort")),
        )
        val proxy = LocalProxyServer(proxyPort, HostResolver(cache = DnsCache()), engine)
        proxies += proxy
        proxy.start()

        val failed = runCatching { runHttpsThroughProxy(proxyPort, TEST_HOST, 443) }.isFailure
        assertEquals("alt.example", seen.get(), "中继应看到被改写的 SNI")
        assertTrue(failed, "改写 SNI 后握手本应失败；若成功请重新评估这个结论")
    }

    @Test
    fun `直连模式同样能完成握手`() {
        val ready = CountDownLatch(1)
        val tlsPort = startTlsServer(ready)

        val proxyPort = freePort()
        val proxy = LocalProxyServer(proxyPort, HostResolver(cache = DnsCache()), RuleEngine(emptyList()))
        proxies += proxy
        proxy.start()

        val (statusLine, _) = runHttpsThroughProxy(proxyPort, "127.0.0.1", tlsPort)
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        assertTrue(statusLine.contains("200"))
    }

    // ---------- 组件 ----------

    /** 最小 TLS 服务端：握手后返回一个固定响应。 */
    private fun startTlsServer(ready: CountDownLatch): Int {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream("src/test/resources/tls/test.p12").use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }

        val ss = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        servers += ss
        thread(isDaemon = true) {
            try {
                val ssl = ss.accept() as SSLSocket
                ssl.use {
                    it.startHandshake()
                    ready.countDown()
                    val buf = ByteArray(2048)
                    if (it.getInputStream().read(buf) > 0) {
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".toByteArray(),
                        )
                        it.getOutputStream().flush()
                    }
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                println("测试 TLS 服务端异常: $e")
                ready.countDown()
            }
        }
        return ss.localPort
    }

    /** 嗅探中继：读出第一条 TLS record，记录其 SNI，然后原样转发并双向透传。 */
    private fun startSniffer(tlsPort: Int, seen: AtomicReference<String?>, rewriteTo: String?): Int {
        val ss = ServerSocket(0)
        servers += ss
        thread(isDaemon = true) {
            try {
                ss.accept().use { client ->
                    val upstream = Socket("127.0.0.1", tlsPort)
                    val first = readTlsRecord(client.getInputStream())
                    if (first != null) {
                        val out = if (rewriteTo != null) TlsClientHello.rewriteSni(first, rewriteTo) else first
                        seen.set(TlsClientHello.sniOf(out))   // 记录真正发给服务端的 SNI
                        upstream.getOutputStream().write(out)
                        upstream.getOutputStream().flush()
                    }
                    Tunnel.relay(client, upstream)
                }
            } catch (e: Exception) {
                println("嗅探中继异常: $e")
            }
        }
        return ss.localPort
    }

    private fun readTlsRecord(input: InputStream): ByteArray? {
        val header = ByteArray(5)
        var read = 0
        while (read < 5) {
            val n = input.read(header, read, 5 - read)
            if (n < 0) return null
            read += n
        }
        val len = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        val body = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = input.read(body, got, len - got)
            if (n < 0) return null
            got += n
        }
        return header + body
    }

    /** 经代理发起 CONNECT，再用真实 TLS 栈握手并取响应。 */
    private fun runHttpsThroughProxy(proxyPort: Int, host: String, targetPort: Int): Pair<String, String> {
        Socket("127.0.0.1", proxyPort).use { raw ->
            raw.soTimeout = 15_000
            raw.getOutputStream().write(
                "CONNECT $host:$targetPort HTTP/1.1\r\nHost: $host:$targetPort\r\n\r\n".toByteArray(),
            )
            raw.getOutputStream().flush()

            val rawIn = raw.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = rawIn.read()
                if (b < 0) break
                head.append(b.toChar())
            }
            assertTrue(head.startsWith("HTTP/1.1 200"), "CONNECT 失败：$head")

            val ssl = clientFactory().createSocket(raw, host, targetPort, true) as SSLSocket
            ssl.use {
                it.startHandshake()
                it.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                it.getOutputStream().flush()
                val text = it.getInputStream().readBytes().decodeToString()
                return text.lineSequence().first() to text
            }
        }
    }

    private fun clientFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }.socketFactory
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        /** JSSE 只对合法 DNS 名发送 SNI，所以不能用 localhost / IP 字面量。 */
        const val TEST_HOST = "raw.example.com"
    }
}
