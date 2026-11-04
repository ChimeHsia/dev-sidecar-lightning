package acr.browser.lightning.compose

import acr.browser.lightning.AppTheme
import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The XML themes in styles.xml must stay in sync with the Compose color schemes, otherwise
 * dialogs and other View-based UI drift from the rest of the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class ThemeColorsTest {

    private val roles: List<Pair<String, (ColorScheme) -> Int>> = listOf(
        "colorPrimary" to { scheme: ColorScheme -> scheme.primary.toArgb() },
        "colorOnPrimary" to { scheme: ColorScheme -> scheme.onPrimary.toArgb() },
        "colorPrimaryContainer" to { scheme: ColorScheme -> scheme.primaryContainer.toArgb() },
        "colorOnPrimaryContainer" to { scheme: ColorScheme -> scheme.onPrimaryContainer.toArgb() },
        "colorSecondary" to { scheme: ColorScheme -> scheme.secondary.toArgb() },
        "colorOnSecondary" to { scheme: ColorScheme -> scheme.onSecondary.toArgb() },
        "colorSecondaryContainer" to { scheme: ColorScheme -> scheme.secondaryContainer.toArgb() },
        "colorOnSecondaryContainer" to { scheme: ColorScheme -> scheme.onSecondaryContainer.toArgb() },
        "colorTertiary" to { scheme: ColorScheme -> scheme.tertiary.toArgb() },
        "colorOnTertiary" to { scheme: ColorScheme -> scheme.onTertiary.toArgb() },
        "colorTertiaryContainer" to { scheme: ColorScheme -> scheme.tertiaryContainer.toArgb() },
        "colorOnTertiaryContainer" to { scheme: ColorScheme -> scheme.onTertiaryContainer.toArgb() },
        "colorError" to { scheme: ColorScheme -> scheme.error.toArgb() },
        "colorOnError" to { scheme: ColorScheme -> scheme.onError.toArgb() },
        "colorErrorContainer" to { scheme: ColorScheme -> scheme.errorContainer.toArgb() },
        "colorOnErrorContainer" to { scheme: ColorScheme -> scheme.onErrorContainer.toArgb() },
        ANDROID_COLOR_BACKGROUND to { scheme: ColorScheme -> scheme.background.toArgb() },
        "colorOnBackground" to { scheme: ColorScheme -> scheme.onBackground.toArgb() },
        "colorSurface" to { scheme: ColorScheme -> scheme.surface.toArgb() },
        "colorOnSurface" to { scheme: ColorScheme -> scheme.onSurface.toArgb() },
        "colorSurfaceVariant" to { scheme: ColorScheme -> scheme.surfaceVariant.toArgb() },
        "colorOnSurfaceVariant" to { scheme: ColorScheme -> scheme.onSurfaceVariant.toArgb() },
        "colorOutline" to { scheme: ColorScheme -> scheme.outline.toArgb() },
        "colorOutlineVariant" to { scheme: ColorScheme -> scheme.outlineVariant.toArgb() },
        "colorSurfaceInverse" to { scheme: ColorScheme -> scheme.inverseSurface.toArgb() },
        "colorOnSurfaceInverse" to { scheme: ColorScheme -> scheme.inverseOnSurface.toArgb() },
        "colorPrimaryInverse" to { scheme: ColorScheme -> scheme.inversePrimary.toArgb() },
        "colorSurfaceDim" to { scheme: ColorScheme -> scheme.surfaceDim.toArgb() },
        "colorSurfaceBright" to { scheme: ColorScheme -> scheme.surfaceBright.toArgb() },
        "colorSurfaceContainerLowest" to { scheme: ColorScheme -> scheme.surfaceContainerLowest.toArgb() },
        "colorSurfaceContainerLow" to { scheme: ColorScheme -> scheme.surfaceContainerLow.toArgb() },
        "colorSurfaceContainer" to { scheme: ColorScheme -> scheme.surfaceContainer.toArgb() },
        "colorSurfaceContainerHigh" to { scheme: ColorScheme -> scheme.surfaceContainerHigh.toArgb() },
        "colorSurfaceContainerHighest" to { scheme: ColorScheme -> scheme.surfaceContainerHighest.toArgb() },
    )

    @Test
    fun `light XML theme matches compose color scheme`() = assertThemeMatches(AppTheme.LIGHT)

    @Test
    fun `dark XML theme matches compose color scheme`() = assertThemeMatches(AppTheme.DARK)

    @Test
    fun `black XML theme matches compose color scheme`() = assertThemeMatches(AppTheme.BLACK)

    @Test
    fun `system theme follows the system dark mode`() {
        assertThat(AppTheme.SYSTEM.styleRes(systemDarkTheme = true))
            .isEqualTo(AppTheme.DARK.styleRes(systemDarkTheme = false))
        assertThat(AppTheme.SYSTEM.styleRes(systemDarkTheme = false))
            .isEqualTo(AppTheme.LIGHT.styleRes(systemDarkTheme = true))
    }

    private fun assertThemeMatches(appTheme: AppTheme) {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            appTheme.styleRes(systemDarkTheme = false)
        )
        val scheme = appTheme.asColorScheme(systemDarkTheme = false)
        val typedValue = TypedValue()
        SoftAssertions.assertSoftly { softly ->
            roles.forEach { (name, expected) ->
                val attr = if (name == ANDROID_COLOR_BACKGROUND) {
                    android.R.attr.colorBackground
                } else {
                    context.resources.getIdentifier(name, "attr", context.packageName)
                }
                softly.assertThat(attr).describedAs("attr $name exists").isNotZero()
                softly.assertThat(context.theme.resolveAttribute(attr, typedValue, true))
                    .describedAs("$appTheme resolves $name")
                    .isTrue()
                softly.assertThat(Integer.toHexString(typedValue.data))
                    .describedAs("$appTheme $name")
                    .isEqualTo(Integer.toHexString(expected(scheme)))
            }
        }
    }

    private companion object {
        const val ANDROID_COLOR_BACKGROUND = "android:colorBackground"
    }
}
