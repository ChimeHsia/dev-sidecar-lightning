package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.adblock.AdBlocker
import acr.browser.lightning.adblock.allowlist.AllowListModel
import acr.browser.lightning.browser.tab.settings.RenderingMode
import acr.browser.lightning.browser.tab.settings.TabSettings
import acr.browser.lightning.browser.tab.settings.TextSize
import acr.browser.lightning.browser.view.CompositeTouchListener
import acr.browser.lightning.browser.view.CustomViewCoordinator
import acr.browser.lightning.browser.view.WebViewLongPressHandler
import acr.browser.lightning.browser.webrtc.WebRtcPermissionsModel
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.FakeCoroutineDispatchers
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.connectivity.ConnectivityProvider
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.device.ScreenSize
import acr.browser.lightning.favicon.FaviconModel
import acr.browser.lightning.ids.ViewIdGenerator
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.log.Logger
import acr.browser.lightning.pool.LimitedObjectPool
import acr.browser.lightning.preference.DisplayColorPreferences
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.preference.datastore.PreferenceDataStoreRule
import acr.browser.lightning.preview.PreviewModel
import acr.browser.lightning.ssl.SessionSslWarningPreferences
import acr.browser.lightning.useragent.UserAgentProvider
import acr.browser.lightning.utils.IntentUtils
import acr.browser.lightning.utils.ThreadSafeFileProvider
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import androidx.webkit.internal.AssetHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.fakes.RoboWebSettings
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    application = TestApplication::class,
    sdk = [SDK_VERSION],
    shadows = [TabAdapterLifecycleTest.HostAssetHelper::class],
)
class TabAdapterLifecycleTest {

    @Implements(value = AssetHelper::class, isInAndroidSdk = false)
    class HostAssetHelper {
        companion object {
            @Implementation
            @JvmStatic
            fun getCanonicalDirPath(directory: File): String =
                // AndroidX appends "/" to host canonical paths. Normalize both sides of its
                // real directory containment checks when these tests run on Windows.
                directory.canonicalPath.replace('\\', '/').trimEnd('/') + "/"
        }
    }

    @get:Rule
    val colorDataStore = PreferenceDataStoreRule()

    @After
    fun resetProcessTimerIntent() {
        TabLifecycleState(initialForeground = false).foreground()
    }

    @Test
    fun `background creation pauses the view without pausing global timers or blocking preload`() = runTest {
        val fixture = Fixture(this)
        fixture.createTab(UrlInitializer("about:blank"))
        runCurrent()

        val view = fixture.views.single()
        assertThat(view.loads).containsExactly("about:blank")
        assertThat(view.pauses).isPositive()
        assertThat(view.resumes).isZero()
        assertThat(view.timerPauses).isZero()
        assertThat(view.timerResumes).isZero()
        assertThat(view.settings.offscreenPreRaster).isFalse()
    }

    @Test
    fun `switching tabs pauses only the view and app background pauses global timers`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.createTab(UrlInitializer("about:blank")).adapter
        runCurrent()
        tab.foreground()
        val view = fixture.views.single()
        assertThat(view.settings.offscreenPreRaster).isTrue()

        tab.background(backgroundAll = false)
        assertThat(view.settings.offscreenPreRaster).isFalse()
        assertThat(view.timerPauses).isZero()

