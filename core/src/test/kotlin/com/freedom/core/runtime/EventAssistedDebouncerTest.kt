package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAssistedDebouncerTest {
    @Test
    fun quietPeriodCoalescesEvents() {
        val scheduler = FakeScheduler()
        var now = 1_000L
        var runs = 0
        val debouncer = EventAssistedDebouncer(scheduler, 300L, 1_200L, { now }) { runs++ }

        debouncer.signal()
        assertEquals(300L, scheduler.nextDelay())
        now += 100L
        debouncer.signal()
        assertEquals(1, scheduler.pendingCount())
        assertEquals(300L, scheduler.nextDelay())
        scheduler.runNext()

        assertEquals(1, runs)
        assertFalse(debouncer.isPending())
    }

    @Test
    fun continuousEventsCannotPushInspectionPastMaximumWait() {
        val scheduler = FakeScheduler()
        var now = 5_000L
        var runs = 0
        val debouncer = EventAssistedDebouncer(scheduler, 300L, 1_200L, { now }) { runs++ }

        debouncer.signal()
        now += 300L
        debouncer.signal()
        assertEquals(300L, scheduler.nextDelay())
        now += 300L
        debouncer.signal()
        now += 300L
        debouncer.signal()
        assertEquals(300L, scheduler.nextDelay())
        now += 300L
        debouncer.signal()
        assertEquals(0L, scheduler.nextDelay())
        scheduler.runNext()

        assertEquals(1, runs)
        assertFalse(debouncer.isPending())
    }

    @Test
    fun staleCancelledCallbackCannotFireAfterReschedule() {
        val scheduler = FakeScheduler()
        var now = 0L
        var runs = 0
        val debouncer = EventAssistedDebouncer(scheduler, 300L, 1_200L, { now }) { runs++ }

        debouncer.signal()
        now += 100L
        debouncer.signal()
        scheduler.runEntryEvenIfCancelled(0)

        assertEquals(0, runs)
        assertTrue(debouncer.isPending())
        scheduler.runNext()
        assertEquals(1, runs)
    }

    @Test
    fun cancelClearsPendingTimerAndBurstWindow() {
        val scheduler = FakeScheduler()
        var now = 0L
        val debouncer = EventAssistedDebouncer(scheduler, 300L, 1_200L, { now }) { error("cancelled task ran") }
        debouncer.signal()
        debouncer.cancel()
        assertFalse(debouncer.isPending())
        assertEquals(0, scheduler.pendingCount())
        now = 10_000L
        debouncer.signal()
        assertEquals(300L, scheduler.nextDelay())
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val block: () -> Unit, var cancelled: Boolean = false)
        private val entries = ArrayList<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }
        fun nextDelay(): Long = entries.firstOrNull { !it.cancelled }?.delay ?: -1L
        fun pendingCount(): Int = entries.count { !it.cancelled }
        fun runNext() {
            val index = entries.indexOfFirst { !it.cancelled }
            if (index < 0) error("No pending task")
            runEntryEvenIfCancelled(index)
        }
        fun runEntryEvenIfCancelled(index: Int) {
            val entry = entries[index]
            entry.cancelled = true
            entry.block()
        }
    }
}
