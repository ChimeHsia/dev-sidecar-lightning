package com.dsha.sidecar.node

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * 内嵌 Node 运行时：把 Termux 编译的 node（aarch64）当作可执行文件跑起来。
 *
 * 为什么用 Termux 的二进制：官方 nodejs-mobile 停在 Node 12，而 dev-sidecar 核心需要
 * `node:` 前缀与可选链（Node 14+）。Termux 的 node 是普通 Android ELF，可直接 exec。
 *
 * 目录布局（filesDir 下）：
 *   node/            ← node 本体 + 依赖 so（首次启动从 assets 解压）
 *   nodejs-project/  ← main.js 与原版核心 core/
 *   node-home/       ← 替代 ~/.dev-sidecar
 */
class NodeRuntime(private val context: Context) {

    private val root: File get() = File(context.filesDir, "node")
    private val projectDir: File get() = File(context.filesDir, "nodejs-project")
    private val homeDir: File get() = File(projectDir, "home")

    @Volatile
    var running = false
        private set

    /** 核心报告"就绪"后回调（主线程）。 */
    var onReady: (() -> Unit)? = null

    fun start() {
        Thread({ launch() }, "node-boot").start()
    }

    private fun launch() {
        try {
            prepare()
            val nodeBin = File(context.applicationInfo.nativeLibraryDir, "libnodebin.so")
            if (!nodeBin.exists()) {
                Log.e(TAG, "缺少 node 可执行文件：${nodeBin.absolutePath}")
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
                put("DSHA_NAVIGATION_CONFIG", File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "navigation-rules.json"
                ).absolutePath)
            }

            val process = pb.start()
            processRef = process
            running = true
            Log.i(TAG, "node 已启动")

            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    lastLine = line
                    if (!ready && line.contains("就绪")) {
                        ready = true
                        val cb = onReady
                        if (cb != null) Handler(Looper.getMainLooper()).post(cb)
                    }
                }
            }
            val code = process.waitFor()
            running = false
            Log.w(TAG, "node 退出，code=$code")
        } catch (e: Throwable) {
            running = false
            Log.e(TAG, "node 启动失败", e)
        }
    }

    /** 首次启动解压 assets；已有则跳过（用版本号文件判断）。 */
    private fun prepare() {
        if (!root.exists()) root.mkdirs()
        if (!projectDir.exists()) projectDir.mkdirs()
        if (!homeDir.exists()) homeDir.mkdirs()

        unzipAsset("node-libs.zip", root)
        unzipAsset("nodejs-project.zip", projectDir)

        // Termux 的 openssl 默认证书路径不存在，用它自带的证书包兜底
        val ca = File(root, "cacert.pem")
        if (!ca.exists()) {
            copyAsset("cacert.pem", ca)
        }

        // 用打包时固定的 CA 覆盖核心自签的那张：只有这张被 network-security-config 信任，
        // 否则 WebView 会因为证书链不可信而拒绝（ERR_CERT_AUTHORITY_INVALID）
        val dsDir = File(homeDir, ".dev-sidecar").apply { mkdirs() }
        copyAsset("dsha_ca.crt", File(dsDir, "dev-sidecar.ca.crt"))
        copyAsset("dsha_ca.key.pem", File(dsDir, "dev-sidecar.ca.key.pem"))
    }

    private fun copyAsset(name: String, dest: File) {
        context.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private fun unzipAsset(name: String, dest: File) {
        val marker = File(dest, ".${name}.v4.done")
        if (marker.exists()) return

        val start = System.currentTimeMillis()
        var count = 0
        context.assets.open(name).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(dest, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        count++
                    }
                    zip.closeEntry()
                }
            }
        }
        marker.createNewFile()
        Log.i(TAG, "解压 $name → ${dest.name}，$count 个文件，${System.currentTimeMillis() - start}ms")
    }

    companion object {
        private const val TAG = "dsha-node"

        @Volatile
        var ready = false
            private set

        @Volatile
        private var processRef: Process? = null

        @Volatile
        var lastLine: String? = null
            private set

        fun stop() {
            processRef?.destroy()
            processRef = null
        }
    }
}
