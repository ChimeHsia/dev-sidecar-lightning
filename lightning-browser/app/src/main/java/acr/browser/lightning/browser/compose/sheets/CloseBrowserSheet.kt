package acr.browser.lightning.browser.compose.sheets

import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserContract
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.dialog.DialogItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
fun CloseBrowserSheet(
    presenter: BrowserPresenter,
    onClick: (BrowserContract.CloseTabEvent) -> Unit
) {
    ListItemSheet(
        title = stringResource(R.string.dialog_title_close_browser),
        items = listOf(
            DialogItem(icon = R.drawable.ic_tab_close, title = R.string.close_tab) {
                onClick(BrowserContract.CloseTabEvent.CLOSE_CURRENT)
            },
            DialogItem(icon = R.drawable.ic_tab_close_right, title = R.string.close_other_tabs) {
                onClick(BrowserContract.CloseTabEvent.CLOSE_OTHERS)
            },
            DialogItem(icon = R.drawable.ic_close, title = R.string.close_all_tabs) {
                onClick(BrowserContract.CloseTabEvent.CLOSE_ALL)
            },
        ),
        presenter = presenter
    )
}
