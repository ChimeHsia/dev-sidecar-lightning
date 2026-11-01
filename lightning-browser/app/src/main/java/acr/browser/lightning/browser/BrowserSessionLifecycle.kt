package acr.browser.lightning.browser

import acr.browser.lightning.browser.tab.TabModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes persistence, recreation snapshots and disposal for one repository.
 * Call on the main dispatcher, like the TabModel operations it coordinates.
 */
internal class BrowserSessionLifecycle(
    private val tabs: () -> List<TabModel>,
    private val selectedId: () -> Int?,
    private val save: suspend (List<TabModel>) -> Unit,
    private val publish: (BrowserSession) -> Unit,
) {
    private val initialized = CompletableDeferred<Unit>()
    private val persistence = Mutex()
    private var recreating = false
    private var destroyed = false

    fun initialized() {
        initialized.complete(Unit)
    }

    suspend fun prepareForRecreation(isSearchBarExpanded: Boolean) {
        initialized.await()
        persistence.withLock {
            check(!destroyed) { "The browser session has already been destroyed" }
            if (recreating) return
            recreating = true
            val currentTabs = tabs().toList()
            currentTabs.forEach { it.background(backgroundAll = true) }
            val session = BrowserSession(
                tabs = currentTabs.map {
                    BrowserSession.Tab(it.id, it.tabType, it.duplicationSnapshot())
                },
                selectedId = selectedId(),
                isSearchBarExpanded = isSearchBarExpanded,
            )
            publish(session)
        }
    }

    suspend fun pause() {
        if (destroyed) return
        // Pause even when persistence is disabled, initialization is incomplete or a snapshot
        // has already been handed off. Cancellation of disk IO must not leave views running.
        tabs().toList().forEach { it.background(backgroundAll = true) }
        if (!initialized.isCompleted) return
        persistence.withLock {
            if (!recreating && !destroyed) save(tabs().toList())
        }
    }

    suspend fun destroy() {
        // Only teardown is non-cancellable. Session saving is completed before recreate().
        withContext(NonCancellable) {
            persistence.withLock {
                if (destroyed) return@withLock
                destroyed = true
                var failure: Throwable? = null
                tabs().toList().forEach {
                    try {
                        it.destroy()
                    } catch (error: Exception) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
        }
    }
}
