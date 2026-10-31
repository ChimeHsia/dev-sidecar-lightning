package com.dsha.sidecar

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceError
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dsha.sidecar.accel.AccelManager
import com.dsha.sidecar.node.NodeRuntime
import com.dsha.sidecar.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var accel: AccelManager
    private lateinit var nodeRuntime: NodeRuntime

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            renderAccelStatus()
            if (accel.running) statusHandler.postDelayed(this, 1000)
        }
    }

    private val prefs by lazy { getSharedPreferences(AccelManager.PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35 强制 edge-to-edge，必须自己让出状态栏/导航栏，否则工具栏被系统栏压住
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        accel = AccelManager(this)
        createWebView()
        bindToolbar()

        // 原版核心起来后再把 WebView 代理切过去：核心里才有 MITM + SNI 伪装，能开 Google
        nodeRuntime = NodeRuntime(this).also { rt ->
            rt.onReady = {
                if (prefs.getBoolean(AccelManager.KEY_ACCEL, false) && accel.supported) {
                    Log.i(TAG, "核心就绪，代理切到 127.0.0.1:${AccelManager.CORE_PORT}")
                    accel.start { ok ->
                        renderAccelStatus()
                        startStatusTicker()
                        if (ok) webView.reload()
                    }
                }
            }
            rt.start()
        }

        // 先决定加速是否开启，再加载首屏，避免首个请求走直连
        val wantAccel = prefs.getBoolean(AccelManager.KEY_ACCEL, false) && accel.supported
        if (wantAccel) {
            binding.accelStatus.setText(R.string.accel_starting)
            accel.start {
                startBrowser(savedInstanceState)
                startStatusTicker()
            }
        } else {
            startBrowser(savedInstanceState)
        }
    }

    private fun startStatusTicker() {
        statusHandler.removeCallbacks(statusTicker)
        statusHandler.post(statusTicker)
    }

    private fun startBrowser(savedInstanceState: Bundle?) {
        renderAccelStatus()
        if (savedInstanceState == null) {
            val url = getString(R.string.home_page)
            Log.i(TAG, "loadUrl $url (accel=${accel.running})")
            webView.loadUrl(url)
        } else {
            Log.i(TAG, "restoreState (accel=${accel.running})")
            webView.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                mediaPlaybackRequiresUserGesture = false
                // 去掉 "wv" 标记，避免部分站点对 WebView 做降级处理
                userAgentString = userAgentString.replace("; wv", "")
            }
            webViewClient = browserClient
            webChromeClient = browserChromeClient
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
        binding.webContainer.addView(webView)
    }

    private val browserClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return true
            return when (scheme) {
                "http", "https" -> false
                else -> {
                    openExternal(uri)
                    true
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                Log.w(TAG, "主文档失败 ${request.url} -> ${error.errorCode} ${error.description}")
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            Log.i(TAG, "onPageStarted $url")
            binding.progress.visibility = View.VISIBLE
            binding.progress.progress = 0
            syncUrlBar(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progress.visibility = View.INVISIBLE
            syncUrlBar(url)
            updateNavButtons()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            syncUrlBar(url)
            updateNavButtons()
        }
    }

    private val browserChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.progress = newProgress
            binding.progress.visibility =
                if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            supportActionBar?.title = title
        }
    }

    private fun bindToolbar() {
        binding.btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        binding.btnReload.setOnClickListener { webView.reload() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.accelStatus.setOnClickListener { toggleAccel() }

        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                submitUrl(binding.urlBar.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.urlBar.selectAll()
        }
    }

    // ---------- 加速 ----------

    private fun toggleAccel() {
        if (!accel.supported) {
            Toast.makeText(this, R.string.accel_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (accel.running) {
            prefs.edit().putBoolean(AccelManager.KEY_ACCEL, false).apply()
            accel.stop { renderAccelStatus() }
            statusHandler.removeCallbacks(statusTicker)
        } else {
            prefs.edit().putBoolean(AccelManager.KEY_ACCEL, true).apply()
            binding.accelStatus.setText(R.string.accel_starting)
            accel.start { ok ->
                renderAccelStatus()
                if (ok) {
                    startStatusTicker()
                    webView.reload()
                } else {
                    Toast.makeText(this, "加速启动失败，已回退直连", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderAccelStatus() {
        val s = accel.state()
        binding.accelStatus.text = when {
            !s.supported -> getString(R.string.accel_unsupported)
            !s.running -> if (s.message != null) s.message else getString(R.string.accel_off)
            else -> {
                val base = if (s.failures > 0) {
                    getString(R.string.accel_on, s.port, s.requests, s.accelerated) + " · 失败 ${s.failures}"
                } else {
                    getString(R.string.accel_on, s.port, s.requests, s.accelerated)
                }
                if (s.lastHost != null && s.lastIp != null) {
                    "$base · " + getString(R.string.accel_last, s.lastHost, s.lastIp, s.latencyMs)
                } else {
                    "$base · 规则 ${s.rules} 条"
                }
            }
        }
        binding.accelStatus.setTextColor(
            if (s.running) getColor(R.color.accent) else getColor(R.color.text_secondary),
        )
    }

    // ---------- 其它 ----------

    private fun showMenu() {
        val labels = arrayOf(
            if (accel.running) "关闭加速" else "开启加速",
            "主页",
            "在外部浏览器打开",
            "退出",
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleAccel()
                    1 -> webView.loadUrl(getString(R.string.home_page))
                    2 -> webView.url?.let { openExternal(Uri.parse(it)) }
                    3 -> finish()
                }
            }
            .show()
    }

    private fun submitUrl(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("http://", true) || text.startsWith("https://", true) -> text
            text.contains(" ") || !text.contains(".") ->
                "https://www.bing.com/search?q=" + Uri.encode(text)
            else -> "https://$text"
        }
        webView.loadUrl(target)
    }

    private fun syncUrlBar(url: String?) {
        if (url.isNullOrBlank()) return
        if (binding.urlBar.hasFocus()) return
        binding.urlBar.setText(url)
        binding.urlBar.setSelection(url.length)
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (webView.canGoBack()) 1f else 0.35f
        binding.btnForward.alpha = if (webView.canGoForward()) 1f else 0.35f
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
        binding.urlBar.clearFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        private const val TAG = "dsha-ui"
    }

    override fun onDestroy() {
        NodeRuntime.stop()
        statusHandler.removeCallbacksAndMessages(null)
        accel.stop()
        binding.webContainer.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
