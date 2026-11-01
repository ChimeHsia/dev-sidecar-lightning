package acr.browser.lightning.accel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class ProxyStartupGate {
    private val ready = CompletableDeferred<Unit>()

    fun markReady() {
        ready.complete(Unit)
    }

    suspend fun await(timeoutMillis: Long = 15_000): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            ready.await()
            true
        } ?: false
}
