package acr.browser.lightning.browser

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.preference.DisplayColorPreferences
import acr.browser.lightning.preference.datastore.PreferenceDataStoreRule
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class BrowserWindowColorModeTest {

    @get:Rule
    val dataStoreRule = PreferenceDataStoreRule()

    @Test
    fun `persisted standard selection overrides wide manifest default before content attaches`() = runTest {
        DisplayColorPreferences(dataStoreRule.dataStore).wideColorGamutEnabled.set(false)
        val restoredPreferences = DisplayColorPreferences(dataStoreRule.dataStore)
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window = activity.get().window
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            assertThat(window.decorView.isAttachedToWindow).isFalse()

            val needsRecreation = BrowserWindowColorMode(window)
                .apply(restoredPreferences.wideColorGamutEnabled.get())

            assertThat(needsRecreation).isFalse()
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_DEFAULT)
        } finally {
            activity.destroy()
        }
    }

    @Test
    fun `current platform changes the real attached window without requesting recreation`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = activity.get().window
            val content = FrameLayout(activity.get())
            activity.get().setContentView(content)
            val decor = window.decorView
            assertThat(decor.isAttachedToWindow).isTrue()
            val colorMode = BrowserWindowColorMode(window)

            assertThat(colorMode.apply(true)).isFalse()
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT)
            assertThat(colorMode.apply(false)).isFalse()
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_DEFAULT)
            assertThat(window.decorView).isSameAs(decor)
            assertThat(content.isAttachedToWindow).isTrue()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `unchanged preference does not request legacy recreation`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = activity.get().window
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            val colorMode = BrowserWindowColorMode(window, sdkInt = Build.VERSION_CODES.P)

            assertThat(colorMode.apply(true)).isFalse()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `legacy window can use the saved selection before attaching its renderer`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window = activity.get().window
            assertThat(window.decorView.isAttachedToWindow).isFalse()
            val colorMode = BrowserWindowColorMode(window, sdkInt = Build.VERSION_CODES.O)

            assertThat(colorMode.apply(true)).isFalse()
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT)
            assertThat(colorMode.apply(false)).isFalse()
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_DEFAULT)
        } finally {
            activity.destroy()
        }
    }

    @Test
    fun `legacy attached window requests recreation and restores target without a loop`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val savedState = Bundle()
        try {
            val window = activity.get().window
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            assertThat(window.decorView.isAttachedToWindow).isTrue()
            val colorMode = BrowserWindowColorMode(window, sdkInt = Build.VERSION_CODES.P)

            assertThat(colorMode.apply(false)).isTrue()
            colorMode.save(savedState)
        } finally {
            activity.pause().stop().destroy()
        }

        val replacement = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window = replacement.get().window
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            val colorMode = BrowserWindowColorMode(window, sdkInt = Build.VERSION_CODES.P)

            colorMode.restore(savedState)
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_DEFAULT)
            replacement.start().resume().visible()
            assertThat(colorMode.apply(false)).isFalse()
        } finally {
            replacement.pause().stop().destroy()
        }
    }

    @Test
    fun `pre Android 8 gating leaves the actual window and saved state unchanged`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val window = activity.get().window
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            val enabledState = Bundle()
            BrowserWindowColorMode(window).save(enabledState)
            val colorMode = BrowserWindowColorMode(window, sdkInt = Build.VERSION_CODES.N)

            assertThat(colorMode.apply(false)).isFalse()
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT)
            window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            colorMode.restore(enabledState)
            assertThat(window.colorMode).isEqualTo(ActivityInfo.COLOR_MODE_DEFAULT)
            val unsupportedState = Bundle()
            colorMode.save(unsupportedState)
            assertThat(unsupportedState.isEmpty).isTrue()
        } finally {
            activity.destroy()
        }
    }
}
