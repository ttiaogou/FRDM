package com.freedom.application.runtime

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryPageObservationRetryTest {
    private class Scheduler : TaskScheduler {
        data class Entry(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }
    }

    @Test
    fun unavailableEntryKeepsOneTimerAndContinuesAtLowFrequencyWithoutEvents() {
        val scheduler = Scheduler()
        var reads = 0
        lateinit var retry: EntryPageObservationRetry
        retry = EntryPageObservationRetry(scheduler, { 7L }, { true }) {
            reads++
            retry.update(true)
        }
        retry.update(true)
        retry.update(true)
        assertEquals(1, scheduler.entries.size)
        val delays = listOf(500L, 1000L, 2000L, 5000L, 10000L, 30000L, 30000L)
        delays.forEachIndexed { index, delay ->
            assertEquals(delay, scheduler.entries[index].delay)
            scheduler.entries[index].callback()
        }
        assertEquals(delays.size, reads)
        retry.update(false)
        scheduler.entries.last().callback()
        assertEquals(delays.size, reads)
    }

    @Test
    fun stopAndGenerationChangeRejectAlreadyQueuedCallbacks() {
        val scheduler = Scheduler()
        var allowed = true
        var generation = 7L
        var reads = 0
        val retry = EntryPageObservationRetry(scheduler, { generation }, { allowed }) { reads++ }
        retry.update(true)
        generation++
        scheduler.entries[0].callback()
        assertEquals(0, reads)
        retry.update(true)
        assertEquals(500L, scheduler.entries[1].delay)
        allowed = false
        retry.cancel()
        scheduler.entries[1].callback()
        retry.update(true)
        assertEquals(0, reads)
        assertEquals(2, scheduler.entries.size)
    }
}
