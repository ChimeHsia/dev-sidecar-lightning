package acr.browser.lightning.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class BrowserVisibilityController(
    private val scope: CoroutineScope,
    private val pause: suspend () -> Unit,
    private val resume: suspend () -> Unit,
) {
    private var transition: Job? = null

    fun show() = update(resume)

    fun hide() = update(pause)

    private fun update(action: suspend () -> Unit) {
        // Saving tabs can suspend. A stale hide must not pause WebView timers
        // after the activity has already returned to the foreground.
        transition?.cancel()
        transition = scope.launch { action() }
    }
}
