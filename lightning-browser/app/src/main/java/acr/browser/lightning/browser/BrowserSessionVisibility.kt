package acr.browser.lightning.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Keeps an already submitted background save alive until normal Activity disposal, while
 * foregrounding and explicit recreation can still cancel stale visibility transitions.
 */
internal class BrowserSessionVisibility(
    private val owner: CoroutineScope,
    pause: suspend () -> Unit,
    resume: suspend () -> Unit,
) {
    private val transitions = SupervisorJob(owner.coroutineContext[Job])
    private val controller = BrowserVisibilityController(
        scope = CoroutineScope(owner.coroutineContext + transitions),
        pause = pause,
        resume = resume,
    )
    private var hidden = false
    private var stopped = false
    private var detached = false

    fun show() {
        if (stopped) return
        hidden = false
        controller.show()
    }

    fun hide() {
        if (stopped) return
        hidden = true
        controller.hide()
    }

    suspend fun stopForRecreation() {
        stopped = true
        transitions.cancelAndJoin()
    }

    fun detach(destroy: suspend () -> Unit): Job? {
        if (detached) return null
        detached = true
        stopped = true
        val pending = transitions.children.toList()
        // A pending resume must not outlive the view. An already submitted hide finishes
        // normally, including disk IO; never start another save just because of onDestroy.
        if (!hidden) transitions.cancel()
        return owner.launch {
            try {
                pending.joinAll()
            } finally {
                transitions.cancel()
                destroy()
            }
        }
    }
}
