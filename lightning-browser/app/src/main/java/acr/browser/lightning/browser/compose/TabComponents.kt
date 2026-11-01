package acr.browser.lightning.browser.compose

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserComposeState
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.tab.TabModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun TabFavicon(icon: TabModel.Favicon, modifier: Modifier = Modifier) {
    when (icon) {
        TabModel.Favicon.Frozen -> Icon(
            modifier = modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            painter = painterResource(R.drawable.ic_ac_unit),
            contentDescription = null
        )

        is TabModel.Favicon.Icon -> Image(
            modifier = modifier.size(20.dp),
            bitmap = icon.bitmap,
            contentDescription = null
        )

        TabModel.Favicon.None -> Icon(
            modifier = modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            painter = painterResource(R.drawable.ic_language),
            contentDescription = null
        )
    }
}

@Composable
fun TabCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(modifier = modifier, onClick = onClick) {
        Icon(
            modifier = Modifier.size(18.dp),
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.close_tab)
        )
    }
}

@Composable
fun TabSheetToolbar(
    browserViewState: BrowserComposeState,
    presenter: BrowserPresenter,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(64.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(
            enabled = browserViewState.isBackEnabled,
            onClick = { presenter.onEvent(BrowserUiEvent.BackClick) }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back)
            )
        }
        IconButton(
            enabled = browserViewState.isForwardEnabled,
            onClick = { presenter.onEvent(BrowserUiEvent.ForwardClick) }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = stringResource(R.string.action_forward)
            )
        }
        IconButton(onClick = { presenter.onEvent(BrowserUiEvent.HomeClick) }) {
            Icon(
                painter = painterResource(R.drawable.ic_home),
                contentDescription = stringResource(R.string.home)
            )
        }
        IconButton(onClick = { presenter.onEvent(BrowserUiEvent.ToolsClick) }) {
            Icon(
                painter = painterResource(R.drawable.ic_build),
                contentDescription = stringResource(R.string.dialog_tools_title)
            )
        }
        IconButton(
            enabled = browserViewState.isBookmarkEnabled,
            onClick = { presenter.onEvent(BrowserUiEvent.StarClick) }
        ) {
            BookmarkIcon(browserViewState.isBookmarked)
        }
        IconButton(onClick = { presenter.onEvent(BrowserUiEvent.NewTabClick) }) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.action_new_tab)
            )
        }
    }
}
