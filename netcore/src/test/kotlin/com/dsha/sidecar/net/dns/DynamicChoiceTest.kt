package com.dsha.sidecar.net.dns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicChoiceTest {
    @Test
    fun `fresh instance has no selection or counters`() {
        val choice = DynamicChoice("example.test")
        assertEquals(DynamicChoice.Snapshot(null, emptyList(), emptyMap()), choice.snapshot())
        assertFalse(choice.resetChoice("example.test"))
        assertTrue(choice.snapshot().counts.isEmpty())
    }

    @Test
    fun `list seeds decreasing totals and counts only the selected candidate`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b", "example.test"))

        assertEquals("a", choice.value)
        assertEquals(listOf("b", "example.test"), choice.backupList)
        assertEquals(DynamicChoice.Count(4.0, 0.0, 0.0, 1.0), choice.count("a"))
        assertEquals(DynamicChoice.Count(2.0, 0.0, 0.0, 0.5), choice.count("b"))
        assertEquals(DynamicChoice.Count(1.0, 0.0, 0.0, 0.5), choice.count("example.test"))
    }

    @Test
    fun `first failure resets totals but preserves rate and does not count the successor`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b"))
        choice.doCount("a", true)

        assertEquals("b", choice.value)
        assertTrue(choice.backupList.isEmpty())
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 0.75), choice.count("a"))
        assertEquals(DynamicChoice.Count(1.0, 0.0, 0.0, 0.5), choice.count("b"))
    }

    @Test
    fun `late failure updates old candidate without switching the current selection`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b", "c"))
        choice.doCount("a", true)
        choice.doCount("a", true)
        choice.doCount("a", true)

        assertEquals("b", choice.value)
        assertEquals(listOf("c"), choice.backupList)
        assertEquals(DynamicChoice.Count(2.0, 2.0, 2.0, 0.0), choice.count("a"))
        choice.countStart("a")
        assertEquals(DynamicChoice.Count(3.0, 2.0, 0.0, 1.0 - 2.0 / 3.0), choice.count("a"))
        assertEquals("b", choice.value)
    }

    @Test
    fun `unlisted feedback is seeded at five and rank can select it`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a"))
        choice.doCount("late", true)
        assertEquals(DynamicChoice.Count(6.0, 1.0, 1.0, 1.0 - 1.0 / 6.0), choice.count("late"))
        choice.countStart("late")
        assertEquals(DynamicChoice.Count(7.0, 1.0, 0.0, 1.0 - 1.0 / 7.0), choice.count("late"))

        choice.doCount("a", true)
        choice.doRank()
        assertEquals("late", choice.value)
        assertEquals(listOf("a"), choice.backupList)
        assertEquals(DynamicChoice.Count(8.0, 1.0, 0.0, 0.875), choice.count("late"))
    }

    @Test
    fun `replacing list keeps history and decrements seed only for newly seen strings`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b"))
        choice.doCount("b", true)
        choice.setBackupList(listOf("b", "c", "c", "d"))

        assertEquals("b", choice.value)
        assertEquals(listOf("c", "c", "d"), choice.backupList)
        assertEquals(DynamicChoice.Count(3.0, 1.0, 0.0, 1.0 - 1.0 / 3.0), choice.count("b"))
        assertEquals(DynamicChoice.Count(4.0, 0.0, 0.0, 0.5), choice.count("c"))
        assertEquals(DynamicChoice.Count(3.0, 0.0, 0.0, 0.5), choice.count("d"))
        assertEquals(listOf("a", "b", "c", "d"), choice.snapshot().counts.keys.toList())
    }

    @Test
    fun `duplicate backup can select the same candidate again`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "a", "b"))
        choice.doCount("a", true)
        assertEquals("a", choice.value)
        assertEquals(listOf("b"), choice.backupList)
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 0.8), choice.count("a"))

        choice.doCount("a", true)
        assertEquals("b", choice.value)
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 0.0), choice.count("a"))
    }

    @Test
    fun `exhaustion leaves history and late successes do not revive selection`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b"))
        choice.doCount("a", true)
        choice.doCount("b", true)
        assertNull(choice.value)
        assertTrue(choice.backupList.isEmpty())
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 0.5), choice.count("b"))

        choice.countStart("a")
        assertNull(choice.value)
        assertEquals(DynamicChoice.Count(1.0, 0.0, 0.0, 1.0), choice.count("a"))
        choice.doRank()
        assertEquals("a", choice.value)
        assertEquals(listOf("b"), choice.backupList)
    }

    @Test
    fun `rank uses stored failure rate instead of recomputing from reset totals`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b", "c"))
        choice.doCount("a", true)
        choice.doRank()

        assertEquals("a", choice.value)
        assertEquals(listOf("b", "c"), choice.backupList)
        assertEquals(DynamicChoice.Count(1.0, 0.0, 0.0, 1.0), choice.count("a"))
    }

    @Test
    fun `rank ties use original insertion order rather than current backup order`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b", "c"))
        choice.countStart("b")
        choice.countStart("c")
        choice.setBackupList(listOf("c", "b", "a"))
        choice.doRank()

        assertEquals("a", choice.value)
        assertEquals(listOf("b", "c"), choice.backupList)
    }

    @Test
    fun `numeric keys follow JS enumeration for snapshots ranking and reset`() {
        val choice = DynamicChoice()
        val insertion = listOf("10", "2", "01", "4294967295", "4294967294", "0", "-0")
        val enumeration = listOf("0", "2", "10", "4294967294", "01", "4294967295", "-0")
        choice.setBackupList(insertion)
        insertion.forEach(choice::countStart)
        assertEquals(enumeration, choice.snapshot().counts.keys.toList())

        choice.doRank()
        assertEquals("0", choice.value)
        assertEquals(enumeration.drop(1), choice.backupList)
        assertTrue(choice.resetChoice("2"))
        assertEquals("0", choice.value)
        assertEquals(enumeration.filter { it != "2" }.drop(1), choice.backupList)
    }

    @Test
    fun `reset uses all history and excludes only the exact hostname`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "example.test"))
        choice.doCount("a", true)
        choice.doCount("late", true)
        choice.setBackupList(listOf("example.test"))

        assertTrue(choice.resetChoice("example.test"))
        assertEquals("a", choice.value)
        assertEquals(listOf("late"), choice.backupList)
        assertEquals(DynamicChoice.Count(1.0, 0.0, 0.0, 1.0), choice.count("a"))
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 1.0), choice.count("example.test"))
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 1.0), choice.count("late"))
        assertTrue(choice.resetChoice("EXAMPLE.TEST"))
        assertEquals(listOf("example.test", "late"), choice.backupList)
    }

    @Test
    fun `unsuccessful reset still clears all counters and preserves current queue`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("example.test", "example.test"))
        choice.countStart("example.test")

        assertFalse(choice.resetChoice("example.test"))
        assertEquals("example.test", choice.value)
        assertEquals(listOf("example.test"), choice.backupList)
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 1.0), choice.count("example.test"))
        choice.doCount("example.test", true)
        assertEquals("example.test", choice.value)
        assertFalse(choice.resetChoice("example.test"))
        assertTrue(choice.backupList.isEmpty())
    }

    @Test
    fun `empty list clears selection while empty rank never creates undefined counter`() {
        val fresh = DynamicChoice()
        fresh.doRank()
        fresh.setBackupList(emptyList())
        assertEquals(DynamicChoice.Snapshot(null, emptyList(), emptyMap()), fresh.snapshot())

        val choice = DynamicChoice()
        choice.setBackupList(listOf("a"))
        val counts = choice.snapshot().counts
        choice.setBackupList(emptyList())
        assertNull(choice.value)
        assertEquals(counts, choice.snapshot().counts)
        choice.doRank()
        assertEquals("a", choice.value)
        assertEquals(listOf("a"), choice.snapshot().counts.keys.toList())
    }

    @Test
    fun `literal undefined is a real candidate and never a synthetic entry`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("undefined", "b"))
        choice.doCount("undefined", true)
        assertEquals("b", choice.value)
        assertEquals(listOf("undefined", "b"), choice.snapshot().counts.keys.toList())
        assertEquals(DynamicChoice.Count(0.0, 0.0, 0.0, 0.75), choice.count("undefined"))
    }

    @Test
    fun `input lists getters and snapshots cannot mutate internal state`() {
        val candidates = mutableListOf("a", "b", "c")
        val choice = DynamicChoice()
        choice.setBackupList(candidates)
        val snapshot = choice.snapshot()
        candidates.clear()
        (choice.backupList as MutableList<String>).clear()
        (snapshot.backupList as MutableList<String>).add("outside")
        (snapshot.counts as MutableMap<String, DynamicChoice.Count>).clear()
        assertEquals("a", choice.value)
        assertEquals(listOf("b", "c"), choice.backupList)
        assertEquals(listOf("a", "b", "c"), choice.snapshot().counts.keys.toList())

        val beforeFailure = choice.snapshot()
        choice.doCount("a", true)
        assertEquals("a", beforeFailure.value)
        assertEquals(DynamicChoice.Count(4.0, 0.0, 0.0, 1.0), beforeFailure.counts["a"])
        assertEquals("b", choice.value)
    }

    @Test
    fun `invalid candidates are rejected before mutating state`() {
        val choice = DynamicChoice()
        choice.setBackupList(listOf("a", "b"))
        val before = choice.snapshot()
        val reserved = listOf(
            "", "__proto__", "constructor", "__defineGetter__", "__defineSetter__",
            "hasOwnProperty", "__lookupGetter__", "__lookupSetter__", "isPrototypeOf",
            "propertyIsEnumerable", "toString", "valueOf", "toLocaleString",
        )
        for (candidate in reserved) {
            assertFailsWith<IllegalArgumentException> {
                choice.setBackupList(listOf("new", candidate))
            }
            assertFailsWith<IllegalArgumentException> { choice.countStart(candidate) }
            assertFailsWith<IllegalArgumentException> { choice.doCount(candidate, true) }
            assertFailsWith<IllegalArgumentException> { choice.resetChoice(candidate) }
            assertEquals(before, choice.snapshot())
        }
    }

    private fun DynamicChoice.count(candidate: String): DynamicChoice.Count =
        snapshot().counts.getValue(candidate)
}
