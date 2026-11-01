package acr.browser.lightning.accel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyStartupGateTest {

    @Test
    fun `network navigation waits until proxy is applied`() = runTest {
        val gate = ProxyStartupGate()
        val first = async { gate.await() }
        val second = async { gate.await() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        gate.markReady()
        advanceUntilIdle()

        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(gate.await())
    }

    @Test
    fun `startup failure has a bounded wait`() = runTest {
        val gate = ProxyStartupGate()
        val waiting = async { gate.await(timeoutMillis = 1_000) }
        advanceUntilIdle()
        assertFalse(waiting.await())
    }

    @Test
    fun `cancelling one navigation does not cancel proxy readiness for others`() = runTest {
        val gate = ProxyStartupGate()
        val cancelled = async { gate.await() }
        val waiting = async { gate.await() }
        runCurrent()
        cancelled.cancel()
        gate.markReady()
        gate.markReady()
        advanceUntilIdle()

        assertTrue(cancelled.isCancelled)
        assertTrue(waiting.await())
    }
}
