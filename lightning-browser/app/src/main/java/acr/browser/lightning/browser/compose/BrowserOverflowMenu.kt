package acr.browser.lightning.browser.compose

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserComposeState
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.menu.MenuSelection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun BrowserOverflowMenu(presenter: BrowserPresenter, browserViewState: BrowserComposeState) {
    Box {
        var dropDownExpanded by remember { mutableStateOf(false) }
        IconButton(onClick = {
            dropDownExpanded = !dropDownExpanded
        }) {
            Icon(
                painter = painterResource(R.drawable.more),
                contentDescription = "more"
            )
        }
        DropdownMenu(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .defaultMinSize(minWidth = 175.dp),
            shape = MaterialTheme.shapes.small,
            properties = PopupProperties(focusable = true, clippingEnabled = false),
            expanded = dropDownExpanded,
            onDismissRequest = { dropDownExpanded = false }
        ) {
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_new_tab)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.NEW_TAB))
                    dropDownExpanded = false
                }
            )
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_duplicate_tab)) },
                enabled = browserViewState.tabs.isNotEmpty(),
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.DUPLICATE_TAB))
                    dropDownExpanded = false
                }
            )
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_incognito)) },
                    leadingIcon = { Icon(painterResource(R.drawable.incognito_mode), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.NEW_INCOGNITO_TAB))
                        dropDownExpanded = false
                    }
                )
            }
            if (browserViewState.enableFullMenu) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_share)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.SHARE))
                        dropDownExpanded = false
                    }
                )
            }
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_history)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.HISTORY))
                        dropDownExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_downloads)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.DOWNLOADS))
                        dropDownExpanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_find)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_find_in_page), contentDescription = null) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.FIND))
                    dropDownExpanded = false
                }
            )
            if (browserViewState.enableFullMenu) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_copy)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.COPY_LINK))
                        dropDownExpanded = false
                    }
                )
                if (!browserViewState.isIncognito) {
                    DropdownMenuItem(
                        text = { DropdownText(stringResource(R.string.action_add_to_homescreen)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_add_to_home_screen), contentDescription = null) },
                        onClick = {
                            presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.ADD_TO_HOME))
                            dropDownExpanded = false
                        }
                    )
                }
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_bookmarks)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_bookmarks), contentDescription = null) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.BOOKMARKS))
                    dropDownExpanded = false
                }
            )
            if (browserViewState.enableFullMenu && !browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_add_bookmark)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_bookmark_add), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.ADD_BOOKMARK))
                        dropDownExpanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.settings)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.SETTINGS))
                    dropDownExpanded = false
                }
            )
            if (browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_exit_incognito)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.EXIT_INCOGNITO))
                        dropDownExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DropdownText(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium
)
