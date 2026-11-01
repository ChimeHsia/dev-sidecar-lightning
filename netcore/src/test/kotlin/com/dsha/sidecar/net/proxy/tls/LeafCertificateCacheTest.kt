package com.dsha.sidecar.net.proxy.tls

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LeafCertificateCacheTest {
    private val clock = MutableCertificateClock(EphemeralTestCa.referenceTime)
    private val authority by lazy { fixture.authority(clock, leafLifetime = Duration.ofMinutes(10)) }

    @Test fun `max size is enforced using access order LRU and contexts reused`() {
        val cache = LeafCertificateCache(authority, maxSize = 2)
        val a = cache.get("a.test")
        val b = cache.get("b.test")
        assertSame(a, cache.get("A.TEST."))
        assertSame(a.sslContext, cache.get("a.test").sslContext())
        cache.get("c.test")
        assertEquals(2, cache.size)
        assertSame(a, cache.get("a.test"))
        assertNotSame(b, cache.get("b.test"))
        assertEquals(2, cache.size)
    }

    @Test fun `concurrent same host requests generate exactly one real leaf`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(8)
        val calls = AtomicInteger()
        val cache = LeafCertificateCache(authority, 2, Duration.ZERO) {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            authority.issue(it)
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                pool.submit(Callable {
                    ready.countDown()
                    cache.get(if (it % 2 == 0) "LOCALHOST." else "localhost")
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            release.countDown()
            val leaves = results.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(leaves.all { it === leaves.first() })
            assertEquals(1, calls.get())
            assertEquals(1, cache.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `different hosts generate outside cache monitor`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val cache = LeafCertificateCache(authority, 2, Duration.ZERO) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            authority.issue(it)
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { cache.get("a.test") })
            val b = pool.submit(Callable { cache.get("b.test") })
            assertTrue("Both generations must run without holding the cache lock", entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("a.test", a.get(10, TimeUnit.SECONDS).host)
            assertEquals("b.test", b.get(10, TimeUnit.SECONDS).host)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `in flight host limit fails fast and permits later retry`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = LeafCertificateCache(authority, 1, Duration.ZERO) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            authority.issue(it)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit(Callable { cache.get("a.test") })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { cache.get("b.test") }
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            assertEquals("b.test", cache.get("b.test").host)
            assertEquals(1, cache.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `failed generation is evicted and original cause preserved`() {
        val calls = AtomicInteger()
        val failure = IllegalStateException("Synthetic generation failure")
        val cache = LeafCertificateCache(authority, 1, Duration.ZERO) {
            if (calls.incrementAndGet() == 1) throw failure
            authority.issue(it)
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { cache.get("localhost") })
        assertEquals(0, cache.size)
        val leaf = cache.get("localhost")
        assertSame(leaf, cache.get("localhost"))
        assertEquals(2, calls.get())
    }

    @Test fun `interrupting one waiter preserves interrupt without cancelling shared generation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val outcome = AtomicReference<Boolean>()
        val cache = LeafCertificateCache(authority, 1, Duration.ZERO) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            authority.issue(it)
        }
        val pool = Executors.newSingleThreadExecutor()
        val waiter = Thread {
            waiting.countDown()
            try {
                cache.get("localhost")
                outcome.set(false)
            } catch (expected: InterruptedException) {
                outcome.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            val owner = pool.submit(Callable { cache.get("localhost") })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waiter.start()
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(5000)
            assertFalse(waiter.isAlive)
            assertEquals(true, outcome.get())
            release.countDown()
            assertSame(owner.get(10, TimeUnit.SECONDS), cache.get("localhost"))
        } finally {
            release.countDown()
            waiter.interrupt()
            waiter.join(5000)
            pool.shutdownNow()
        }
    }

    @Test fun `cached validity and renewal use injected authority clock`() {
        val cache = LeafCertificateCache(authority, 2, Duration.ofMinutes(1))
        val first = cache.get("localhost")
        clock.current = first.certificate.notAfter.toInstant().minusSeconds(61)
        assertSame(first, cache.get("localhost"))
        clock.current = first.certificate.notAfter.toInstant().minusSeconds(60)
        val renewed = cache.get("localhost")
        assertNotSame(first, renewed)
        assertTrue(renewed.certificate.notAfter.after(first.certificate.notAfter))
        clock.current = renewed.certificate.notAfter.toInstant()
        assertNotSame(renewed, cache.get("localhost"))
    }

    @Test fun `clock moving before leaf validity regenerates without returning future leaf`() {
        val cache = LeafCertificateCache(authority, 1, Duration.ZERO)
        val first = cache.get("localhost")
        clock.current = first.certificate.notBefore.toInstant().minusSeconds(1)
        val second = cache.get("localhost")
        assertNotSame(first, second)
        assertTrue(second.isUsableAt(clock.instant()))
    }

    @Test fun `issuer expiry or not yet valid state prevents cached return`() {
        val cache = LeafCertificateCache(authority, 1)
        cache.get("localhost")
        clock.current = authority.certificate.notAfter.toInstant()
        assertThrows(Exception::class.java) { cache.get("localhost") }
        clock.current = authority.certificate.notBefore.toInstant().minusSeconds(1)
        assertThrows(Exception::class.java) { cache.get("localhost") }
    }

    @Test fun `short remaining issuer validity can be served but is not cached in renewal window`() {
        val shortAuthority = EphemeralTestCa.create(
            sharedKeys, notAfter = clock.instant().plusSeconds(30),
        ).authority(clock)
        val cache = LeafCertificateCache(shortAuthority, 1)
        assertTrue(cache.get("localhost").isUsableAt(clock.instant()))
        assertEquals(0, cache.size)
    }

    @Test fun `cache identity includes certificate even when CA keys are reused`() {
        val secondAuthority = EphemeralTestCa.create(sharedKeys).authority(clock)
        assertArrayEquals(authority.certificate.publicKey.encoded, secondAuthority.certificate.publicKey.encoded)
        assertNotEquals(authority.identity, secondAuthority.identity)
        val first = LeafCertificateCache(authority).get("localhost")
        val second = LeafCertificateCache(secondAuthority).get("localhost")
        assertNotSame(first, second)
        assertNotEquals(first.authorityIdentity, second.authorityIdentity)
        second.certificate.verify(secondAuthority.certificate.publicKey)
    }

    @Test fun `internal generator cannot insert wrong authority wrong host or expired leaf`() {
        val other = EphemeralTestCa.create(sharedKeys).authority(clock)
        val foreign = other.issue("localhost")
        val wrongHost = authority.issue("other.test")
        val expired = authority.issue("localhost")
        for (invalid in listOf(foreign, wrongHost)) {
            val cache = LeafCertificateCache(authority, 1, Duration.ZERO) { invalid }
            assertThrows(IllegalStateException::class.java) { cache.get("localhost") }
            assertEquals(0, cache.size)
        }
        clock.current = expired.certificate.notAfter.toInstant()
        val cache = LeafCertificateCache(authority, 1, Duration.ZERO) { expired }
        assertThrows(IllegalStateException::class.java) { cache.get("localhost") }
        assertEquals(0, cache.size)
    }

    @Test fun `clear drops cached entries and prevents pending generation from repopulating LRU`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = LeafCertificateCache(authority, 1, Duration.ZERO) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            authority.issue(it)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { cache.get("localhost") })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cache.clear()
            release.countDown()
            val old = pending.get(10, TimeUnit.SECONDS)
            assertEquals(0, cache.size)
            val current = cache.get("localhost")
            assertNotSame(old, current)
            assertEquals(1, cache.size)
            cache.clear()
            assertEquals(0, cache.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `invalid size renewal and host rejected`() {
        for (size in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { LeafCertificateCache(authority, size) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeafCertificateCache(authority, 1, Duration.ofSeconds(-1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeafCertificateCache(authority, 1, Duration.ofDays(8))
        }
        val cache = LeafCertificateCache(authority)
        assertThrows(IllegalArgumentException::class.java) { cache.get("https://example.test") }
        assertEquals(0, cache.size)
    }

    companion object {
        private val sharedKeys by lazy { EphemeralTestCa.keyPair() }
        private val fixture by lazy { EphemeralTestCa.create(sharedKeys) }
    }
}
