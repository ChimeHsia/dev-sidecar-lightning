package acr.browser.lightning.browser

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.accel.DshaAccel
import acr.browser.lightning.IncognitoBrowserActivity
import acr.browser.lightning.ThemableActivity
import acr.browser.lightning.browser.keys.KeyEventAdapter
import acr.browser.lightning.browser.search.IntentExtractor
import acr.browser.lightning.browser.tab.TabPager
import acr.browser.lightning.browser.ui.TabConfiguration
import acr.browser.lightning.compose.BrowserTheme
import acr.browser.lightning.di.injector
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.search.SuggestionsModel
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * The base browser activity that governs the browsing experience for both default and incognito
 * browsers.
 */
abstract class BrowserActivity : ThemableActivity(), BrowserContract.View {

    private lateinit var windowColorMode: BrowserWindowColorMode
    private var colorModeRecreationPending = false

    @Suppress("ConvertLambdaToReference")
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { presenter.onEvent(BrowserUiEvent.FileChooserResult(it)) }

    @Inject
    internal lateinit var keyEventAdapter: KeyEventAdapter

    @Inject
    internal lateinit var presenter: BrowserPresenter

    @Inject
    internal lateinit var tabPager: TabPager

    @Inject
    internal lateinit var intentExtractor: IntentExtractor

    @Named("tab")
    @Inject
    internal lateinit var tabConfigurationProvider: StateFlow<@JvmSuppressWildcards TabConfiguration?>

    @Inject
    internal lateinit var suggestionsModel: SuggestionsModel

    @Inject
    internal lateinit var userPreferencesDataStore: UserPreferencesDataStore

    override fun onCreate(savedInstanceState: Bundle?) {

        val browserFrame = FrameLayout(this)
        val customFrame = FrameLayout(this)
        injector.browserComponentBuilder()
            .activity(this)
            .incognitoMode(this is IncognitoBrowserActivity)
            .browserFrame(browserFrame)
            .customFrame(customFrame)
            .initialIntent(intent.takeIf { savedInstanceState == null })
            .build()
            .inject(this)

        super.onCreate(savedInstanceState)

        windowColorMode = BrowserWindowColorMode(window)
        windowColorMode.restore(savedInstanceState)

        lifecycleScope.launch {
            // Keep the manifest's wide gamut default until preferences are loaded. Do not
            // attach the web content with a temporary, potentially incorrect color mode.
            if (applyWindowColorMode(userPreferencesDataStore.wideColorGamutEnabled.get())) {
                return@launch
            }
            setBrowserContent(browserFrame, customFrame)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPreferencesDataStore.wideColorGamutEnabled.values().collect {
                    if (!colorModeRecreationPending) {
                        applyWindowColorMode(it)
                    }
                }
            }
        }

        presenter.onViewAttached(this)

        tabPager.longPressListener = { id, longPress ->
            presenter.onEvent(BrowserUiEvent.PageLongPress(id, longPress))
        }

        onBackPressedDispatcher.addCallback {
            presenter.onEvent(BrowserUiEvent.NavigateBack)
        }
    }

    private fun applyWindowColorMode(wideColorGamut: Boolean): Boolean {
        if (windowColorMode.apply(wideColorGamut)) {
            colorModeRecreationPending = true
            // This job belongs to the Activity, not repeatOnLifecycle's cancellable collector.
            // Cold-start preference loading and returning from settings use the same handoff.
            lifecycleScope.launch {
                presenter.prepareForRecreation()
                recreate()
            }
        }
        return colorModeRecreationPending
    }

    override fun onSaveInstanceState(outState: Bundle) {
        windowColorMode.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun setBrowserContent(browserFrame: FrameLayout, customFrame: FrameLayout) {
        setContent {
            val currentState = presenter.state.collectAsMutableState(
                produceState = { BrowserComposeState(it) },
                updateFrom = { updateFrom(it) }
            )

            BrowserTheme(appThemeStateFlow) {
                BrowserScreen(
                    tabConfigurationProvider,
                    useBlackStatusBarStateFlow,
                    currentState,
                    presenter,
                    browserFrame,
                    customFrame,
                    suggestionsModel
                )
                if (currentState.showCustomView) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    setFullscreen(enabled = true, immersive = true)
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    setFullscreen(enabled = false, immersive = false)
                }
            }
        }
    }

    @SuppressLint("StateFlowValueCalledInComposition")
    @Composable
    fun <T, R> StateFlow<T>.collectAsMutableState(
        produceState: (T) -> R,
        updateFrom: R.(T) -> Unit
    ): R {
        val state = remember { produceState(value) }
        LaunchedEffect(null) {
            collectLatest {
                state.updateFrom(it)
            }
        }
        return state
    }

    override fun onNewIntent(intent: Intent) {
        intentExtractor.extractUrlFromIntent(intent)?.let {
            presenter.onEvent(BrowserUiEvent.NewAction(it))
        }
        super.onNewIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onViewDetached()
    }

    override fun onResume() {
        super.onResume()
        presenter.onViewShown()
        // WebView 在 Compose 里创建，Application/WebViewFactory 阶段可能还没 attach 到窗口，
        // 那时设置代理会被静默忽略；Activity 可见时补一次最可靠。
        DshaAccel.ensureProxy(this)
    }

    override fun onPostResume() {
        super.onPostResume()
        DshaAccel.ensureProxy(this)
    }

    override fun onPause() {
        super.onPause()
        presenter.onViewHidden()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyEventAdapter.adaptKeyEvent(event)?.let {
            presenter.onEvent(BrowserUiEvent.KeyComboClick(it))
            true
        } ?: super.onKeyUp(keyCode, event)
    }

    /**
     * @see BrowserContract.View.showFileChooser
     */
    override fun showFileChooser(intent: Intent) {
        launcher.launch(intent)
    }

    private fun setFullscreen(enabled: Boolean, immersive: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (enabled) {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) {
                    hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            } else {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // TODO: Animate color change
//    private fun animateColorChange(color: Int) {
//        if (!userPreferencesDataStore.colorModeEnabled.get() || userPreferencesDataStore.useTheme.get() != AppTheme.LIGHT || isIncognito()) {
//            return
//        }
//        val adapter = tabsAdapter as? DesktopTabRecyclerViewAdapter
//        val colorAnimator = ColorAnimator(defaultColor)
//        binding.toolbar.startAnimation(
//            colorAnimator.animateTo(
//                color
//            ) { mainColor, secondaryColor ->
//                if (userPreferencesDataStore.tabConfiguration.get() != TabConfiguration.DESKTOP) {
//                    backgroundDrawable.color = mainColor
//                    window.setBackgroundDrawable(backgroundDrawable)
//                } else {
//                    adapter?.updateForegroundTabColor(mainColor)
//                }
//                binding.toolbar.setBackgroundColor(mainColor)
//                binding.searchContainer.background?.tint(secondaryColor)
//            })
//    }
}
