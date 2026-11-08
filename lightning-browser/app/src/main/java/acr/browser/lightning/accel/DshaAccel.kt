package acr.browser.lightning.accel

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.File
import java.util.concurrent.Executors
import com.dsha.sidecar.net.config.native.*
import com.dsha.sidecar.net.proxy.native.NativeProxyOptions
import com.dsha.sidecar.net.proxy.tls.MitmCertificateAuthority
import com.dsha.sidecar.net.runtime.*
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/**
 * Kotlin MITM runtime owned by the main process and a bound service.
 * Each WebView process installs its own override; incognito binds the main-process owner.
 */
object DshaAccel {

    private const val TAG = "dsha-accel"
    private const val PORT = 31181

    @Volatile
    private var started = false

    /** Binding and WebView callbacks must both finish before releasing initial navigation. */
    @Volatile
    private var coreReady = false

    @Volatile
    private var ownsCore = false

    @Volatile
    private var activeToken = 0L

    private var runtime: NativeRuntime? = null
    private var ownerConnection: ServiceConnection? = null
    private var remoteFetcher: HttpsRemoteConfigFetcher? = null

    @Volatile
    private var appContext: Context? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val startupGate = ProxyStartupGate()
    private val preparationLock = Any()

    suspend fun awaitReady() {
        if (ownsCore && !started) appContext?.let { start(it) }
        if (started && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (!startupGate.await()) {
                Log.w(TAG, "代理未就绪或启动失败，交由 WebView 显示连接结果")
            }
        }
    }

    /** Main process: construct the native runtime, bind, then install the WebView override. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        ownsCore = true
        started = true
        coreReady = false
        val token = startupGate.begin()
        activeToken = token
        appContext = context.applicationContext
        bindOwner(context.applicationContext)
        Thread({ boot(context.applicationContext, token) }, "dsha-boot").start()
    }

    /**
     * The service starts/retains the main process even when incognito is opened independently.
     */
    @Synchronized
    fun attachProxyOnly(context: Context) {
        appContext = context.applicationContext
        if (!started) {
            started = true
            ownsCore = false
            activeToken = startupGate.begin()
        }
        bindOwner(context.applicationContext)
        ensureProxy(context)
    }

    private fun boot(context: Context, token: Long) {
        try {
            synchronized(preparationLock) {
                if (!isActive(token)) return
                val defaults = context.assets.open("native/default-config.json").bufferedReader().use { it.readText() }
                val store = NativeCoreConfigStore(File(context.filesDir, "native-sidecar"),
                    File(context.filesDir, "nodejs-project/home/.dev-sidecar"))
                val config = store.load(defaults)
                val authority = MitmCertificateAuthority.fromPem(
                    context.assets.open("dsha_ca.crt").bufferedReader().use { it.readText() },
                    context.assets.open("dsha_ca.key.pem").bufferedReader().use { it.readText() })
                val navigation = NativeNavigationRuleStore(
                    context.assets.open("native/navigation-rules.json").bufferedReader().use { it.readText() },
                    File(context.getExternalFilesDir(null) ?: context.filesDir, "navigation-rules.json"))
                val native = NativeRuntime(config, authority, NativeRuntimeOptions(
                    proxy = NativeProxyOptions(port = PORT, workers = 24, queuedConnections = 96,
                        connectTimeoutMillis = 4_000, handshakeTimeoutMillis = 6_000,
                        readTimeoutMillis = 20_000, exchangeTimeoutMillis = 60_000),
                    allowSystemDnsFallback = true, legacyTlsCompatibility = true),
                    navigationRules = navigation::getRules)
                synchronized(this) {
                    if (!isActive(token)) { native.close(); return }
                    runtime = native
                }
                native.start()
                synchronized(this) {
                    if (!isActive(token)) { native.close(); return }
                    coreReady = true
                }
                Log.i(TAG, "[android] READY Kotlin 127.0.0.1:$PORT")
                refreshProxy(context, token)
                refreshRemote(store, defaults, token, authority)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "核心启动失败", e)
            failAttempt(token)
        }
    }

    @Synchronized
    internal fun isNativeOwnerReady(): Boolean = ownsCore && coreReady &&
        runtime?.state == com.dsha.sidecar.net.runtime.NativeRuntimeState.LISTENING

    @Synchronized
    private fun isActive(token: Long): Boolean = started && activeToken == token

    @Synchronized
    private fun failAttempt(token: Long) {
        if (activeToken != token) return
        coreReady = false
        started = false
        startupGate.fail(token)
        remoteFetcher?.close()
        remoteFetcher = null
        val previous = runtime
        runtime = null
        if (previous != null) Thread({ previous.close() }, "dsha-close").start()
    }

