package acr.browser.lightning.browser.tab

import acr.browser.lightning.accel.DshaAccel
import acr.browser.lightning.browser.view.CustomGestureListener
import acr.browser.lightning.browser.view.ToggleListener
import acr.browser.lightning.browser.view.TouchListener
import acr.browser.lightning.browser.view.setCompositeOnFocusChangeListener
import acr.browser.lightning.browser.view.setCompositeTouchListener
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.connectivity.ConnectivityProvider
import acr.browser.lightning.constant.DESKTOP_USER_AGENT
import acr.browser.lightning.download.PendingDownload
import acr.browser.lightning.ids.ViewIdGenerator
import acr.browser.lightning.pool.ObjectPool
import acr.browser.lightning.preview.PreviewModel
import acr.browser.lightning.ssl.SslCertificateInfo
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.useragent.UserAgentProvider
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Message
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResult
import androidx.core.graphics.createBitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Creates the adaptation between a [WebView] and the [TabModel] interface used by the browser.
 */
@SuppressLint("ClickableViewAccessibility")
class TabAdapter @AssistedInject constructor(
    @Assisted override val id: Int,
    @Assisted private val tabInitializer: TabInitializer,
    @Assisted private val webViewPool: ObjectPool<WebView>,
    @Assisted private val requestHeaders: Map<String, String>,
    @Assisted private val tabWebViewClient: TabWebViewClient,
    @Assisted override var tabType: TabModel.Type,
    @Assisted private val tabCoroutineScope: TabCoroutineScope,
    @Assisted private var priority: Priority,
    private val tabPager: TabPager,
    private val tabWebChromeClientFactory: TabWebChromeClient.Factory,
    private val userAgentProvider: UserAgentProvider,
    private val viewIdGenerator: ViewIdGenerator,
    private val previewModel: PreviewModel,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val connectivityProvider: ConnectivityProvider,
) : TabModel {

    @AssistedFactory
    interface Factory {

        fun create(
            id: Int,
            tabInitializer: TabInitializer,
            webViewPool: ObjectPool<WebView>,
            requestHeaders: Map<String, String>,
            tabWebViewClient: TabWebViewClient,
            tabType: TabModel.Type,
            tabCoroutineScope: TabCoroutineScope,
            priority: Priority,
        ): TabAdapter
    }

    enum class Priority {
        LOW,
        HIGH,
    }

    private var latentInitializer: FreezableInitializer? = tabInitializer as? FreezableInitializer
    private val lifecycle = TabLifecycleState(initialForeground = priority == Priority.HIGH)
    private var initializationJob: Job? = null
    private var initializationToken: Any? = null
    private var initializing: TabInitializer? = null
    private var connectivityJob: Job? = null
    private var releaseJob: Job? = null

    private class InitializationContext(
        val owner: TabAdapter,
        val token: Any,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<InitializationContext>
    }

    private var findInPageQuery: String? = null
    private var toggleDesktop: Boolean = false
    private var navigationGeneration = 0
    private val downloadsShareFlow = MutableSharedFlow<PendingDownload>()
    private val focusSharedFlow = MutableSharedFlow<Unit>()
    private val showHideFlow = MutableSharedFlow<Boolean>()

    private val tabWebChromeClient by lazy { tabWebChromeClientFactory.create(tabCoroutineScope) }

    private fun WebView.setup() {
        webViewClient = tabWebViewClient
        webChromeClient = tabWebChromeClient
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            tabCoroutineScope.launch {
                downloadsShareFlow.emit(
                    PendingDownload(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimetype,
                        contentLength = contentLength
                    )
                )
            }
        }
        id = this@TabAdapter.id

        setCompositeOnFocusChangeListener("focus_change") { _, hasFocus ->
            tabCoroutineScope.launch {
                if (hasFocus) {
                    focusSharedFlow.emit(Unit)
                }
            }
        }

        setCompositeTouchListener("toggle", createToolbarAwareTouchListener(context))

        setCompositeTouchListener("focus") { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                tabCoroutineScope.launch {
                    focusSharedFlow.emit(Unit)
                }
            }
            false
        }

        connectivityJob = tabCoroutineScope.launch {
            connectivityProvider.hasInternetAccess.collectLatest {
                if (this@setup === webViewIfInitialized()) setNetworkAvailable(it)
            }
        }
    }

    private fun WebView.applyLifecycle() {
        settings.offscreenPreRaster = lifecycle.shouldResume
        if (lifecycle.shouldResume) {
            resumeTimers()
            onResume()
        } else {
            onPause()
            if (lifecycle.shouldPauseTimers) pauseTimers()
        }
    }

    private val previewPathDeferred = tabCoroutineScope.async {
        previewModel.previewForId(id)
    }

    private var _acquiredWebView: ObjectPool.AcquiredObject<WebView>? = null

    private val webViewMutex = Mutex()

    private suspend fun webView(): WebView {
        val generation = lifecycle.generation
        return withContext(coroutineDispatchers.main) {
            webViewMutex.withLock {
                if (!lifecycle.isCurrent(generation)) {
                    throw CancellationException("Tab WebView was released")
                }
                _acquiredWebView?.actual ?: webViewPool.acquire(
                    highPriority = priority == Priority.HIGH
                ).also {
                    if (!lifecycle.isCurrent(generation)) {
                        it.actual.destroy()
                        webViewPool.release(it)
                        throw CancellationException("Tab WebView was released during allocation")
                    }
                    _acquiredWebView = it
                    it.actual.setup()
                    it.actual.applyLifecycle()
                    tabPager.addTab(id, it.actual)
                    releaseJob = tabCoroutineScope.launch {
                        it.awaitRelease {
                            withContext(coroutineDispatchers.main) {
                                if (_acquiredWebView === it) freezeWebView(releaseToPool = false)
                            }
                        }
                    }
                }.actual
            }
        }
    }

    private fun webViewIfInitialized(): WebView? =
        _acquiredWebView?.actual?.takeUnless { lifecycle.isFrozen || lifecycle.isDestroyed }

    private val titleStateFlow = MutableStateFlow(
        latentInitializer?.initialTitle
    )

    private val faviconStateFlow = MutableStateFlow(
        latentInitializer?.let { TabModel.Favicon.Frozen } ?: TabModel.Favicon.None
    )

    private val urlStateFlow = MutableStateFlow(latentInitializer?.initialUrl.orEmpty())

    private val progressStateFlow = MutableStateFlow(100)

    private val previewStateFlow = MutableStateFlow<TabModel.Preview>(TabModel.Preview.None)

    init {
        latentInitializer?.snapshot?.let {
            tabWebViewClient.searchQuery = it.searchQuery
            tabWebViewClient.searchQuerySelection = it.searchQuerySelection
        }
        if (tabInitializer !is FreezableInitializer) {
            loadFromInitializer(tabInitializer)
        }
        tabCoroutineScope.launch {
            merge(
                tabWebViewClient.finishedSharedFlow.mapNotNull { webViewIfInitialized()?.title },
                tabWebChromeClient.titleShareFlow.filter { webViewIfInitialized() != null }
            ).collectLatest { titleStateFlow.emit(it) }
        }
        tabCoroutineScope.launch {
            merge(
                tabWebViewClient.startedSharedFlow
                    .filter { webViewIfInitialized() != null }
                    .map { TabModel.Favicon.None },
                tabWebChromeClient.faviconSharedFlow.filter { webViewIfInitialized() != null }
            ).collectLatest { faviconStateFlow.emit(it) }
        }
        tabCoroutineScope.launch {
            @OptIn(FlowPreview::class)
            tabWebViewClient.finishedSharedFlow
                .debounce(100.milliseconds)
                .mapNotNull { webViewIfInitialized()?.let { renderViewToBitmap(it) } }
                .flowOn(coroutineDispatchers.main)
                .map<Bitmap, TabModel.Preview> { bitmap ->
                    previewModel.cachePreviewForId(id, bitmap)
                    TabModel.Preview.Image(
                        previewPathDeferred.await(),
                        System.currentTimeMillis()
                    )
                }
                .onStart {
                    emit(if (latentInitializer != null) {
                        TabModel.Preview.None
                    } else {
                        TabModel.Preview.Image(
                            previewPathDeferred.await(),
                            System.currentTimeMillis()
                        )
                    })
                }
                .flowOn(coroutineDispatchers.io)
                .collectLatest { previewStateFlow.emit(it) }
        }
        tabCoroutineScope.launch {
            tabWebViewClient.urlSharedFlow.collect {
                if (webViewIfInitialized() != null) urlStateFlow.emit(it)
            }
        }
        tabCoroutineScope.launch {
            tabWebChromeClient.progressSharedFlow.collect {
                if (webViewIfInitialized() != null) progressStateFlow.emit(it)
            }
        }
    }

    private suspend fun beginNavigation(): Int {
        val context = currentCoroutineContext()
        context.ensureActive()
        val initialization = context[InitializationContext]
        if (initialization?.owner === this) {
            if (initialization.token !== initializationToken) {
                throw CancellationException("Tab initializer was superseded")
            }
        } else {
            initializationJob?.cancel()
            initializationToken = null
            initializing = null
            latentInitializer = null
        }
        return ++navigationGeneration
    }

    override suspend fun loadUrl(url: String): Unit = withContext(coroutineDispatchers.main) {
        val generation = beginNavigation()
        val view = webView()
        if (URLUtil.isNetworkUrl(url)) DshaAccel.awaitReady()
        // Ignore an old startup load if the user navigated or stopped meanwhile.
        if (generation == navigationGeneration && view === webViewIfInitialized()) {
            view.loadUrl(url, requestHeaders)
        }
    }

    override fun loadFromInitializer(tabInitializer: TabInitializer) {
        val generation = lifecycle.generation
        if (!lifecycle.isCurrent(generation)) return
        if (initializing === tabInitializer && initializationJob?.isActive == true) return
        if (tabInitializer !== latentInitializer) latentInitializer = null
        initializationJob?.cancel()
        val token = Any()
        initializationToken = token
        initializing = tabInitializer
        initializationJob = tabCoroutineScope.launch(InitializationContext(this, token)) {
            try {
                if (lifecycle.isCurrent(generation)) {
                    tabInitializer.initialize(this@TabAdapter)
                    if (lifecycle.isCurrent(generation) && latentInitializer === tabInitializer) {
                        latentInitializer = null
                    }
                }
            } finally {
                if (initializationToken === token) initializing = null
            }
        }
    }

    override suspend fun goBack(): Unit = withContext(coroutineDispatchers.main) {
        beginNavigation()
        webView().goBack()
    }

    override suspend fun canGoBack(): Boolean = webView().canGoBack()

    override fun canGoBackChanges(): Flow<Boolean> = tabWebViewClient.goBackSharedFlow

    override suspend fun goForward(): Unit = withContext(coroutineDispatchers.main) {
        beginNavigation()
        webView().goForward()
    }

    override suspend fun canGoForward(): Boolean = webView().canGoForward()

    override fun canGoForwardChanges(): Flow<Boolean> = tabWebViewClient.goForwardSharedFlow

    override suspend fun toggleDesktopAgent() {
        webView().settings.userAgentString = if (!toggleDesktop) {
            DESKTOP_USER_AGENT
        } else {
            userAgentProvider.getUserAgent()
        }

        toggleDesktop = !toggleDesktop
    }

    override suspend fun reload(): Unit = withContext(coroutineDispatchers.main) {
        beginNavigation()
        webView().reload()
    }

    override suspend fun stopLoading(): Unit = withContext(coroutineDispatchers.main) {
        beginNavigation()
        webView().stopLoading()
    }

    override suspend fun find(query: String) {
        webView().findAllAsync(query)
        findInPageQuery = query
    }

    override suspend fun findNext() {
        webView().findNext(true)
    }

    override suspend fun findPrevious() {
        webView().findNext(false)
    }

    override suspend fun clearFindMatches() {
        webView().clearMatches()
        findInPageQuery = null
    }

    override val preview: TabModel.Preview
        get() = previewStateFlow.value

    override fun previewChanges(): StateFlow<TabModel.Preview> = previewStateFlow

    override val findQuery: String?
        get() = findInPageQuery

    override var searchQuery: String
        get() = tabWebViewClient.searchQuery
        set(value) {
            tabWebViewClient.searchQuery = value
        }

    override var searchQuerySelection: Pair<Int, Int>
        get() = tabWebViewClient.searchQuerySelection
        set(value) {
            tabWebViewClient.searchQuerySelection = value
        }

    override val favicon: TabModel.Favicon
        get() = faviconStateFlow.value

    override fun faviconChanges(): StateFlow<TabModel.Favicon> = faviconStateFlow

    override val themeColor: Int
        get() = tabWebChromeClient.colorChangeStateFlow.value

    // TODO: Do something with theme color or drop it
    override fun themeColorChanges(): Flow<Int> {
        // Treat it like a SharedFlow for consistency on presenter side
        return tabWebChromeClient.colorChangeStateFlow.drop(1)
    }

    override val url: String
        get() = urlStateFlow.value

    override fun urlChanges(): Flow<String> = urlStateFlow

    override val title: String?
        get() = titleStateFlow.value

    override fun titleChanges(): StateFlow<String?> = titleStateFlow

    override suspend fun getSslCertificateInfo(): SslCertificateInfo? = webView().certificate?.let {
        SslCertificateInfo(
            issuedByCommonName = it.issuedBy.cName,
            issuedToCommonName = it.issuedTo.cName,
            issuedToOrganizationName = it.issuedTo.oName,
            issueDate = it.validNotBeforeDate,
            expireDate = it.validNotAfterDate,
            sslState = sslState
        )
    }

    override val sslState: SslState
        get() = tabWebViewClient.sslStateFlow.value

    override fun sslChanges(): StateFlow<SslState> = tabWebViewClient.sslStateFlow

    override val loadingProgress: Int
        get() = progressStateFlow.value

    override fun loadingProgress(): Flow<Int> = progressStateFlow

    override fun downloadRequests(): Flow<PendingDownload> = downloadsShareFlow

    override fun fileChooserRequests(): Flow<Intent> = tabWebChromeClient.fileChooserSharedFlow

    override fun handleFileChooserResult(activityResult: ActivityResult) {
        tabWebChromeClient.onResult(activityResult)
    }

    override fun showCustomViewRequests(): Flow<Unit> = tabWebChromeClient.showCustomViewSharedFlow

    override fun hideCustomViewRequests(): Flow<Unit> = tabWebChromeClient.hideCustomViewObservable

    override fun hideCustomView() {
        tabWebChromeClient.hideCustomView()
    }

    override suspend fun handleMessage(message: Message) {
        message.apply {
            (obj as WebView.WebViewTransport).webView = webView()
        }.sendToTarget()
    }

    override fun createWindowRequests(): Flow<TabInitializer> =
        tabWebChromeClient.createWindowSharedFlow

    override fun closeWindowRequests(): Flow<Unit> = tabWebChromeClient.closeWindowSharedFlow

    override fun focusRequests(): Flow<Unit> = focusSharedFlow
    override fun showHideToolbar(): Flow<Boolean> = showHideFlow

    override suspend fun foreground(): Unit = withContext(coroutineDispatchers.main) {
        if (!lifecycle.foreground()) return@withContext
        priority = Priority.HIGH
        val view = webView()
        if (!lifecycle.shouldResume) return@withContext
        view.applyLifecycle()
        latentInitializer?.let(::loadFromInitializer)
        tabPager.selectTab(id)
    }

    override suspend fun background(backgroundAll: Boolean): Unit = withContext(coroutineDispatchers.main) {
        lifecycle.background(backgroundAll)
        priority = Priority.LOW
        webViewIfInitialized()?.applyLifecycle()
    }

    override suspend fun freeze(): Unit = withContext(coroutineDispatchers.main) {
        freezeWebView(releaseToPool = true)
    }

    private suspend fun freezeWebView(releaseToPool: Boolean) {
        val acquired = _acquiredWebView
        val releaseWatcher = releaseJob
        val snapshot = if (acquired != null) duplicationSnapshot() else null
        val pendingInitializer = latentInitializer
        navigationGeneration++
        lifecycle.freeze()
        initializationJob?.cancel()
        connectivityJob?.cancel()
        releaseJob = null
        _acquiredWebView = null
        acquired?.actual?.apply {
            // Disconnect callbacks before teardown can emit navigation/history events.
            webViewClient = WebViewClient()
            webChromeClient = null
            setDownloadListener(null)
            stopLoading()
            onPause()
            settings.offscreenPreRaster = false
            clearHistory()
            removeAllViews()
            destroy()
        }
        if (priority == Priority.HIGH) {
            tabPager.clearTab(id)
        } else {
            tabPager.removeTab(id)
        }
        if (snapshot != null) {
            latentInitializer = pendingInitializer ?: FreezableInitializer(snapshot, id = id)
        }
        // Eviction already owns the pool mutex; only an explicit release removes the entry.
        if (releaseToPool && acquired != null) {
            webViewPool.release(acquired)
            // A concurrent eviction needs this watcher to acknowledge before releasing the
            // pool mutex. Keep it alive if release is cancelled while waiting for that mutex.
            releaseWatcher?.cancel()
        }
    }

    override suspend fun destroy(): Unit = withContext(coroutineDispatchers.main) {
        if (lifecycle.isDestroyed) return@withContext
        freezeWebView(releaseToPool = true)
        lifecycle.destroy()
        viewIdGenerator.releaseViewId(id)
        previewModel.prune()
        tabCoroutineScope.cancel()
    }

    override suspend fun restore(bundle: Bundle): Unit = withContext(coroutineDispatchers.main) {
        val generation = beginNavigation()
        val view = webView()
        DshaAccel.awaitReady()
        if (generation == navigationGeneration && view === webViewIfInitialized()) {
            if (view.restoreState(bundle) == null) {
                val fallbackUrl = TabSnapshot.savedUrl(bundle).orEmpty().ifBlank { "about:blank" }
                loadUrl(fallbackUrl)
            }
        }
    }

    override suspend fun save(): Bundle = duplicationSnapshot().newBundle()

    override suspend fun duplicationSnapshot(): TabSnapshot = withContext(coroutineDispatchers.main) {
        latentInitializer?.snapshot?.withTabState(
            title = title.orEmpty(),
            url = url,
            searchQuery = searchQuery,
            searchQuerySelection = searchQuerySelection,
        ) ?: TabSnapshot.capture(
            bundle = Bundle(ClassLoader.getSystemClassLoader()).also(webView()::saveState),
            title = title.orEmpty(),
            url = url,
            searchQuery = searchQuery,
            searchQuerySelection = searchQuerySelection,
        )
    }

    private fun createToolbarAwareTouchListener(context: Context): View.OnTouchListener {
        val gestureListener = CustomGestureListener(
            ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
        )

        val touchListener = TouchListener(context, GestureDetector(context, gestureListener))

        val toggleListener = object : ToggleListener {
            override fun hideToolbar() {
                tabCoroutineScope.launch {
                    showHideFlow.emit(false)
                }
            }

            override fun showToolbar() {
                tabCoroutineScope.launch {
                    showHideFlow.emit(true)
                }
            }
        }

        gestureListener.toggleListener = toggleListener
        touchListener.toggleListener = toggleListener

        return touchListener
    }

    private suspend fun renderViewToBitmap(
        view: View,
        width: Int = view.width,
        height: Int = view.height
    ): Bitmap? = withContext(coroutineDispatchers.main) {
        // Ensure the view has been laid out
        if (width == 0 || height == 0) {
            return@withContext null
        }

        // Create a Bitmap with the specified dimensions and ARGB_8888 configuration
        val bitmap = createBitmap(width / 3, height / 3)

        // Create a Canvas to draw on the Bitmap
        val canvas = Canvas(bitmap)

        canvas.scale(0.33F, 0.33F)

        if (view !== webViewIfInitialized()) {
            bitmap.recycle()
            return@withContext null
        }
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())

        // Layout the view if it hasn't been laid out yet
        view.layout(0, 0, width, height)

        // Draw the view onto the canvas
        view.draw(canvas)

        return@withContext bitmap
    }
}
