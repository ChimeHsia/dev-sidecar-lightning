package acr.browser.lightning.settings.framework.compose

import androidx.annotation.DrawableRes
import acr.browser.lightning.R
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
internal fun SettingsIcon(@DrawableRes icon: Int) {
    Icon(painter = painterResource(icon), contentDescription = null)
}

@Composable
fun SettingsUpButton(onUp: () -> Unit) {
    IconButton(onClick = onUp) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.action_back)
        )
    }
}

@Composable
internal fun settingsListItemColors(enabled: Boolean): ListItemColors {
    val colors = ListItemDefaults.colors()
    return if (enabled) {
        colors
    } else {
        ListItemDefaults.colors(
            headlineColor = colors.disabledHeadlineColor,
            supportingColor = colors.disabledHeadlineColor,
            leadingIconColor = colors.disabledLeadingIconColor,
        )
    }
}
