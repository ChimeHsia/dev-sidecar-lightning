package acr.browser.lightning.settings.framework.compose

import acr.browser.lightning.settings.framework.SettingsClickableState
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SettingsClickable(
    state: SettingsClickableState,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(enabled = state.enabled) { onClick() },
        headlineContent = { Text(state.title) },
        supportingContent = state.summary?.let { { Text(it) } },
        leadingContent = state.icon?.let { { SettingsIcon(it) } },
        colors = settingsListItemColors(state.enabled),
    )
}
