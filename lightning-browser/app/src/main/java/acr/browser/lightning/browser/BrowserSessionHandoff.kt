package acr.browser.lightning.browser

import acr.browser.lightning.browser.tab.FreezableInitializer
import acr.browser.lightning.browser.tab.TabModel
import acr.browser.lightning.browser.tab.TabSnapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

internal class BrowserSession(
    tabs: List<Tab>,
    val selectedId: Int?,
    val isSearchBarExpanded: Boolean,
) {
    val tabs = tabs.toList()

    data class Tab(
        val id: Int,
        val type: TabModel.Type,
        val snapshot: TabSnapshot,
    ) {
        fun initializer(): FreezableInitializer = FreezableInitializer(snapshot, id = id)
    }
}

/**
 * A single-use, process-local transfer across Activity recreation, including incognito sessions.
 * Never put this state in SavedStateHandle: histories can exceed Binder's transaction limit.
 */
internal class BrowserSessionHandoff : ViewModel() {
    private var pending: BrowserSession? = null

    fun put(session: BrowserSession) {
        check(pending == null) { "A browser session is already waiting for recreation" }
        pending = session
    }

    fun take(): BrowserSession? = pending.also { pending = null }

    override fun onCleared() {
        pending = null
    }

    companion object {
        fun from(owner: ViewModelStoreOwner): BrowserSessionHandoff =
            ViewModelProvider(owner, ViewModelProvider.NewInstanceFactory())
                .get(BrowserSessionHandoff::class.java)
    }
}
