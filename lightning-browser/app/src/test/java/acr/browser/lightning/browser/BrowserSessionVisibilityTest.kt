package acr.browser.lightning.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionVisibilityTest {

    @Test
    fun `normal finish lets a suspended save finish before destroying tabs`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val visibility = BrowserSessionVisibility(
            owner = this,
            pause = {
                events += "save:start"
                saved.await()
                events += "save:done"
            },
            resume = {},
        )
        visibility.hide()
        runCurrent()

        val disposal = visibility.detach { events += "destroy" }
        visibility.hide()
        visibility.show()
        runCurrent()
        assertThat(events).containsExactly("save:start")
        assertThat(disposal?.isCompleted).isFalse()

        saved.complete(Unit)
        disposal?.join()
        assertThat(events).containsExactly("save:start", "save:done", "destroy")
    }

    @Test
    fun `finish before pause dispatch still runs exactly the previously submitted pause`() = runTest {
        val events = mutableListOf<String>()
        val visibility = BrowserSessionVisibility(
            owner = this,
            pause = { events += "pause" },
            resume = { events += "resume" },
        )

        visibility.hide()
        val disposal = visibility.detach { events += "destroy" }
        disposal?.join()

        assertThat(events).containsExactly("pause", "destroy")
        assertThat(visibility.detach { events += "destroy:again" }).isNull()
    }

    @Test
    fun `returning to foreground still cancels a stale save`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val visibility = BrowserSessionVisibility(
            owner = this,
            pause = {
                events += "save:start"
                saved.await()
                events += "save:done"
            },
            resume = { events += "resume" },
        )
        visibility.hide()
        runCurrent()
        visibility.show()
        runCurrent()

        val disposal = visibility.detach { events += "destroy" }
        saved.complete(Unit)
        disposal?.join()

        assertThat(events).containsExactly("save:start", "resume", "destroy")
    }

    @Test
    fun `recreation stops pending pause and rejects onPause arriving during handoff`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val handoff = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val visibility = BrowserSessionVisibility(
            owner = this,
            pause = {
                events += "save:start"
                saved.await()
                events += "save:done"
            },
            resume = { events += "resume" },
        )
        visibility.hide()
        runCurrent()
        val prepare = launch {
            visibility.stopForRecreation()
            events += "handoff:start"
            handoff.await()
            events += "handoff:done"
        }
        runCurrent()

        visibility.hide()
        visibility.show()
        saved.complete(Unit)
        runCurrent()
        assertThat(events).containsExactly("save:start", "handoff:start")

        handoff.complete(Unit)
        prepare.join()
        visibility.detach { events += "destroy" }?.join()
        assertThat(events).containsExactly(
            "save:start", "handoff:start", "handoff:done", "destroy",
        )
    }

    @Test
    fun `cancelling the Presenter scope does not cancel its submitted finish save`() = runTest {
        val presenterJob = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        val presenterScope = CoroutineScope(coroutineContext + presenterJob)
        val saved = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val visibility = BrowserSessionVisibility(
            owner = this,
            pause = {
                events += "save:start"
                saved.await()
                events += "save:done"
            },
            resume = {},
        )
        presenterScope.launch { visibility.hide() }
        runCurrent()

        presenterScope.cancel()
        val disposal = visibility.detach { events += "destroy" }
        runCurrent()
        assertThat(events).containsExactly("save:start")

        saved.complete(Unit)
        disposal?.join()
        assertThat(events).containsExactly("save:start", "save:done", "destroy")
    }

    @Test
    fun `destroy while visible cancels pending resume without starting a save`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val visibility = BrowserSessionVisibility(
            owner = this,
            pause = { events += "save" },
            resume = {
                events += "resume:start"
                ready.await()
                events += "resume:done"
            },
        )
        visibility.show()
        runCurrent()

        visibility.detach { events += "destroy" }?.join()
        ready.complete(Unit)

        assertThat(events).containsExactly("resume:start", "destroy")
    }
}
