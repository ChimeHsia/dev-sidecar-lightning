package acr.browser.lightning.browser

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Applies the window request, which does not guarantee display or content wide gamut support.
 */
internal class BrowserWindowColorMode(
    private val window: Window,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    fun restore(savedInstanceState: Bundle?) {
        if (!supportsColorMode() ||
            savedInstanceState?.containsKey(STATE_COLOR_MODE) != true
        ) {
            return
        }
        window.colorMode = savedInstanceState.getInt(STATE_COLOR_MODE)
    }

    fun save(outState: Bundle) {
        if (supportsColorMode()) {
            outState.putInt(STATE_COLOR_MODE, window.colorMode)
        }
    }

    /**
     * Returns true when an attached pre-Android 10 renderer needs an Activity recreation.
     */
    fun apply(wideColorGamut: Boolean): Boolean {
        if (!supportsColorMode()) {
            return false
        }
        val colorMode = if (wideColorGamut) {
            ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        } else {
            ActivityInfo.COLOR_MODE_DEFAULT
        }
        if (window.colorMode == colorMode) {
            return false
        }
        // Android 8-9 only configure the renderer gamut when creating ViewRootImpl.
        // Android 10 updates it in performTraversals when the requested color mode changes.
        val needsRecreation = sdkInt < Build.VERSION_CODES.Q &&
            window.peekDecorView()?.isAttachedToWindow == true
        window.colorMode = colorMode
        return needsRecreation
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    private fun supportsColorMode(): Boolean = sdkInt >= Build.VERSION_CODES.O

    private companion object {
        const val STATE_COLOR_MODE = "browser.windowColorMode"
    }
}
