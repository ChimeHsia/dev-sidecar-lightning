package acr.browser.lightning.settings.screens

import acr.browser.lightning.R
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.resources.ResourceProvider
import acr.browser.lightning.settings.framework.ClickableOnClick
import acr.browser.lightning.settings.framework.ClickableState
import acr.browser.lightning.settings.framework.SettingsFrameworkState
import acr.browser.lightning.settings.navigation.SettingsNavigation
import javax.inject.Inject

class RootSettingsScreen @Inject constructor(
    private val resourceProvider: ResourceProvider,
    private val buildInfo: BuildInfo,
) {
    fun createSettingsFrameworkState(): SettingsFrameworkState = SettingsFrameworkState(
        title = resourceProvider.stringResource(R.string.settings),
        content = listOf(
            ClickableState(
                icon = R.drawable.ic_block,
                title = resourceProvider.stringResource(R.string.settings_adblock),
                onClick = ClickableOnClick.Navigate(SettingsNavigation.ADBLOCK),
            ),
            ClickableState(
                icon = R.drawable.ic_tune,
                title = resourceProvider.stringResource(R.string.settings_general),
                onClick = ClickableOnClick.Navigate(SettingsNavigation.GENERAL),
            ),
            ClickableState(
                icon = R.drawable.ic_bookmarks,
                title = resourceProvider.stringResource(R.string.bookmark_settings),
                onClick = ClickableOnClick.Navigate(SettingsNavigation.BOOKMARK),
            ),
            ClickableState(
                icon = R.drawable.ic_palette,
                title = resourceProvider.stringResource(R.string.settings_display),
                onClick = ClickableOnClick.Navigate(SettingsNavigation.DISPLAY),
            ),
            ClickableState(
                icon = R.drawable.ic_shield,
                title = resourceProvider.stringResource(R.string.settings_privacy),
                onClick = ClickableOnClick.Navigate(SettingsNavigation.PRIVACY),
            ),
            ClickableState(
                icon = R.drawable.ic_build,
                title = resourceProvider.stringResource(R.string.settings_advanced),
                onClick = ClickableOnClick.Navigate(SettingsNavigation.ADVANCED),
            ),
            ClickableState(
                icon = R.drawable.ic_info,
                title = resourceProvider.stringResource(R.string.settings_about),
                summary = { resourceProvider.stringResource(R.string.settings_about_explain) },
                onClick = ClickableOnClick.Navigate(SettingsNavigation.ABOUT),
            ),
            ClickableState(
                icon = R.drawable.ic_help,
                title = resourceProvider.stringResource(R.string.faq),
                summary = { resourceProvider.stringResource(R.string.faq_description) },
                onClick = ClickableOnClick.WebLink("http://acrdevelopment.org/lightning/faq"),
            ),
        ) + if (buildInfo.buildType == BuildType.DEBUG) {
            listOf(
                ClickableState(
                    icon = R.drawable.ic_bug_report,
                    title = resourceProvider.stringResource(R.string.debug_title),
                    onClick = ClickableOnClick.Navigate(SettingsNavigation.DEBUG),
                )
            )
        } else {
            emptyList()
        }
    )
}
