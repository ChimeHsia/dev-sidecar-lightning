package acr.browser.lightning.accel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class ProxyStartupGate {
    private var generation = 0L
    private var ready = CompletableDeferred<Boolean>()

    @Synchronized
    fun begin(): Long {
        ready.complete(false)
        ready = CompletableDeferred()
        return ++generation
    }

    @Synchronized
    fun markReady(token: Long = generation) {
        if (token == generation) ready.complete(true)
    }

    @Synchronized
    fun fail(token: Long = generation) {
        if (token != generation) return
        ready.complete(false)
        ready = CompletableDeferred(false)
    }

    suspend fun await(timeoutMillis: Long = 15_000): Boolean {
        val attempt = synchronized(this) { ready }
        return withTimeoutOrNull(timeoutMillis) { attempt.await() } ?: false
    }
}
