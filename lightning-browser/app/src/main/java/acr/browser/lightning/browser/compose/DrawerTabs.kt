package acr.browser.lightning.browser.compose

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserComposeState
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.BrowserViewState
import acr.browser.lightning.browser.compose.sheets.BookmarksBottomSheet
import acr.browser.lightning.compose.imeInsetsPadding
import acr.browser.lightning.search.SuggestionsModel
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DrawerTabs(
    useBlackStatusBarStateFlow: StateFlow<Boolean?>,
    frameLayout: FrameLayout,
    browserViewState: BrowserComposeState,
    presenter: BrowserPresenter,
    suggestionsModel: SuggestionsModel,
    snackbarHostState: SnackbarHostState,
    lazyListState: LazyListState,
) {
    if (browserViewState.scrollToTab != -1) {
        LaunchedEffect(browserViewState.scrollToTab) {
            lazyListState.scrollToItem(browserViewState.scrollToTab)
            presenter.onEvent(BrowserUiEvent.TabScroll)
        }
    }

    val desiredDrawerState = if (browserViewState.openTabs) {
        DrawerValue.Open
    } else {
        DrawerValue.Closed
    }
    val drawerState = rememberDrawerState(
        initialValue = desiredDrawerState,
        confirmStateChange = {
            if (it == DrawerValue.Closed) {
                presenter.onEvent(BrowserUiEvent.TabDrawerMoved(isOpen = false))
            }
            true
        }
    )
    LaunchedEffect(desiredDrawerState) {
        if (drawerState.currentValue != desiredDrawerState &&
            drawerState.targetValue != desiredDrawerState
        ) {
            if (desiredDrawerState == DrawerValue.Open) {
                drawerState.open()
            } else {
                drawerState.close()
            }
        }
    }
    ModalNavigationDrawer(
        gesturesEnabled = drawerState.isOpen || drawerState.isAnimationRunning,
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 300.dp)) {
                Row(
                    modifier = Modifier
                        .height(64.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { presenter.onEvent(BrowserUiEvent.TabMenuClick) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tab),
                            contentDescription = stringResource(R.string.tabs)
                        )
                    }
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.tabs),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f, false),
                    state = lazyListState
                ) {
                    itemsIndexed(
                        items = browserViewState.tabs,
                        key = { _, item -> item.id },
                        contentType = { _, item -> item.isSelected },
                    ) { index, tab ->
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .fillMaxWidth()
                                .height(56.dp)
                                .animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null
                                )
                                .clip(CircleShape)
                                .background(
                                    if (tab.isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .combinedClickable(
                                    onClick = { presenter.onEvent(BrowserUiEvent.TabClick(index)) },
                                    onLongClick = {
                                        presenter.onEvent(
                                            BrowserUiEvent.TabLongClick(index)
                                        )
                                    }
                                )
                                .padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TabFavicon(tab.icon, modifier = Modifier.size(24.dp))
                            Text(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (tab.isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                text = tab.title
                            )
                            TabCloseButton(
                                onClick = { presenter.onEvent(BrowserUiEvent.TabClose(index)) }
                            )
                        }
                    }
                }
                HorizontalDivider()
                TabSheetToolbar(browserViewState, presenter)
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            BrowserStatusBar(
                browserComposeState = browserViewState,
                useBlackStatusBarStateFlow = useBlackStatusBarStateFlow,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val heightDp = remember { 56.dp }
                val height = with(LocalDensity.current) {
                    remember { heightDp.roundToPx() }
                }

                BookmarksBottomSheet(browserViewState, presenter)
                if (browserViewState.toolbarVisibility == BrowserViewState.ToolbarVisibility.FIXED) {
                    TopTabNavigationBar(
                        height = heightDp,
                        offset = remember { mutableIntStateOf(0) },
                        browserViewState = browserViewState,
                        presenter = presenter,
                        suggestionsModel = suggestionsModel
                    )
                    BrowserFindInPage(browserViewState, presenter)
                    AndroidView(
                        factory = { frameLayout },
                        modifier = Modifier
                            .imeInsetsPadding(innerPadding = innerPadding)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceDim)
                            .weight(1f, false),
                    )
                } else {
                    val currentOffset = remember { mutableIntStateOf(0) }
                    AnimateMutableOffset(
                        shouldShow = browserViewState.toolbarVisibility == BrowserViewState.ToolbarVisibility.SHOW,
                        mutableOffset = currentOffset,
                        maxOffset = height
                    )
                    Box(modifier = Modifier.weight(1f, false)) {
                        AnimatableTabContainer(
                            offsetDp = heightDp,
                            frameLayout = frameLayout,
                            fixedOffset = height,
                            mutableOffset = currentOffset,
                            innerPadding = innerPadding,
                        )
                        Column {
                            TopTabNavigationBar(
                                heightDp,
                                currentOffset,
                                browserViewState,
                                presenter,
                                suggestionsModel
                            )
                            BrowserFindInPage(browserViewState, presenter)
                        }
                    }
                }
                BrowserDialogs(browserViewState, presenter)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopTabNavigationBar(
    height: Dp,
    offset: MutableIntState,
    browserViewState: BrowserComposeState,
    presenter: BrowserPresenter,
    suggestionsModel: SuggestionsModel,
) {
    Column(
        modifier = Modifier
            .height(height)
            .offset {
                IntOffset(0, offset.intValue)
            }
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabCountButton(browserViewState) {
                presenter.onEvent(BrowserUiEvent.TabCountClick)
            }
            BrowserSearchBar(browserViewState, presenter, suggestionsModel)
            BrowserOverflowMenu(presenter, browserViewState)
        }
        Box(
            contentAlignment = Alignment.BottomCenter
        ) {
            BrowserProgressIndicator(browserViewState)
            HorizontalDivider()
        }
    }
}
