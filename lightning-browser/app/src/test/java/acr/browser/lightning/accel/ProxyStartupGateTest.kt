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

    @Test
    fun `failure releases all waiters without a successful readiness result`() = runTest {
        val gate = ProxyStartupGate()
        val token = gate.begin()
        val waiting = async { gate.await() }
        runCurrent()
        gate.fail(token)
        gate.markReady(token)
        assertFalse(waiting.await())
        assertFalse(gate.await())
    }

    @Test
    fun `restart invalidates old proxy callbacks and old navigation waiters`() = runTest {
        val gate = ProxyStartupGate()
        val old = gate.begin()
        val oldWaiting = async { gate.await() }
        runCurrent()
        val current = gate.begin()
        assertFalse(oldWaiting.await())
        val waiting = async { gate.await() }
        runCurrent()
        gate.markReady(old)
        gate.fail(old)
        assertFalse(waiting.isCompleted)
        gate.markReady(current)
        assertTrue(waiting.await())
    }

    @Test
    fun `exit after readiness removes the old successful result`() = runTest {
        val gate = ProxyStartupGate()
        val token = gate.begin()
        gate.markReady(token)
        assertTrue(gate.await())
        gate.fail(token)
        assertFalse(gate.await())
        val restarted = gate.begin()
        gate.markReady(restarted)
        assertTrue(gate.await())
    }
}
