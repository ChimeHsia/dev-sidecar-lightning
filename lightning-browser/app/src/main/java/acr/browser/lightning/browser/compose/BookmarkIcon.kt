package acr.browser.lightning.browser.compose

import acr.browser.lightning.R
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
fun BookmarkIcon(
    isBookmarked: Boolean,
) {
    Icon(
        painter = painterResource(
            if (isBookmarked) R.drawable.ic_bookmark_remove else R.drawable.ic_bookmark_add
        ),
        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        contentDescription = stringResource(R.string.action_add_bookmark)
    )
}