        tab.background(backgroundAll = true)
        assertThat(view.timerPauses).isEqualTo(1)
        val resumes = view.timerResumes
        tab.foreground()
        assertThat(view.settings.offscreenPreRaster).isTrue()
        assertThat(view.timerResumes).isGreaterThan(resumes)
        assertThat(fixture.container.childCount).isEqualTo(1)
    }

    @Test
    fun `allocation delayed until after app background inherits paused state`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        val tab = fixture.createTab(object : TabInitializer {
            override suspend fun initialize(tab: TabModel) {
                ready.await()
                tab.loadUrl("about:blank")
            }
        }, foreground = true).adapter
        runCurrent()
        assertThat(fixture.views).isEmpty()
        tab.background(backgroundAll = true)
        ready.complete(Unit)
        runCurrent()

        val view = fixture.views.single()
        assertThat(view.loads).containsExactly("about:blank")
        assertThat(view.resumes).isZero()
        assertThat(view.timerResumes).isZero()
        assertThat(view.timerPauses).isEqualTo(1)
        assertThat(view.settings.offscreenPreRaster).isFalse()
    }

    @Test
    fun `old backgroundAll intent cannot pause timers after a different tab resumes`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        val background = fixture.createTab(object : TabInitializer {
            override suspend fun initialize(tab: TabModel) {
                ready.await()
                tab.loadUrl("about:blank")
            }
        }).adapter
        runCurrent()
        background.background(backgroundAll = true)
        val selected = fixture.createTab(UrlInitializer("about:blank")).adapter
        runCurrent()
        selected.foreground()
        ready.complete(Unit)
        runCurrent()

        val lateView = fixture.views.last()
        assertThat(lateView.timerPauses).isZero()
        assertThat(lateView.timerResumes).isZero()
        assertThat(lateView.resumes).isZero()
        assertThat(lateView.settings.offscreenPreRaster).isFalse()
    }

    @Test
    fun `concurrent foreground calls run lazy initializer once and preserve later background state`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        var initializations = 0
        val initializer = FreezableInitializer(
            bundle = Bundle(),
            delegate = object : TabInitializer {
                override suspend fun initialize(tab: TabModel) {
                    initializations++
                    ready.await()
                    tab.loadUrl("about:blank")
                }
            },
            initialTitle = "Frozen",
            id = 1,
        )
        val tab = fixture.createTab(initializer).adapter
        runCurrent()
        assertThat(fixture.views).isEmpty()
        launch { tab.foreground() }
        launch { tab.foreground() }
        runCurrent()
        assertThat(initializations).isEqualTo(1)
        tab.background(backgroundAll = false)
        val view = fixture.views.single()
        val resumes = view.resumes
        ready.complete(Unit)
        runCurrent()

        assertThat(view.loads).containsExactly("about:blank")
        assertThat(view.resumes).isEqualTo(resumes)
        assertThat(view.settings.offscreenPreRaster).isFalse()
    }

    @Test
    fun `freeze disconnects teardown callbacks and late events do not reacquire a view`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.createTab(UrlInitializer("about:original"))
        runCurrent()
        tab.client.urlSharedFlow.emit("about:original")
        tab.client.finishedSharedFlow.emit(Unit)
        runCurrent()
        tab.adapter.freeze()
        tab.client.finishedSharedFlow.emit(Unit)
        tab.client.urlSharedFlow.emit("about:stale")
        fixture.connectivity.hasInternetAccess.value = false
        advanceTimeBy(200)
        runCurrent()

        val released = fixture.views.single()
        assertThat(released.destroyed).isTrue()
        assertThat(released.callbackWasDisconnected).isTrue()
        assertThat(tab.adapter.url).isEqualTo("about:original")
        assertThat(fixture.views).hasSize(1)

        tab.adapter.foreground()
        runCurrent()
        assertThat(fixture.views).hasSize(2)
        assertThat(fixture.views.last().restoredUrls).containsExactly("about:original")
        tab.adapter.destroy()
        tab.adapter.foreground()
        tab.adapter.loadFromInitializer(UrlInitializer("about:late"))
        tab.client.finishedSharedFlow.emit(Unit)
        advanceTimeBy(200)
        runCurrent()
        assertThat(fixture.views).hasSize(2)
    }

    @Test
    fun `freeze cancels a pending initializer instead of allocating after release`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        val tab = fixture.createTab(object : TabInitializer {
            override suspend fun initialize(tab: TabModel) {
                ready.await()
                tab.loadUrl("about:late")
            }
        }).adapter
        runCurrent()
        tab.freeze()
        ready.complete(Unit)
        runCurrent()
        assertThat(fixture.views).isEmpty()
    }

    @Test
    fun `freeze during pool allocation disposes the result and destroy cannot revive it`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        fixture.beforeAllocation = { ready.await() }
        val tab = fixture.createTab(NoOpInitializer()).adapter
        launch { tab.foreground() }
        runCurrent()
        tab.destroy()
        ready.complete(Unit)
        runCurrent()
        assertThat(fixture.views.single().destroyed).isTrue()
        assertThat(fixture.container.childCount).isZero()
        tab.foreground()
        assertThat(fixture.views).hasSize(1)
    }

    @Test
    fun `pool eviction saves freezes and later restores without retaining the destroyed view`() = runTest {
        val fixture = Fixture(this, poolSize = 1)
        val first = fixture.createTab(UrlInitializer("about:first"))
        runCurrent()
        fixture.createTab(UrlInitializer("about:second"))
        runCurrent()
        assertThat(fixture.views).hasSize(2)
        assertThat(fixture.views.first().destroyed).isTrue()
        first.client.finishedSharedFlow.emit(Unit)
        advanceTimeBy(200)
        runCurrent()
        assertThat(fixture.views).hasSize(2)

        first.adapter.foreground()
        runCurrent()
        assertThat(fixture.views).hasSize(3)
        assertThat(fixture.views[1].destroyed).isTrue()
        assertThat(fixture.views.last().restoredUrls).containsExactly("about:first")
        assertThat(fixture.views.last().resumes).isPositive()
    }

    @Test
    fun `explicit freeze lets queued eviction acknowledge before cancelling its watcher`() = runTest {
        val fixture = Fixture(this, poolSize = 1)
        val first = fixture.createTab(UrlInitializer("about:first")).adapter
        val second = fixture.createTab(NoOpInitializer()).adapter
        runCurrent()
        assertThat(fixture.views).hasSize(1)

        // Queue both main-dispatcher bodies before running either. Allocation signals the
        // watcher behind the already queued freeze, while holding the pool mutex.
        val allocating = launch(start = CoroutineStart.UNDISPATCHED) { second.foreground() }
        val freezing = launch(start = CoroutineStart.UNDISPATCHED) { first.freeze() }
        runCurrent()

        // Assert completion before joining so a broken acknowledgement fails without a timeout.
        assertThat(allocating.isCompleted).isTrue()
        assertThat(allocating.isCancelled).isFalse()
        assertThat(freezing.isCompleted).isTrue()
        assertThat(freezing.isCancelled).isFalse()
        assertThat(fixture.views).hasSize(2)
        assertThat(fixture.views.first().destroyed).isTrue()
        assertThat(fixture.views.last().destroyed).isFalse()

        first.foreground()
        runCurrent()
        assertThat(fixture.views).hasSize(3)
        assertThat(fixture.views.last().restoredUrls).containsExactly("about:first")
    }

    @Test
    fun `cancelling explicit freeze while eviction holds the pool preserves acknowledgement`() = runTest {
        val fixture = Fixture(this, poolSize = 1)
        val first = fixture.createTab(UrlInitializer("about:first")).adapter
        val second = fixture.createTab(NoOpInitializer()).adapter
        runCurrent()

        val allocating = launch(start = CoroutineStart.UNDISPATCHED) { second.foreground() }
        val freezing = launch(start = CoroutineStart.UNDISPATCHED) { first.freeze() }
        // This runs after freeze waits for the pool lock, but before the signalled watcher.
        launch { freezing.cancel() }
        runCurrent()

        assertThat(freezing.isCancelled).isTrue()
        assertThat(allocating.isCompleted).isTrue()
        assertThat(allocating.isCancelled).isFalse()
        assertThat(fixture.views).hasSize(2)
        assertThat(fixture.views.first().destroyed).isTrue()
        assertThat(fixture.views.last().destroyed).isFalse()

        // The pool must remain usable after the cancelled explicit removal.
        val third = fixture.createTab(NoOpInitializer()).adapter
        third.foreground()
        runCurrent()
        assertThat(fixture.views).hasSize(3)
        assertThat(fixture.views[1].destroyed).isTrue()
    }

    @Test
    fun `user navigation supersedes an initializer still waiting to choose its url`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        val tab = fixture.createTab(object : TabInitializer {
            override suspend fun initialize(tab: TabModel) {
                ready.await()
                tab.loadUrl("about:old")
            }
        }).adapter
        runCurrent()
        assertThat(fixture.views).isEmpty()

        tab.loadUrl("about:new")
        ready.complete(Unit)
        runCurrent()

        assertThat(fixture.views).hasSize(1)
        assertThat(fixture.views.single().loads).containsExactly("about:new")
    }

    @Test
    fun `replacement initializer clears a pending lazy initializer and its snapshot`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        val tab = fixture.createTab(FreezableInitializer(
            bundle = Bundle(),
            delegate = object : TabInitializer {
                override suspend fun initialize(tab: TabModel) {
                    ready.await()
                    tab.loadUrl("about:old")
                }
            },
            initialTitle = "Old",
            initialUrl = "about:old",
            id = 1,
        ))
        tab.adapter.foreground()
        runCurrent()
        assertThat(fixture.views.single().loads).isEmpty()

        tab.adapter.loadFromInitializer(UrlInitializer("about:new"))
        runCurrent()
        tab.client.urlSharedFlow.emit("about:new")
        runCurrent()
        ready.complete(Unit)
        runCurrent()
        tab.adapter.foreground()
        runCurrent()

        assertThat(fixture.views).hasSize(1)
        assertThat(fixture.views.single().loads).containsExactly("about:new")
        val snapshot = tab.adapter.duplicationSnapshot()
        assertThat(snapshot.url).isEqualTo("about:new")
        assertThat(snapshot.newBundle().getString("test:url")).isEqualTo("about:new")
    }

    @Test
    fun `cancelled lazy initialization can resume once in the next generation`() = runTest {
        val fixture = Fixture(this)
        val ready = CompletableDeferred<Unit>()
        var initializations = 0
        val tab = fixture.createTab(FreezableInitializer(
            bundle = Bundle(),
            delegate = object : TabInitializer {
                override suspend fun initialize(tab: TabModel) {
                    initializations++
                    ready.await()
                    tab.loadUrl("about:restored")
                }
            },
            initialTitle = "Frozen",
            id = 1,
        )).adapter
        tab.foreground()
        runCurrent()
        assertThat(initializations).isEqualTo(1)
        tab.freeze()
        runCurrent()

        launch { tab.foreground() }
        launch { tab.foreground() }
        runCurrent()
        assertThat(initializations).isEqualTo(2)
        ready.complete(Unit)
        runCurrent()
        assertThat(fixture.views).hasSize(2)
        assertThat(fixture.views.first().loads).isEmpty()
        assertThat(fixture.views.last().loads).containsExactly("about:restored")
    }

    @Test
    fun `background restore applies paused lifecycle before restoring history`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.createTab(NoOpInitializer()).adapter
        runCurrent()
        tab.background(backgroundAll = true)
        tab.restore(Bundle().apply { putString("test:url", "about:saved") })
        runCurrent()

        val view = fixture.views.single()
        assertThat(view.restoredUrls).containsExactly("about:saved")
        assertThat(view.resumes).isZero()
        assertThat(view.timerResumes).isZero()
        assertThat(view.settings.offscreenPreRaster).isFalse()
    }

    @Test
    fun `duplicate stays lazy and survives source navigation and closure`() = runTest {
        val fixture = Fixture(this)
        val source = fixture.createTab(UrlInitializer("about:source"))
        runCurrent()
        source.client.urlSharedFlow.emit("about:source")
        source.adapter.searchQuery = "source query"
        source.adapter.searchQuerySelection = 1 to 4
        val snapshot = source.adapter.duplicationSnapshot()
        val duplicate = fixture.createTab(FreezableInitializer(snapshot)).adapter
        runCurrent()

        assertThat(fixture.views).hasSize(1)
        assertThat(duplicate.url).isEqualTo("about:source")
        assertThat(duplicate.searchQuery).isEqualTo("source query")
        assertThat(duplicate.preview).isEqualTo(TabModel.Preview.None)
        assertThat(duplicate.duplicationSnapshot().sharesStorageWith(snapshot)).isTrue()

        source.adapter.searchQuery = "changed source"
        source.adapter.loadUrl("about:changed")
        source.client.urlSharedFlow.emit("about:changed")
        source.adapter.destroy()
        runCurrent()
        assertThat(duplicate.url).isEqualTo("about:source")
        assertThat(duplicate.searchQuery).isEqualTo("source query")
        assertThat(fixture.views).hasSize(1)

        duplicate.foreground()
        runCurrent()
        assertThat(fixture.views).hasSize(2)
        assertThat(fixture.views.last().restoredUrls).containsExactly("about:source")
        assertThat(fixture.views.last().loads).isEmpty()
        assertThat(duplicate.searchQuerySelection).isEqualTo(1 to 4)
    }

    @Test
    fun `cold copies share history while queries are independent and save does not allocate`() = runTest {
        val fixture = Fixture(this)
        val snapshot = TabSnapshot.capture(
            Bundle().apply { putString("test:url", "about:shared") },
            title = "Shared",
            url = "about:shared",
            searchQuery = "initial",
        )
        val first = fixture.createTab(FreezableInitializer(snapshot)).adapter
        val second = fixture.createTab(FreezableInitializer(first.duplicationSnapshot())).adapter
        first.searchQuery = "changed"
        runCurrent()

        assertThat(second.searchQuery).isEqualTo("initial")
        assertThat(second.url).isEqualTo("about:shared")
        assertThat(first.duplicationSnapshot().sharesStorageWith(second.duplicationSnapshot())).isTrue()
        val reopened = fixture.createTab(FreezableInitializer(TabSnapshot.capture(second.save()))).adapter
        runCurrent()
        assertThat(reopened.title).isEqualTo("Shared")
        assertThat(reopened.url).isEqualTo("about:shared")
        assertThat(reopened.searchQuery).isEqualTo("initial")
        assertThat(fixture.views).isEmpty()
    }

    @Test
    fun `rejected history falls back to saved URL and missing URL falls back to blank`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.createTab(NoOpInitializer()).adapter
        tab.foreground()
        val view = fixture.views.single()
        view.rejectRestores = true

        tab.restore(TabSnapshot.capture(
            Bundle().apply { putString("test:url", "about:invalid") },
            url = "about:fallback",
        ).newBundle())
        assertThat(view.loads).containsExactly("about:fallback")

        tab.restore(Bundle())
        assertThat(view.loads).containsExactly("about:fallback", "about:blank")
    }

    private class RecordingWebView(context: Context) : WebView(context) {
        private val recordedSettings: WebSettings by lazy {
            // RoboWebSettings deliberately stubs this property as always false.
            object : RoboWebSettings() {
                private var preRaster = false
                override fun setOffscreenPreRaster(enabled: Boolean) { preRaster = enabled }
                override fun getOffscreenPreRaster(): Boolean = preRaster
            }
        }

        override fun getSettings(): WebSettings = recordedSettings

        val loads = mutableListOf<String>()
        val restoredUrls = mutableListOf<String>()
        var pauses = 0
        var resumes = 0
        var timerPauses = 0
        var timerResumes = 0
        var destroyed = false
        var callbackWasDisconnected = false
        var rejectRestores = false

        init {
            tag = CompositeTouchListener()
            settings.offscreenPreRaster = true
        }

        override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
            check(!destroyed)
            loads += url
        }

        override fun saveState(outState: Bundle): WebBackForwardList? {
            check(!destroyed)
            outState.putString("test:url", loads.lastOrNull() ?: restoredUrls.lastOrNull())
            return SavedHistory()
        }

        override fun restoreState(inState: Bundle): WebBackForwardList? {
            check(!destroyed)
            restoredUrls += inState.getString("test:url").orEmpty()
            return if (rejectRestores) null else SavedHistory()
        }

        override fun onPause() { pauses++ }
        override fun onResume() { resumes++ }
        override fun pauseTimers() { timerPauses++ }
        override fun resumeTimers() { timerResumes++ }

        override fun stopLoading() {
            callbackWasDisconnected = webViewClient !is TabWebViewClient && webChromeClient == null
            webViewClient.onPageStarted(this, "about:teardown", null)
        }

        override fun setNetworkAvailable(networkUp: Boolean) {
            check(!destroyed)
        }

        override fun destroy() {
            check(!destroyed)
            destroyed = true
            super.destroy()
        }
    }

    private class SavedHistory : WebBackForwardList() {
        override fun getSize(): Int = 1
        override fun getCurrentIndex(): Int = 0
        override fun getCurrentItem(): WebHistoryItem? = null
        override fun getItemAtIndex(index: Int): WebHistoryItem? = null
        public override fun clone(): WebBackForwardList = SavedHistory()
    }

    private data class TestTab(val adapter: TabAdapter, val client: TabWebViewClient)

    private inner class Fixture(private val scope: TestScope, poolSize: Int = 8) {
        private val application = RuntimeEnvironment.getApplication() as TestApplication
        private val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .setup().get().apply { setTheme(R.style.Theme_LightTheme) }
        private val dispatchers = FakeCoroutineDispatchers(scope.testScheduler)
        private val appScope = AppCoroutineScope(scope.backgroundScope)
        private val logger = object : Logger {
            override fun log(tag: String, message: String) = Unit
            override fun log(tag: String, message: String, throwable: Throwable) = Unit
        }
        private val preferences = UserPreferencesDataStore(
            application, ScreenSize(application),
            BuildInfo(application.packageName, BuildType.DEBUG, 1, true),
            DisplayColorPreferences(colorDataStore.dataStore),
        )
        private val fileProvider = ThreadSafeFileProvider(appScope, dispatchers) {
            File(application.cacheDir, "lifecycle-test")
        }
        private val ids = ViewIdGenerator()
        private val preview = PreviewModel(logger, ids, fileProvider, dispatchers, appScope)
        private val favicons = FaviconModel(application, logger, dispatchers, fileProvider)
        private val storage = InternalStoragePathHandler(application, application.filesDir)
        private val settings = TabSettings(
            openAvailableAppsEnabled = false, algorithmicDarkeningEnabled = false,
            doNotTrackEnabled = false, saveDataEnabled = false,
            removeIdentifyingHeadersEnabled = false, textEncoding = "UTF-8",
            renderingMode = RenderingMode.NORMAL, locationEnabled = false, userAgent = "test",
            javaScriptEnabled = true, textReflowEnabled = false, blockImagesEnabled = false,
            popupsEnabled = true, useWideViewPortEnabled = true, overviewModeEnabled = true,
            textSize = TextSize.MEDIUM, cookiesEnabled = true, blockThirdPartyCookiesEnabled = false,
        )
        val connectivity = ConnectivityProvider(
            appScope, application.getSystemService(ConnectivityManager::class.java), logger,
        )
        val container = FrameLayout(activity)
        private val pager = TabPager(container, WebViewLongPressHandler(activity))
        val views = mutableListOf<RecordingWebView>()
        var beforeAllocation: suspend () -> Unit = {}
        private val pool = LimitedObjectPool(
            factory = {
                beforeAllocation()
                RecordingWebView(activity).also(views::add) as WebView
            },
            poolSize = poolSize,
        )
        private var nextId = 1

        fun createTab(initializer: TabInitializer, foreground: Boolean = false): TestTab {
            val tabScope = TabCoroutineScope(CoroutineScope(
                scope.backgroundScope.coroutineContext +
                    SupervisorJob(scope.backgroundScope.coroutineContext[Job])
            ))
            val client = TabWebViewClient(
                adBlocker = CompletableDeferred(object : AdBlocker {
                    override fun isAd(uri: Uri) = false
                }),
                allowListModel = object : AllowListModel {
                    override fun isUrlAllowedAds(url: String) = true
                    override fun addUrlToAllowList(url: String) = Unit
                    override fun removeUrlFromAllowList(url: String) = Unit
                },
                urlHandler = UrlHandler(activity, logger, IntentUtils(activity, logger), false),
                headers = emptyMap(),
                sslWarningPreferences = SessionSslWarningPreferences(),
                textReflow = object : TextReflow { override fun provideJs() = "" },
                logger = logger,
                faviconCacheDirThreadSafeFileProvider = fileProvider,
                generatedHtmlDirThreadSafeFileProvider = fileProvider,
                cacheStoragePathHandler = storage,
                filesStoragePathHandler = storage,
                tabCoroutineScope = tabScope,
                tabSettings = settings,
            )
            return TestTab(
                adapter = TabAdapter(
                    id = nextId++,
                    tabInitializer = initializer,
                    webViewPool = pool,
                    requestHeaders = emptyMap(),
                    tabWebViewClient = client,
                    tabType = TabModel.Type.NORMAL,
                    tabCoroutineScope = tabScope,
                    priority = if (foreground) TabAdapter.Priority.HIGH else TabAdapter.Priority.LOW,
                    tabPager = pager,
                    tabWebChromeClientFactory = object : TabWebChromeClient.Factory {
                        override fun create(tabCoroutineScope: TabCoroutineScope) = TabWebChromeClient(
                            activity, favicons, preferences, WebRtcPermissionsModel(),
                            tabCoroutineScope, CustomViewCoordinator(FrameLayout(activity)),
                        )
                    },
                    userAgentProvider = object : UserAgentProvider {
                        override suspend fun getUserAgent() = "test"
                    },
                    viewIdGenerator = ids,
                    previewModel = preview,
                    coroutineDispatchers = dispatchers,
                    connectivityProvider = connectivity,
                ),
                client = client,
            )
        }
    }
}
