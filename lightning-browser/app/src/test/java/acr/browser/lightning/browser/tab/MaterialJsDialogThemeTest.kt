package acr.browser.lightning.browser.tab

import acr.browser.lightning.AppTheme
import acr.browser.lightning.DefaultBrowserActivity
import acr.browser.lightning.IncognitoBrowserActivity
import acr.browser.lightning.R
import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.ThemableActivity
import acr.browser.lightning.browser.view.CustomViewCoordinator
import acr.browser.lightning.browser.webrtc.WebRtcPermissionsModel
import acr.browser.lightning.compose.asColorScheme
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.FakeCoroutineDispatchers
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.device.ScreenSize
import acr.browser.lightning.favicon.FaviconModel
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.DisplayColorPreferences
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.preference.datastore.PreferenceDataStoreRule
import acr.browser.lightning.utils.ThreadSafeFileProvider
import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.KeyEvent
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import acr.browser.lightning.databinding.DialogJsPromptBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.lang.reflect.Proxy

/**
 * Runs the real ThemableActivity theme collector and TabWebChromeClient dialogs.
 * The harness uses each browser's actual manifest launch theme without starting proxy/DI.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = TestApplication::class, sdk = [SDK_VERSION])
class MaterialJsDialogThemeTest(
    private val appTheme: AppTheme,
    private val systemDark: Boolean,
    private val incognito: Boolean,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, systemDark={1}, incognito={2}")
        fun themes(): List<Array<Any>> = listOf(
            AppTheme.LIGHT to false,
            AppTheme.DARK to false,
            AppTheme.BLACK to false,
            AppTheme.SYSTEM to false,
            AppTheme.SYSTEM to true,
        ).flatMap { (theme, dark) ->
            listOf(false, true).map { privateMode -> arrayOf(theme, dark, privateMode) }
        }
    }

    class ThemeHostActivity : ThemableActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            // Flows are assigned before super, matching browser/settings injection order.
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }

    @get:Rule
    val colors = PreferenceDataStoreRule()

    private lateinit var controller: ActivityController<ThemeHostActivity>
    private lateinit var activity: ThemeHostActivity
    private lateinit var chrome: TabWebChromeClient
    private lateinit var webView: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val selectedTheme = MutableStateFlow<AppTheme?>(null)

    @Before
    fun startWithBrowserLaunchThemeAndUserPreference() {
        RuntimeEnvironment.setQualifiers(if (systemDark) "+night" else "+notnight")
        val application = RuntimeEnvironment.getApplication() as TestApplication
        controller = Robolectric.buildActivity(ThemeHostActivity::class.java)
        activity = controller.get()
        val browserClass = if (incognito) IncognitoBrowserActivity::class.java else DefaultBrowserActivity::class.java
        val info = application.packageManager.getActivityInfo(ComponentName(application, browserClass), 0)
        activity.setTheme(info.themeResource)
        selectedTheme.value = appTheme
        activity.appThemeStateFlow = selectedTheme
        activity.useBlackStatusBarStateFlow = MutableStateFlow(false)
        controller.setup().visible()
        shadowOf(Looper.getMainLooper()).idle()

        val logger = object : Logger {
            override fun log(tag: String, message: String) = Unit
            override fun log(tag: String, message: String, throwable: Throwable) = Unit
        }
        val dispatchers = FakeCoroutineDispatchers(Dispatchers.Main)
        val appScope = AppCoroutineScope(scope)
        val files = ThreadSafeFileProvider(appScope, dispatchers) {
            File(application.cacheDir, "material-js-dialog")
        }
        chrome = TabWebChromeClient(
            activity,
            FaviconModel(application, logger, dispatchers, files),
            UserPreferencesDataStore(
                application, ScreenSize(application),
                BuildInfo(application.packageName, BuildType.DEBUG, 1, true),
                DisplayColorPreferences(colors.dataStore),
            ),
            WebRtcPermissionsModel(),
            TabCoroutineScope(scope),
            CustomViewCoordinator(FrameLayout(activity)),
        )
        webView = WebView(activity)
    }

    @After
    fun tearDown() {
        ShadowDialog.getShownDialogs().forEach { it.dismiss() }
        if (::webView.isInitialized) webView.destroy()
        if (::controller.isInitialized) controller.pause().stop().destroy()
        scope.cancel()
    }

    @Test
    fun `manifest launch theme can show prompt before preferences arrive`() {
        val application = RuntimeEnvironment.getApplication()
        val pending = Robolectric.buildActivity(ThemeHostActivity::class.java)
        val host = pending.get()
        val browserClass = if (incognito) IncognitoBrowserActivity::class.java else DefaultBrowserActivity::class.java
        val info = application.packageManager.getActivityInfo(ComponentName(application, browserClass), 0)
        host.setTheme(info.themeResource)
        host.appThemeStateFlow = MutableStateFlow(null)
        host.useBlackStatusBarStateFlow = MutableStateFlow(false)
        try {
            pending.setup().visible()
            shadowOf(Looper.getMainLooper()).idle()
            val binding = DialogJsPromptBinding.inflate(LayoutInflater.from(host))
            var confirmed = false
            val dialog = MaterialAlertDialogBuilder(host)
                .setView(binding.root)
                .setPositiveButton(R.string.action_ok) { _, _ -> confirmed = true }
                .show()
            assertThat(dialog.isShowing).isTrue()
            assertThat(binding.jsPromptInputLayout).isInstanceOf(TextInputLayout::class.java)
            clickButton(dialog, AlertDialog.BUTTON_POSITIVE)
            assertThat(confirmed).isTrue()
        } finally {
            pending.pause().stop().destroy()
        }
    }

    @Test
    fun `confirm buttons and back complete the actual JavaScript result`() {
        for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, null)) {
            val result = Result<JsResult>(JsResult::class.java)
            assertThat(chrome.onJsConfirm(webView, "https://example.test", "Confirm?", result.value)).isTrue()
            val dialog = shownDialog()
            if (button == null) back(dialog) else clickButton(dialog, button)
            if (button == AlertDialog.BUTTON_POSITIVE) {
                assertThat(result.completed).containsExactly(true)
            } else {
                result.assertCancelled()
            }
        }
    }

    @Test
    fun `prompt inflates Material text input and returns edited text`() {
        val result = Result<JsPromptResult>(JsPromptResult::class.java)
        assertThat(chrome.onJsPrompt(webView, "https://example.test", "Name?", "initial", result.value)).isTrue()
        val dialog = shownDialog()
        val inputLayout = dialog.findViewById<TextInputLayout>(R.id.js_prompt_input_layout)
        assertThat(inputLayout).isNotNull()
        val edit = dialog.findViewById<TextInputEditText>(R.id.js_prompt_edittext)!!
        assertThat(edit.text.toString()).isEqualTo("initial")
        edit.setText("edited value")
        clickButton(dialog, AlertDialog.BUTTON_POSITIVE)
        assertThat(result.completed).containsExactly(true)
        assertThat(JsPromptResult::class.java.getMethod("getStringResult").invoke(result.value))
            .isEqualTo("edited value")
    }

    @Test
    fun `prompt negative and back cancel without confirming`() {
        for (useBack in listOf(false, true)) {
            val result = Result<JsPromptResult>(JsPromptResult::class.java)
            chrome.onJsPrompt(webView, "https://example.test", "Name?", "initial", result.value)
            val dialog = shownDialog()
            if (useBack) back(dialog) else clickButton(dialog, AlertDialog.BUTTON_NEGATIVE)
            result.assertCancelled()
        }
    }

    @Test
    fun `alert and before unload show Material dialogs with working callbacks`() {
        val alert = Result<JsResult>(JsResult::class.java)
        chrome.onJsAlert(webView, "https://example.test", "Alert", alert.value)
        clickButton(shownDialog(), AlertDialog.BUTTON_POSITIVE)
        assertThat(alert.completed).containsExactly(true)
        val unload = Result<JsResult>(JsResult::class.java)
        chrome.onJsBeforeUnload(webView, "https://example.test", "Leave?", unload.value)
        clickButton(shownDialog(), AlertDialog.BUTTON_NEGATIVE)
        unload.assertCancelled()
    }

    @Test
    fun `live user theme changes keep confirm and prompt usable`() {
        for (next in listOf(AppTheme.DARK, AppTheme.LIGHT, AppTheme.BLACK, AppTheme.SYSTEM)) {
            selectedTheme.value = next
            shadowOf(Looper.getMainLooper()).idle()
            val confirm = Result<JsResult>(JsResult::class.java)
            chrome.onJsConfirm(webView, "https://example.test", "Confirm?", confirm.value)
            clickButton(shownDialog(), AlertDialog.BUTTON_POSITIVE)
            assertThat(confirm.completed).containsExactly(true)
            val prompt = Result<JsPromptResult>(JsPromptResult::class.java)
            chrome.onJsPrompt(webView, "https://example.test", "Name?", "value", prompt.value)
            val dialog = shownDialog()
            assertThat(dialog.findViewById<TextInputLayout>(R.id.js_prompt_input_layout)).isNotNull()
            back(dialog)
            prompt.assertCancelled()
        }
    }

    private fun shownDialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, "test"))
            .isEqualTo(selectedTheme.value!!.asColorScheme(systemDark).surface.toArgb())
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            assertThat(it.isShowing).isTrue()
            assertThat(it.getButton(AlertDialog.BUTTON_POSITIVE).isShown).isTrue()
        }
    }

    private fun back(dialog: AlertDialog) {
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(dialog.isShowing).isFalse()
    }

    private fun clickButton(dialog: AlertDialog, button: Int) {
        dialog.getButton(button).performClick()
        // AppCompat dispatches button listeners and dismissal through its main Handler.
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(dialog.isShowing).isFalse()
    }

    private class Result<T : JsResult>(type: Class<T>) {
        val completed = mutableListOf<Boolean>()
        private val receiverType = Class.forName("android.webkit.JsResult\$ResultReceiver")
        private val receiver = Proxy.newProxyInstance(receiverType.classLoader, arrayOf(receiverType)) { _, method, args ->
            if (method.name == "onJsResultComplete") {
                completed += JsResult::class.java.getMethod("getResult").invoke(args!![0]) as Boolean
            }
            null
        }
        val value: T = type.getConstructor(receiverType).newInstance(receiver)

        fun assertCancelled() {
            // Robolectric's JsResult shadow records cancel() without invoking ResultReceiver.
            assertThat(shadowOf(value).wasCancelled()).isTrue()
            assertThat(completed).isEmpty()
        }
    }
}
