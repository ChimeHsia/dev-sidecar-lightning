package com.dsha.sidecar.net.proxy

import com.dsha.sidecar.net.dns.HostResolver
import com.dsha.sidecar.net.rules.Policy
import com.dsha.sidecar.net.rules.RuleAction
import com.dsha.sidecar.net.rules.RuleEngine
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 HTTP 代理（默认 127.0.0.1:8118）。WebView 通过 ProxyController 把流量指到这里。
 *
 * 两条路径：
 *  - CONNECT host:443 —— HTTPS，建连到候选 IP，保持 ClientHello/SNI 不变并原样透传；
 *  - 绝对 URI 的普通 HTTP —— 改写成 origin-form 后转发。
 *
 * 全过程不解密、不需要证书、不接管其它 App 的流量。
 */
class LocalProxyServer(
    val port: Int = 8118,
    private val resolver: HostResolver,
    private val ruleEngine: RuleEngine = RuleEngine(),
    /** 单次建连超时。过大会让"候选地址被墙"时的等待变得很难受。 */
    private val connectTimeoutMs: Int = 4000,
    /** 并发隧道上限。浏览器会同时保持多条 keep-alive 连接，给小了会把页面卡死。 */
    private val maxWorkers: Int = 64,
) {

    data class Stats(
        var requests: Long = 0,
        var accelerated: Long = 0,
        var aborted: Long = 0,
        var failures: Long = 0,
        var lastHost: String? = null,
        var lastIp: String? = null,
        var lastLatencyMs: Long = -1,
    )

    interface Listener {
        fun onRequest(host: String, port: Int, policed: Policy, ip: String?, latencyMs: Long)

        /** 隧道关闭时回调，便于判断"连上了但没数据"这类问题。 */
        fun onTunnelClosed(host: String, upBytes: Long, downBytes: Long) {}

        fun onError(message: String)
    }

    var listener: Listener? = null

    private val counters = Stats()
    private val requestCount = AtomicLong()
    private val acceleratedCount = AtomicLong()
    private val abortedCount = AtomicLong()
    private val failureCount = AtomicLong()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor = newPool()

    /**
     * 注意 core = max：若 core 为 0 又用无界队列，ThreadPoolExecutor 永远只创建 1 个线程，
     * 浏览器的多条 keep-alive 隧道会串行排队，页面表现为"永远加载不完"。
     */
    private fun newPool(): ThreadPoolExecutor = ThreadPoolExecutor(
        maxWorkers,
        maxWorkers,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { r -> Thread(r, "proxy-worker").apply { isDaemon = true } }.apply {
        allowCoreThreadTimeOut(true)
    }

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port), 64)
            serverSocket = ss
            running = true
            acceptThread = Thread({ acceptLoop(ss) }, "proxy-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            listener?.onError("代理端口 $port 启动失败：${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        Tunnel.close(serverSocket)
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        pool.shutdownNow()
        pool = newPool()
        resolver.persist()
    }

    fun stats(): Stats = Stats(
        requests = requestCount.get(),
        accelerated = acceleratedCount.get(),
        aborted = abortedCount.get(),
        failures = failureCount.get(),
        lastHost = counters.lastHost,
        lastIp = counters.lastIp,
        lastLatencyMs = counters.lastLatencyMs,
    )

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                if (!running) return
                continue
            }
            try {
                pool.execute { handle(socket) }
            } catch (_: Exception) {
                Tunnel.closeQuietly(socket)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val clientIn = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val head = readHead(clientIn) ?: return
            val firstLine = String(head, Charsets.ISO_8859_1).substringBefore("\r\n")
            val parts = firstLine.split(' ')
            if (parts.size < 2) {
                respond(socket, "400 Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            requestCount.incrementAndGet()
            if (method == "CONNECT") {
                val (host, p) = splitAuthority(target, 443)
                handleConnect(socket, clientIn, host, p)
            } else if (target.startsWith("http://", true)) {
                handlePlainHttp(socket, clientIn, head, method, target)
            } else {
                respond(socket, "400 Bad Request")
            }
        } catch (e: Exception) {
            listener?.onError("代理连接异常：${e.message}")
        } finally {
            Tunnel.closeQuietly(socket)
        }
    }

    private fun handleConnect(socket: Socket, clientIn: InputStream, host: String, port: Int) {
        val policy = ruleEngine.policy(host, port)
        if (policy.action == RuleAction.ABORT) {
            abortedCount.incrementAndGet()
            respond(socket, "403 Forbidden")
            return
        }

        val dialHost = policy.targetHost ?: host
        val dialPort = policy.targetPort ?: port
        var ip: String? = null
        var latency = -1L
        var usedFastIp = false

        val upstream: Socket? = when (policy.action) {
            RuleAction.ACCELERATE -> {
                if (isLoopback(dialHost)) {
                    connectAny(emptyList(), dialHost, dialPort)
                } else {
                    var r = resolver.resolve(dialHost)
                    if (r != null) {
                        ip = r.ip
                        latency = r.latencyMs
                        usedFastIp = true
                    }
                    var sock = connectAny(r?.candidates ?: emptyList(), dialHost, dialPort)
                    if (sock == null && r?.cached == true) {
                        // 缓存的 IP 可能已经不可达（CDN 调度变了/被干扰），丢掉重测一次
                        listener?.onError("缓存 IP 失效，重新测速 $dialHost")
                        resolver.invalidate(dialHost)
                        r = resolver.resolve(dialHost)
                        if (r != null) {
                            ip = r.ip
                            latency = r.latencyMs
                            usedFastIp = true
                        }
                        sock = connectAny(r?.candidates ?: emptyList(), dialHost, dialPort)
                    }
                    sock
                }
            }
            RuleAction.PROXY -> {
                val r = resolver.resolve(dialHost)
                ip = r?.ip
                latency = r?.latencyMs ?: -1
                if (r != null) usedFastIp = true
                connectAny(r?.candidates ?: emptyList(), dialHost, dialPort)
            }
            else -> {
                // 普通站点：用自建 DNS（DoH 兜底）拿一个 IP 再连，SNI 不变。
                // 这样在系统 DNS 被劫持/不可用的网络里，普通网页也能打开。
                val quick = if (isLoopback(dialHost)) null else resolver.resolveQuick(dialHost)
                ip = quick?.ip
                // 普通站点只试一个地址：这里的目标是"能打开"，不是"挑最快"，不该为备选地址多等
                connectAny(quick?.ip?.let { listOf(it) } ?: emptyList(), dialHost, dialPort)
            }
        }

        if (upstream == null) {
            failureCount.incrementAndGet()
            listener?.onError("连不上 $dialHost:$dialPort（规则 ${policy.action}）")
            respond(socket, "502 Bad Gateway")
            return
        }

        upstream.tcpNoDelay = true
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: DshaSidecar\r\n\r\n".toByteArray())
        out.flush()

        if (usedFastIp) acceleratedCount.incrementAndGet()

        counters.lastHost = host
        counters.lastIp = ip
        counters.lastLatencyMs = latency
        listener?.onRequest(host, port, policy, ip, latency)

        // 注意：这里刻意**不**碰 TLS 字节流。
        // 改写 ClientHello 的 SNI 会让客户端与服务端的握手转录不一致，TLS 1.3 下必然
        // 在 Finished 校验时报 "Tag mismatch"（已用真实 JSSE 服务端验证）。加速只能靠
        // "连更快的 IP、SNI 原样保留"，因此这里只做纯字节转发。
        socket.soTimeout = 0
        val (up, down) = Tunnel.relay(socket, clientIn, upstream)
        listener?.onTunnelClosed(host, up, down)
    }

    private fun handlePlainHttp(
        socket: Socket,
        clientIn: InputStream,
        head: ByteArray,
        method: String,
        absoluteUrl: String,
    ) {
        val rest = absoluteUrl.substring(7)
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"
        val (host, defaultPort) = splitAuthority(authority, 80)
        val policy = ruleEngine.policy(host, defaultPort)
        if (policy.action == RuleAction.ABORT) {
            abortedCount.incrementAndGet()
            respond(socket, "403 Forbidden")
            return
        }

        val ip = if (policy.action == RuleAction.ACCELERATE) resolver.resolve(host)?.ip else null
        val upstream = try {
            Socket().apply {
                connect(InetSocketAddress(ip ?: host, defaultPort), connectTimeoutMs)
                soTimeout = 60_000
            }
        } catch (_: Exception) {
            failureCount.incrementAndGet()
            respond(socket, "502 Bad Gateway")
            return
        }

        val text = String(head, Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n")
        val rewritten = "$method $path HTTP/1.1" + text.substring(headerEnd)
        upstream.getOutputStream().write(rewritten.toByteArray(Charsets.ISO_8859_1))
        upstream.getOutputStream().flush()

        listener?.onRequest(host, defaultPort, policy, ip, if (ip != null) 0 else -1)
        socket.soTimeout = 0
        val (up, down) = Tunnel.relay(socket, clientIn, upstream)
        listener?.onTunnelClosed(host, up, down)
    }

    /** 先试优选 IP（含备选），全部失败再退回系统解析直连。 */
    private fun connectAny(candidates: List<String>, host: String, port: Int): Socket? {
        for (ip in candidates) {
            try {
                return Socket().apply {
                    connect(InetSocketAddress(ip, port), connectTimeoutMs)
                    soTimeout = 60_000
                }
            } catch (e: Exception) {
                listener?.onError("候选 $ip:$port 失败 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return try {
            Socket().apply {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                soTimeout = 60_000
            }
        } catch (e: Exception) {
            listener?.onError("直连 $host:$port 失败 ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 读到空行为止（请求头），最多 [limit] 字节。 */
    private fun readHead(input: InputStream, limit: Int = 16 * 1024): ByteArray? {
        val buf = ByteArrayOutputStream(1024)
        var matched = 0
        while (buf.size() < limit) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return buf.toByteArray()
        }
        return null
    }

    private fun respond(socket: Socket, status: String) {
        try {
            socket.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            socket.getOutputStream().flush()
        } catch (_: Exception) {
        }
    }

    private fun splitAuthority(authority: String, defaultPort: Int): Pair<String, Int> {
        val hostPort = authority.substringBefore('/')
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end > 0) {
                val h = hostPort.substring(1, end)
                val port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return h to port
            }
        }
        val idx = hostPort.lastIndexOf(':')
        if (idx > 0 && hostPort.indexOf(':') == idx && idx < hostPort.length - 1) {
            val port = hostPort.substring(idx + 1).toIntOrNull()
            if (port != null) return hostPort.substring(0, idx) to port
        }
        return hostPort to defaultPort
    }

    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"

}
