package acr.browser.lightning.browser.tab

import acr.browser.lightning.browser.BrowserContract
import acr.browser.lightning.browser.BrowserSessionHandoff
import acr.browser.lightning.browser.BrowserSessionLifecycle
import acr.browser.lightning.browser.tab.bundle.BundleStore
import acr.browser.lightning.browser.tab.settings.TabSettings
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.di.InitialAction
import acr.browser.lightning.extensions.activeTabLimit
import acr.browser.lightning.ids.ViewIdGenerator
import acr.browser.lightning.pool.LimitedObjectPool
import acr.browser.lightning.pool.ObjectPool
import acr.browser.lightning.pool.UnlimitedObjectPool
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.search.SearchEngineProvider
import acr.browser.lightning.search.engine.search
import acr.browser.lightning.useragent.UserAgentProvider
import acr.browser.lightning.utils.isFileUrl
import android.app.ActivityManager
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The repository for tabs that implements the [BrowserContract.Model] interface. Manages the state
 * of the tabs list and adding new tabs to it or removing tabs from it.
 */
class TabsRepository @Inject constructor(
    private val webViewFactory: WebViewFactory,
    private val bundleStore: BundleStore,
    private val recentTabModel: RecentTabModel,
    private val tabFactory: TabFactory,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val userAgentProvider: UserAgentProvider,
    @InitialAction private val initialAction: BrowserContract.Action?,
    private val permissionInitializerFactory: PermissionInitializer.Factory,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val searchEngineProvider: SearchEngineProvider,
    private val viewIdGenerator: ViewIdGenerator,
    private val activityManager: ActivityManager,
    private val activity: FragmentActivity,
    appCoroutineScope: AppCoroutineScope,
) : BrowserContract.Model {

    private val isInitialized = CompletableDeferred<Unit>()
    private val tabsListStateFlow = MutableStateFlow<List<TabModel>>(emptyList())
    private val sessionLifecycle = BrowserSessionLifecycle(
        tabs = { tabsList },
        selectedId = { selectedTab?.id },
        save = {
            if (userPreferencesDataStore.restoreLostTabsEnabled.get()) {
                bundleStore.save(it)
            }
        },
        publish = { BrowserSessionHandoff.from(activity).put(it) },
    )
    private val webViewPool: Deferred<ObjectPool<WebView>> = appCoroutineScope.async {
        if (userPreferencesDataStore.limitActiveTabs.get()) {
            LimitedObjectPool(
                factory = {
                    val tabSettings =
                        TabSettings.create(userPreferencesDataStore, userAgentProvider)
                    webViewFactory.createWebView(tabSettings)
                },
                poolSize = activityManager.activeTabLimit()
            )
        } else {
            UnlimitedObjectPool(
                factory = {
                    val tabSettings =
                        TabSettings.create(userPreferencesDataStore, userAgentProvider)
                    webViewFactory.createWebView(tabSettings)
                }
            )
        }
    }

    override var selectedTab: TabModel? = null

    override suspend fun deleteTab(id: Int): Unit = withContext(coroutineDispatchers.main) {
        val tab = tabsList.forId(id)
        recentTabModel.addClosedTab(tab.save())
        tab.destroy()
        tabsList = tabsList - tab

        tabsListStateFlow.emit(tabsList)
    }

    override suspend fun deleteAllTabs(): Unit = withContext(coroutineDispatchers.main) {
        isInitialized.await()

        tabsList.forEach { it.destroy() }
        tabsList = emptyList()

        tabsListStateFlow.emit(tabsList)
    }

    override suspend fun createTab(
        tabInitializer: TabInitializer,
        tabType: TabModel.Type,
        foreground: Boolean
    ): TabModel = withContext(coroutineDispatchers.main) {
        isInitialized.await()
        createTabUnsafe(tabInitializer, tabType, foreground)
    }

    private suspend fun TabInitializer.tabId(): Int = if (this is FreezableInitializer) {
        this.id.takeIf { it != -1 } ?: viewIdGenerator.generateViewId()
    } else {
        viewIdGenerator.generateViewId()
    }

    /**
     * Creates a tab without waiting for the browser to be initialized.
     */
    private suspend fun createTabUnsafe(
        tabInitializer: TabInitializer,
        tabType: TabModel.Type,
        foreground: Boolean,
        emitUpdate: Boolean = true,
    ): TabModel = withContext(coroutineDispatchers.main) {
        val id = tabInitializer.tabId()
        val tabSettings = TabSettings.create(userPreferencesDataStore, userAgentProvider)
        val tabModel = tabFactory.constructTab(
            id = id,
            tabInitializer = tabInitializer,
            webViewPool = webViewPool.await(),
            tabType = tabType,
            tabSettings = tabSettings,
            foreground = foreground,
        )
        if (tabInitializer is FreezableInitializer) {
            tabModel.searchQuery = tabInitializer.snapshot.searchQuery
            tabModel.searchQuerySelection = tabInitializer.snapshot.searchQuerySelection
        }
        tabsList = tabsList + tabModel

        if (emitUpdate) {
            tabsListStateFlow.emit(tabsList)
        }

        tabModel
    }

    override suspend fun reopenTab(): TabModel? = withContext(coroutineDispatchers.main) {
        recentTabModel.lastClosed()?.let {
            createTab(
                FreezableInitializer(TabSnapshot.capture(it)),
                foreground = false,
            )
        }
    }

    override fun selectTab(id: Int): TabModel {
        val selected = tabsList.forId(id)
        selectedTab = selected

        return selected
    }

    override var tabsList = emptyList<TabModel>()
        private set

    override fun tabsListChanges(): Flow<List<TabModel>> = tabsListStateFlow

    override var restoredSearchBarExpanded: Boolean = false
        private set

    override suspend fun initializeTabs(): List<TabModel> =
        withContext(coroutineDispatchers.default) {
            val session = withContext(coroutineDispatchers.main) {
                BrowserSessionHandoff.from(activity).take()
            }
            if (session != null) {
                viewIdGenerator.claimViewIds(session.tabs.map { it.id })
                val restoredTabs = session.tabs.map {
                    createTabUnsafe(
                        tabInitializer = it.initializer(),
                        tabType = it.type,
                        foreground = false,
                        emitUpdate = false,
                    )
                }
                return@withContext withContext(coroutineDispatchers.main) {
                    tabsList = restoredTabs
                    selectedTab = restoredTabs.find { it.id == session.selectedId }
                    restoredSearchBarExpanded = session.isSearchBarExpanded
                    isInitialized.complete(Unit)
                    sessionLifecycle.initialized()
                    tabsListStateFlow.emit(restoredTabs)
                    restoredTabs
                }
            }
            val oldTabsDeferred = bundleStore.retrieve()
                .also { initializers -> viewIdGenerator.claimViewIds(initializers.map { it.id }) }
                .map {
                    async {
                        createTabUnsafe(
                            tabInitializer = it,
                            tabType = TabModel.Type.NORMAL,
                            foreground = false,
                            emitUpdate = false
                        )
                    }
                }

            val initialUrl = when (initialAction) {
                is BrowserContract.Action.LoadUrl -> initialAction.url
                is BrowserContract.Action.Search -> searchEngineProvider.provideSearchEngine()
                    .search(initialAction.query)

                else -> null
            }
            val newTabInitializer = if (initialUrl != null && initialUrl.isFileUrl()) {
                permissionInitializerFactory.create(initialUrl)
            } else if (initialUrl != null) {
                UrlInitializer(initialUrl)
            } else {
                null
            }

            // Wait until old tabs are loaded before creating new tab
            val oldTabs = oldTabsDeferred.awaitAll()
            val newTab = newTabInitializer?.let {
                createTabUnsafe(
                    tabInitializer = it,
                    tabType = TabModel.Type.EPHEMERAL,
                    foreground = true,
                    emitUpdate = false
                )
            }

            tabsList = if (newTab != null) {
                oldTabs + newTab
            } else {
                oldTabs
            }
            isInitialized.complete(Unit)
            sessionLifecycle.initialized()

            tabsListStateFlow.emit(tabsList)

            tabsList
        }

    override fun markAllNonEphemeral() {
        tabsList.forEach { it.tabType = TabModel.Type.NORMAL }
    }

    override suspend fun prepareForRecreation(isSearchBarExpanded: Boolean) =
        withContext(coroutineDispatchers.main) {
            sessionLifecycle.prepareForRecreation(isSearchBarExpanded)
        }

    override suspend fun pause() = withContext(coroutineDispatchers.main) {
        sessionLifecycle.pause()
    }

    override suspend fun destroy() = withContext(coroutineDispatchers.main) {
        try {
            sessionLifecycle.destroy()
        } finally {
            selectedTab = null
            tabsList = emptyList()
            tabsListStateFlow.emit(emptyList())
        }
    }

    override suspend fun clean() {
        bundleStore.deleteAll()
    }

    private fun List<TabModel>.forId(id: Int): TabModel = requireNotNull(find { it.id == id })
}