    @Synchronized
    private fun bindOwner(context: Context) {
        if (ownerConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (ownsCore) return
                val token = activeToken
                Thread({
                    val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
                    while (isActive(token) && System.nanoTime() < until) {
                        val listening = runCatching {
                            NativeProxyService.isReady(service)
                        }.getOrDefault(false)
                        if (listening) {
                            synchronized(this@DshaAccel) {
                                if (!isActive(token)) return@Thread
                                coreReady = true
                            }
                            refreshProxy(context, token)
                            return@Thread
                        }
                        Thread.sleep(100)
                    }
                    failAttempt(token)
                }, "dsha-attach").start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!ownsCore) failAttempt(activeToken)
            }
        }
        ownerConnection = connection
        if (!context.bindService(Intent(context, NativeProxyService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            ownerConnection = null
            if (!ownsCore) failAttempt(activeToken)
        }
    }

    private fun refreshRemote(store: NativeCoreConfigStore, defaults: String, token: Long,
        authority: MitmCertificateAuthority) {
        Thread({
            val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("browser-proxy", authority.certificate)
            }
            val managers = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, managers.trustManagers, null)
            }
            val fetcher = HttpsRemoteConfigFetcher(HttpsConfigLimits(
                connectTimeoutMillis = 4_000, readTimeoutMillis = 6_000, totalTimeoutMillis = 10_000),
                java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", PORT)), context)
            synchronized(this) {
                if (!isActive(token)) { fetcher.close(); return@Thread }
                remoteFetcher = fetcher
            }
            try {
                val downloads = linkedMapOf<String, ByteArray>()
                val result = store.repository().reloadWithFetcher(store.documents(defaults),
                    RemoteConfigFetcher { address, maximum ->
                        fetcher.fetch(address, maximum).also { bytes ->
                            downloads[if (address.channel == RemoteChannel.PERSONAL)
                                "remote_config_personal.json5" else "remote_config.json5"] = bytes
                        }
                    })
                synchronized(preparationLock) {
                    if (isActive(token) && result is ConfigUpdate.Applied) {
                        val updated = NativeRuntimeConfig.decode(result.snapshot.configuration)
                        runtime?.reconfigure(updated)
                        downloads.forEach { (name, bytes) -> store.write(name, bytes) }
                        if (downloads.isNotEmpty()) Log.i(TAG, "原生远程规则已更新并缓存，当前请求继续使用原规则")
                    } else if (result is ConfigUpdate.Rejected) {
                        Log.w(TAG, "远程规则更新被拒绝，保留当前规则: ${result.error}")
                    }
                }
            } catch (failure: Exception) {
                Log.w(TAG, "远程规则刷新失败，保留当前规则", failure)
            } finally {
                synchronized(this) { if (remoteFetcher === fetcher) remoteFetcher = null }
                fetcher.close()
            }
        }, "dsha-config").start()
    }

    /**
     * 核心就绪后强制重设一次代理。
     *
     * 关键点：启动瞬间 WebView 往往已经开始加载（恢复上次标签），那时核心端口还没
     * LISTEN。Chromium 会把"代理连不上"这个结果缓存一段时间，之后即使端口开了也
     * 不再重试 —— 表现就是代理设置明明成功了，核心却收到 0 个请求。
     * 先 clear 再 set，等于让 Chromium 丢掉旧结论重新解析。
     */
    @Synchronized
    fun refreshProxy(context: Context, token: Long = activeToken) {
        if (!isActive(token)) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        ProxyController.getInstance().clearProxyOverride(executor) {
            synchronized(this) {
                if (isActive(token)) {
                    Log.i(TAG, "已清空旧代理配置，准备重设")
                    ensureProxy(context)
                }
            }
        }
    }

    /**
     * 把本进程所有 WebView 的流量导向本地核心。
     *
     * 必须可重复调用：`ProxyController` 的设置在 WebView 尚未初始化时可能静默不生效，
     * 所以每建一个 WebView 就补设一次，确保后来的 WebView 也吃到代理。
     */
    @Synchronized
    fun ensureProxy(context: Context) {
        appContext = context.applicationContext
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "当前 WebView 不支持 PROXY_OVERRIDE（Chromium 版本过旧）")
            return
        }
        // 不设 bypass：与已验证可用的版本保持一致，加了 bypass 规则反而出现过代理不生效
        val config = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$PORT")
            .build()
        val token = activeToken
        ProxyController.getInstance().setProxyOverride(config, executor) {
            Log.i(TAG, "WebView 代理已生效 → 127.0.0.1:$PORT")
            synchronized(this) {
                if (coreReady && isActive(token)) startupGate.markReady(token)
            }
        }
    }

    @Synchronized
    fun stop() {
        ownsCore = false
        failAttempt(activeToken)
        val connection = ownerConnection
        ownerConnection = null
        if (connection != null) appContext?.let { runCatching { it.unbindService(connection) } }
    }
}
