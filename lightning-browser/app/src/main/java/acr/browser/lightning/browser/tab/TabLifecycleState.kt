package acr.browser.lightning.browser.tab

/**
 * Target state survives lazy allocation; generations invalidate work from a released WebView.
 * Accessed on the main dispatcher, like the WebViews it describes.
 */
internal class TabLifecycleState(
    initialForeground: Boolean,
    private val timers: TimerState = processTimers,
) {
    var isForeground: Boolean = initialForeground
        private set
    var generation: Int = 0
        private set
    var isFrozen: Boolean = false
        private set
    var isDestroyed: Boolean = false
        private set

    val shouldResume: Boolean
        get() = !isFrozen && !isDestroyed && isForeground && !timers.paused

    val shouldPauseTimers: Boolean
        get() = timers.paused

    fun foreground(): Boolean {
        if (isDestroyed) return false
        isFrozen = false
        isForeground = true
        timers.paused = false
        return true
    }

    fun background(all: Boolean) {
        if (isDestroyed) return
        isForeground = false
        if (all) timers.paused = true
    }

    fun freeze() {
        generation++
        isFrozen = true
    }

    fun destroy() {
        freeze()
        isDestroyed = true
    }

    fun isCurrent(generation: Int): Boolean =
        !isFrozen && !isDestroyed && this.generation == generation

    // WebView timers are process-wide. A background tab allocated after another tab resumes
    // must not replay its old backgroundAll request and pause the foreground page.
    internal class TimerState(var paused: Boolean = false)

    private companion object {
        val processTimers = TimerState()
    }
}
