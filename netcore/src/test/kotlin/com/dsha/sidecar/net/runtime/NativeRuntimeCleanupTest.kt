package com.dsha.sidecar.net.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeCleanupTest {
    @Test fun completedQueryCanDisappearAfterSizeWasObserved() {
        val concurrentlyEmptied = object : AbstractSet<CompletableFuture<*>>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<CompletableFuture<*>> = emptyList<CompletableFuture<*>>().iterator()
        }
        assertThrows(NoSuchElementException::class.java) { concurrentlyEmptied.toList() }
        cancelPendingQueries(concurrentlyEmptied)
    }

    @Test fun cancellationCanRemoveQueriesDuringIteration() {
        val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
        val first = CompletableFuture<Unit>()
        val second = CompletableFuture<Unit>()
        pending.add(first)
        pending.add(second)
        first.whenComplete { _, _ -> pending.remove(first) }
        second.whenComplete { _, _ -> pending.remove(second) }
        cancelPendingQueries(pending)
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(pending.isEmpty())
    }
}
