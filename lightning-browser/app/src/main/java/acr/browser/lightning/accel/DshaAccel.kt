package acr.browser.lightning.accel

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * 内嵌 dev-sidecar 核心：在本进程里拉起 Node（aarch64 ELF），
 * 再把 WebView 的流量指向它的 MITM 端口（127.0.0.1:31181）。
 *
 * 为什么是 MITM 而不是纯转发：Google 这类站点的 DNS 被投毒、可连的 IP 只给
 * 诱饵证书（CN=invalid.invalid），纯字节转发必然被浏览器拒绝；只有自己签发证书
 * 才能接住（CA 打包在 APK 内，只被本 App 的 network-security-config 信任）。
 *
 * 关键约束：
 * - `ProxyController` 是**进程级**的，一个进程调一次即对全部 WebView 生效。
 * - WebView 进程（`:incognito`）不重复拉 Node，只设代理指向主进程已开好的端口。
 * - Node 二进制必须以 `libnodebin.so` 放在 jniLibs（Android 10+ 只允许从
 *   nativeLibraryDir 执行文件），依赖库靠 LD_LIBRARY_PATH 指过去。
 */
object DshaAccel {

    private const val TAG = "dsha-accel"
    private const val PORT = 31181
    private const val MARKER_READY = "[android] READY 127.0.0.1:$PORT"
    private const val MARKER_FAILED = "[android] FAILED"
    /** 负载版本：改了 assets 里的 zip 就 +1，保证升级后会重新解压。 */
    private const val PAYLOAD_VERSION = "12"

    @Volatile
    private var started = false

    /** 只在 node 就绪后置 true；代理本身可重复设置（WebView 每建一个都补一次）。 */
    @Volatile
    private var coreReady = false

    @Volatile
    private var ownsCore = false

    @Volatile
    private var activeToken = 0L

    private var process: Process? = null

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

    /** 主进程调用：解压资源 → 起 Node → 等就绪 → 设代理。 */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        ownsCore = true
        started = true
        coreReady = false
        val token = startupGate.begin()
        activeToken = token
        appContext = context.applicationContext
        Thread({ boot(context.applicationContext, token) }, "dsha-boot").start()
    }

    /**
     * WebView 子进程调用：核心已在主进程跑好，这里只需要把本进程的 WebView 指过去。
     * 子进程里 `start()` 会拉 Node，那是错的，所以单独开一个入口。
     */
    fun attachProxyOnly(context: Context) {
        ensureProxy(context)
    }

    private fun boot(context: Context, token: Long) {
        try {
            synchronized(preparationLock) {
                if (!isActive(token)) return
                prepare(context)
            }
            if (isActive(token)) launchNode(context, token)
        } catch (e: Throwable) {
            Log.e(TAG, "核心启动失败", e)
            failAttempt(token)
        }
    }

    @Synchronized
    private fun isActive(token: Long): Boolean = started && activeToken == token

    @Synchronized
    private fun failAttempt(token: Long) {
        if (activeToken != token) return
        coreReady = false
        started = false
        startupGate.fail(token)
        process?.destroy()
        process = null
    }

    private fun launchNode(context: Context, token: Long) {
        val root = File(context.filesDir, "node")
        val projectDir = File(context.filesDir, "nodejs-project")
        val homeDir = File(projectDir, "home")

        val nodeBin = File(context.applicationInfo.nativeLibraryDir, "libnodebin.so")
        if (!nodeBin.exists()) {
            Log.e(TAG, "缺少 node 可执行文件：${nodeBin.absolutePath}")
            failAttempt(token)
            return
        }

        val pb = ProcessBuilder(nodeBin.absolutePath, "main.js")
            .directory(projectDir)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", root.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("SSL_CERT_FILE", File(root, "cacert.pem").absolutePath)
            put("NODE_EXTRA_CA_CERTS", File(root, "cacert.pem").absolutePath)
            put("DSHA_PORT", PORT.toString())
            put("DSHA_NAVIGATION_CONFIG", File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "navigation-rules.json"
            ).absolutePath)
        }

        val p = pb.start()
        synchronized(this) {
            if (!isActive(token)) {
                p.destroy()
                return
            }
            process = p
        }
        Log.i(TAG, "node 已启动")

        try {
            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    if (line.startsWith(MARKER_FAILED)) {
                        failAttempt(token)
                    } else if (line == MARKER_READY && isActive(token)) {
                        synchronized(this) {
                            if (isActive(token)) coreReady = true
                        }
                        appContext?.let { refreshProxy(it, token) }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读取 node 输出中断: ${e.message}")
        }
        failAttempt(token)
        Log.w(TAG, "node 已退出 code=${runCatching { p.waitFor() }.getOrNull()}")
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

    /** 首次启动解压 assets（用标记文件避免重复解压）。 */
    private fun prepare(context: Context) {
        val root = File(context.filesDir, "node").apply { mkdirs() }
        val projectDir = File(context.filesDir, "nodejs-project").apply { mkdirs() }
        val homeDir = File(projectDir, "home").apply { mkdirs() }

        unzipAsset(context, "node-libs.zip", root)
        unzipAsset(context, "nodejs-project.zip", projectDir)

        // Termux 的 openssl 默认证书路径不存在，用自带证书包兜底
        val ca = File(root, "cacert.pem")
        if (!ca.exists()) copyAsset(context, "cacert.pem", ca)

        // 用打包时固定的 CA 覆盖核心自签的那张：只有这张被 network-security-config
        // 信任，否则 WebView 会报 ERR_CERT_AUTHORITY_INVALID
        val dsDir = File(homeDir, ".dev-sidecar").apply { mkdirs() }
        copyAsset(context, "dsha_ca.crt", File(dsDir, "dev-sidecar.ca.crt"))
        copyAsset(context, "dsha_ca.key.pem", File(dsDir, "dev-sidecar.ca.key.pem"))
    }

    private fun copyAsset(context: Context, name: String, dest: File) {
        context.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private fun unzipAsset(context: Context, name: String, dest: File) {
        val start = System.currentTimeMillis()
        val count = PayloadExtractor.extract(name, PAYLOAD_VERSION, dest) {
            context.assets.open(name)
        } ?: return
        Log.i(TAG, "解压 $name → ${dest.name}，$count 个文件，${System.currentTimeMillis() - start}ms")
    }

    @Synchronized
    fun stop() {
        ownsCore = false
        failAttempt(activeToken)
    }
}
