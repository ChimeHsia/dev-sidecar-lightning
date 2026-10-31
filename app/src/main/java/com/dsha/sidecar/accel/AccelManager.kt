package com.dsha.sidecar.accel

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.dsha.sidecar.net.dns.DnsCache
import com.dsha.sidecar.net.dns.DnsClient
import com.dsha.sidecar.net.dns.DnsPacket
import com.dsha.sidecar.net.dns.HostResolver
import com.dsha.sidecar.net.proxy.LocalProxyServer
import com.dsha.sidecar.net.rules.Policy
import com.dsha.sidecar.net.rules.RuleEngine
import com.dsha.sidecar.node.NodeRuntime
import java.io.File

/**
 * 加速总控：本地转发器生命周期 + WebView 代理指向 + 运行状态。
 *
 * 关键前提：ProxyController 的能力取决于系统 WebView 的 Chromium 版本，
 * 必须用 [supported] 探测；不支持时整个加速功能不可用（浏览器本身仍可正常上网）。
 */
class AccelManager(context: Context) {

    data class State(
        val supported: Boolean,
        val running: Boolean,
        val port: Int,
        val rules: Int,
        val requests: Long = 0,
        val accelerated: Long = 0,
        val failures: Long = 0,
        val lastHost: String? = null,
        val lastIp: String? = null,
        val latencyMs: Long = -1,
        val message: String? = null,
    )

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private val cache = DnsCache(store = File(appContext.filesDir, "dns_cache.tsv"))
    private val resolver = HostResolver(cache = cache)
    private val rules = RuleEngine()
    private val server = LocalProxyServer(port = PORT, resolver = resolver, ruleEngine = rules).apply {
        listener = object : LocalProxyServer.Listener {
            override fun onRequest(host: String, port: Int, policed: Policy, ip: String?, latencyMs: Long) {
                Log.i(TAG, "req $host:$port -> ${policed.action} ip=$ip ${latencyMs}ms")
            }

            override fun onTunnelClosed(host: String, upBytes: Long, downBytes: Long) {
                Log.i(TAG, "closed $host: 上行 ${upBytes}B 下行 ${downBytes}B")
            }

            override fun onError(message: String) {
                Log.w(TAG, message)
            }
        }
    }

    val supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var lastMessage: String? = null

    /** 当前实际生效的代理端口：原版核心（31181）或自研转发器（8118）。 */
    @Volatile
    private var activePort: Int = PORT

    @Volatile
    private var usingCore: Boolean = false

    /** 启动转发器并把 WebView 流量指向它。 */
    fun start(onDone: (Boolean) -> Unit) {
        if (!supported) {
            Log.w(TAG, "当前 WebView 不支持 PROXY_OVERRIDE，加速不可用")
            onDone(false)
            return
        }
        // 原版核心已就绪时优先用它：MITM + SNI 伪装 + 预设 IP 全在核心里，
        // 自研转发器只做纯字节转发，遇到 Google 这类必须有证书的场景无能为力。
        if (NodeRuntime.ready) {
            activePort = CORE_PORT
            usingCore = true
            running = true
            lastMessage = null
            Log.i(TAG, "使用原版核心代理 127.0.0.1:$CORE_PORT")
            proxyOverride(enable = true) { ok ->
                if (!ok) {
                    running = false
                    lastMessage = "当前 WebView 不支持代理设置"
                }
                onDone(ok)
            }
            return
        }
        if (running) {
            proxyOverride(enable = true) { onDone(it) }
            return
        }
        val ok = server.start()
        if (!ok) {
            lastMessage = "本地转发器启动失败（端口 $PORT 被占用？）"
            onDone(false)
            return
        }
        running = true
        usingCore = false
        activePort = PORT
        lastMessage = null
        Log.i(TAG, "转发器已监听 127.0.0.1:$PORT")
        diagnose()
        proxyOverride(enable = true) { done ->
            if (!done) {
                // 代理没设上就没有加速效果，转发器留着也没意义
                server.stop()
                running = false
                lastMessage = "当前 WebView 不支持代理设置"
            }
            onDone(done)
        }
    }

    fun stop(onDone: () -> Unit = {}) {
        proxyOverride(enable = false) {
            if (!usingCore) server.stop()
            running = false
            lastMessage = null
            onDone()
        }
    }

    /** 自检：确认自建 DNS 与优选在本机网络下是否可用（排查用，结果进 logcat）。 */
    private fun diagnose() {
        Thread {
            try {
                val direct = DnsClient().query("github.com", DnsPacket.TYPE_A)
                Log.i(TAG, "dns 查询 github.com: answers=" +
                    (direct?.response?.answers?.size ?: -1) +
                    " rcode=" + (direct?.response?.rcode ?: -1) +
                    " via=" + (direct?.server ?: "-"))
                val r = resolver.resolve("github.com")
                Log.i(TAG, "resolve github.com -> ip=${r?.ip} latency=${r?.latencyMs} " +
                    "cached=${r?.cached} candidates=${r?.candidates}")
            } catch (e: Exception) {
                Log.w(TAG, "自检异常: $e")
            }
        }.start()
    }

    fun state(): State {
        val s = server.stats()
        return State(
            supported = supported,
            running = running,
            port = activePort,
            rules = rules.size,
            requests = s.requests,
            accelerated = s.accelerated,
            failures = s.failures,
            lastHost = s.lastHost,
            lastIp = s.lastIp,
            latencyMs = s.lastLatencyMs,
            message = lastMessage,
        )
    }

    private fun proxyOverride(enable: Boolean, done: (Boolean) -> Unit) {
        if (!supported) {
            done(false)
            return
        }
        val controller = ProxyController.getInstance()
        if (!enable) {
            controller.clearProxyOverride(mainExecutor) { done(true) }
            return
        }
        val config = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:$activePort")
            .addDirect()
            .addBypassRule("127.0.0.1")
            .addBypassRule("localhost")
            .build()
        Log.i(TAG, "设置 WebView 代理 -> 127.0.0.1:$activePort")
        controller.setProxyOverride(config, mainExecutor) {
            Log.i(TAG, "WebView 代理已生效")
            done(true)
        }
    }

    companion object {
        private const val TAG = "dsha-accel"
        const val PORT = 8118
        const val CORE_PORT = 31181
        const val PREFS = "dsha"
        const val KEY_ACCEL = "accel_enabled"
    }
}
