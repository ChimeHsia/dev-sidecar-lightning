package acr.browser.lightning.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserVisibilityControllerTest {

    @Test
    fun `returning to foreground during tab save prevents a stale pause`() = runTest {
        val saved = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = BrowserVisibilityController(
            scope = this,
            pause = {
                events += "save"
                saved.await()
                events += "pause"
            },
            resume = { events += "resume" },
        )
        controller.hide()
        runCurrent()
        controller.show()
        runCurrent()
        saved.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("save", "resume"), events)
    }

    @Test
    fun `leaving foreground cancels a pending resume`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = BrowserVisibilityController(
            scope = this,
            pause = { events += "pause" },
            resume = {
                ready.await()
                events += "resume"
            },
        )
        controller.show()
        runCurrent()
        controller.hide()
        runCurrent()
        ready.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("pause"), events)
    }

    @Test
    fun `normal background save and subsequent resume both finish`() = runTest {
        val events = mutableListOf<String>()
        val controller = BrowserVisibilityController(
            scope = this,
            pause = {
                events += "save"
                events += "pause"
            },
            resume = { events += "resume" },
        )
        controller.hide()
        advanceUntilIdle()
        controller.show()
        advanceUntilIdle()

        assertEquals(listOf("save", "pause", "resume"), events)
    }
}
