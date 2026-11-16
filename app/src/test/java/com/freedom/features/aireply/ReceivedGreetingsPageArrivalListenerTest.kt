package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsPageArrivalListenerTest {
    @Test
    fun confirmsOnlyAfterAdapterReportsStableListAndDoesNotNavigate() {
        val scheduler = FakeScheduler()
        val statuses = ArrayDeque(
            listOf(
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
        )
        var observations = 0
        var starts = 0
        val listener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = { observations++; statuses.removeFirst() },
            onConfirmed = { starts++ }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            listener.onPageSnapshotAvailable()
        )
        assertEquals(1, observations)
        assertEquals(0, starts)
        assertEquals(1, scheduler.pendingCount())

        scheduler.runNext()

        assertEquals(2, observations)
        assertEquals(1, starts)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun unrelatedAndIntermediatePagesScheduleOnlyOneLowFrequencyFallback() {
        val scheduler = FakeScheduler()
        var starts = 0
        var fallbackRequests = 0
        val listener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = { ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION },
            onConfirmed = { starts++ },
            onFallbackCheckRequested = { fallbackRequests++ },
            fallbackDelayMs = { 60_000L }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION,
            listener.onPageSnapshotAvailable()
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())
        assertEquals(0, starts)

        scheduler.runNext()
        assertEquals(1, fallbackRequests)
        assertEquals(1, scheduler.pendingCount()) // The watchdog rearms until a new event or confirmation.
        assertEquals(60_000L, scheduler.nextDelay())

        listener.onPageChangeEvent()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(0, starts)

        val transitionListener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = { ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE },
            onConfirmed = { starts++ },
            onFallbackCheckRequested = { fallbackRequests++ },
            fallbackDelayMs = { 60_000L }
        )
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE,
            transitionListener.onPageSnapshotAvailable()
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(0, starts)
    }

    @Test
    fun stabilityRetriesAreBoundedThenLowFrequencyFallbackContinuesIndefinitely() {
        val scheduler = FakeScheduler()
        var observations = 0
        var fallbacks = 0
        var starts = 0
        val listener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = {
                observations++
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING
            },
            onConfirmed = { starts++ },
            onFallbackCheckRequested = { fallbacks++ },
            retryDelayMs = 350L,
            maxStabilityRetries = 8,
            fallbackDelayMs = { 60_000L }
        )

        listener.onPageSnapshotAvailable()
        repeat(8) {
            assertEquals(350L, scheduler.nextDelay())
            scheduler.runNext()
        }

        assertEquals(9, observations) // One initial sample plus eight short retries.
        assertEquals(0, starts)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(1, fallbacks)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(2, fallbacks)
        assertEquals(1, scheduler.pendingCount())
    }

    @Test
    fun pageChangeCancelsFallbackAndRepeatedEventsDoNotCreateConcurrentRetries() {
        val scheduler = FakeScheduler()
        val statuses = ArrayDeque(
            listOf(
                ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
        )
        var observations = 0
        var starts = 0
        val listener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = { observations++; statuses.removeFirst() },
            onConfirmed = { starts++ },
            fallbackDelayMs = { 60_000L }
        )

        assertEquals(ReceivedGreetingsPageArrivalStatus.NOT_TARGET, listener.onPageSnapshotAvailable())
        assertEquals(60_000L, scheduler.nextDelay())

        listener.onPageChangeEvent()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING, listener.onPageSnapshotAvailable())
        assertEquals(1, scheduler.pendingCount())

        // A repeated event does not start a competing observation while the short retry is in flight.
        listener.onPageChangeEvent()
        assertEquals(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING, listener.onPageSnapshotAvailable())
        assertEquals(2, observations)
        assertEquals(1, scheduler.pendingCount())

        scheduler.runNext()
        assertEquals(3, observations)
        assertEquals(1, starts)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun resetCancelsBothPendingTimersAndInvalidatesTheirCallbacks() {
        val scheduler = FakeScheduler()
        var starts = 0
        var fallbackRequests = 0
        val listener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = { ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING },
            onConfirmed = { starts++ },
            onFallbackCheckRequested = { fallbackRequests++ }
        )

        listener.onPageSnapshotAvailable()
        assertTrue(scheduler.pendingCount() > 0)
        listener.reset()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(0, starts)
        assertEquals(0, fallbackRequests)
        assertFalse(scheduler.hasPendingDelay(350L))

        val fallbackListener = ReceivedGreetingsPageArrivalListener(
            scheduler = scheduler,
            observePage = { ReceivedGreetingsPageArrivalStatus.NOT_TARGET },
            onConfirmed = { starts++ },
            onFallbackCheckRequested = { fallbackRequests++ },
            fallbackDelayMs = { 60_000L }
        )
        fallbackListener.onPageSnapshotAvailable()
        assertTrue(scheduler.hasPendingDelay(60_000L))
        fallbackListener.reset()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(0, fallbackRequests)
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingCount(): Int = entries.count { !it.cancelled }
        fun nextDelay(): Long = entries.firstOrNull { !it.cancelled }?.delay ?: -1L
        fun hasPendingDelay(delayMillis: Long): Boolean = entries.any { !it.cancelled && it.delay == delayMillis }

        fun runNext() {
            val entry = entries.firstOrNull { !it.cancelled } ?: error("No pending task")
            entry.cancelled = true
            entry.task()
        }
    }
}
