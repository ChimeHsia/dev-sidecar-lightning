package acr.browser.lightning.settings.framework.compose

import acr.browser.lightning.settings.framework.SettingsToggleState
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun SettingsToggle(
    state: SettingsToggleState,
    onToggle: (Boolean) -> Unit
) {
    var toggleState by remember { mutableStateOf(state.isChecked) }
    ListItem(
        modifier = Modifier.clickable(enabled = state.enabled) {
            toggleState = !toggleState
            onToggle(toggleState)
        },
        headlineContent = { Text(state.title) },
        supportingContent = state.summary?.let { { Text(it) } },
        leadingContent = state.icon?.let { { SettingsIcon(it) } },
        trailingContent = {
            Switch(
                enabled = state.enabled,
                checked = toggleState,
                onCheckedChange = {
                    toggleState = it
                    onToggle(toggleState)
                }
            )
        },
        colors = settingsListItemColors(state.enabled),
    )
}
