package com.dsha.sidecar.net.proxy.native

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSocketCleanupTest {
    @Test fun collectionMayBecomeEmptyAfterItsSizeWasObserved() {
        val concurrentlyEmptied = object : AbstractSet<Socket>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<Socket> = emptyList<Socket>().iterator()
        }

        var released = 0
        assertThrows(NoSuchElementException::class.java) { concurrentlyEmptied.toList() }
        releaseConcurrentSockets(concurrentlyEmptied) { released++ }
        assertEquals(0, released)
    }

    @Test fun releaseCanRemoveSocketsWhileIterating() {
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val first = Socket()
        val second = Socket()
        sockets.add(first)
        sockets.add(second)

        releaseConcurrentSockets(sockets) {
            it.close()
            sockets.remove(it)
        }

        assertTrue(sockets.isEmpty())
        assertTrue(first.isClosed)
        assertTrue(second.isClosed)
    }
}
