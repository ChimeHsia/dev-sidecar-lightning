package com.dsha.sidecar.net.dns

import org.junit.Assert.*
import org.junit.Test

class Ipv6AvailabilityTest {
    @Test
    fun `three cumulative reports disable IPv6 and count saturates`() {
        val initial = Ipv6Availability.fromInterfaceAvailability(true)
        val once = initial.reportUnreachable("::1")
        val twice = once.reportUnreachable("2001:db8::1")
        var disabled = twice.reportUnreachable("[broken")
        assertTrue(initial.available)
        assertEquals(0, initial.errorCount)
        assertTrue(once.available)
        assertEquals(1, once.errorCount)
        assertTrue(twice.available)
        assertEquals(2, twice.errorCount)
        assertFalse(disabled.available)
        assertEquals(3, disabled.errorCount)
        repeat(10_000) {
            val next = disabled.reportUnreachable("::1")
            assertSame(disabled, next)
            disabled = next
        }
        assertEquals(3, disabled.errorCount)
    }

    @Test
    fun `disabled epochs and non IPv6 reports do not count`() {
        val disabled = Ipv6Availability.fromInterfaceAvailability(false)
        assertSame(disabled, disabled.reportUnreachable("::1"))
        assertEquals(0, disabled.errorCount)
        val available = Ipv6Availability.fromInterfaceAvailability(true)
        for (address in listOf("192.0.2.1", "bad", "", "::1]", "2001:db8::1%eth0")) {
            assertSame(available, available.reportUnreachable(address))
        }
        val once = available.reportUnreachable("::1")
        assertSame(once, once.reportUnreachable("192.0.2.1"))
        assertEquals(1, once.errorCount)
    }
}
