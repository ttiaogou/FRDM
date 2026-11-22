package com.freedom.features.aireply

import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoPageObservationCoordinatorTest {
    @Test
    fun coalescesFullAndRegionSnapshotsThroughOneCoordinator() {
        val scheduler = FakeScheduler()
        var fullInspections = 0
        var regionRefreshes = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(listOf(ReceivedGreetingsPageArrivalStatus.NOT_TARGET)),
            runFullInspection = { fullInspections++ },
            runRegionRefresh = { regionRefreshes++ }
        )

        coordinator.signalFullInspection()
        coordinator.signalFullInspection()
        coordinator.signalRegionRefresh()

        assertEquals(2, scheduler.pendingCount())
        assertTrue(coordinator.isFullInspectionPending())
        scheduler.runNextWithDelay(250L)
        assertEquals(1, fullInspections)
        assertEquals(0, regionRefreshes)
        scheduler.runNextWithDelay(450L)
        assertEquals(1, fullInspections)
        assertEquals(1, regionRefreshes)
        assertFalse(coordinator.isFullInspectionPending())
    }

    @Test
    fun centralCoordinatorPreservesBoundedGreetingStabilityRetriesAndConfirmation() {
        val scheduler = FakeScheduler()
        val statuses = ArrayDeque(
            listOf(
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
        )
        var snapshotRequests = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = statuses,

            onFallback = {},
            onSnapshotRequested = { snapshotRequests++ }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(350L, scheduler.nextDelay())

        coordinator.onPageChangeEvent()
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        )
        assertEquals(1, scheduler.pendingCount())

        scheduler.runNext()
        assertEquals(1, snapshotRequests)
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED,
            coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED)
        )
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun unknownPagesUseRecurringLowFrequencyFallbackAndEventsCancelIt() {
        val scheduler = FakeScheduler()
        var fallbackRequests = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(listOf(ReceivedGreetingsPageArrivalStatus.UNKNOWN)),
            onFallback = { fallbackRequests++ }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.UNKNOWN,
            coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.UNKNOWN)
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(1, fallbackRequests)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        coordinator.onPageChangeEvent()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(1, fallbackRequests)
    }

    @Test
    fun quickRetryBudgetIsBoundedAndFallbackContinuesAfterExhaustion() {
        val scheduler = FakeScheduler()
        var fallbackRequests = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(),
            onFallback = { fallbackRequests++ },
            inspect = null
        )

        coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        repeat(8) {
            assertEquals(350L, scheduler.nextDelay())
            scheduler.runNext()
            coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        }

        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())
        scheduler.runNext()
        assertEquals(1, fallbackRequests)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())
    }

    @Test
    fun resetCancelsSnapshotDebouncersAndArrivalWatchdog() {
        val scheduler = FakeScheduler()
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(listOf(ReceivedGreetingsPageArrivalStatus.NOT_TARGET))
        )
        coordinator.signalFullInspection()
        coordinator.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.NOT_TARGET)
        assertEquals(2, scheduler.pendingCount())

        coordinator.reset()

        assertEquals(0, scheduler.pendingCount())
        assertFalse(coordinator.isFullInspectionPending())
    }

    private fun createCoordinator(
        scheduler: FakeScheduler,
        statuses: ArrayDeque<ReceivedGreetingsPageArrivalStatus>,
        runFullInspection: () -> Unit = {},
        runRegionRefresh: () -> Unit = {},

        onFallback: () -> Unit = {},
        onSnapshotRequested: () -> Unit = {},
        inspect: (() -> ReceivedGreetingsPageArrivalStatus)? = null
    ): MomoPageObservationCoordinator = MomoPageObservationCoordinator(
        sharedCoordinator = PageObservationCoordinator(scheduler),
        fullInspectionDebounceMs = 250L,
        regionRefreshDebounceMs = 450L,
        maxEventBurstInspectionWaitMs = 1_200L,
        runFullInspection = runFullInspection,
        runRegionRefresh = runRegionRefresh,
        onFallbackCheckRequested = onFallback,
        onSnapshotRequested = onSnapshotRequested,
        fallbackDelayMs = { 60_000L }
    )

    private class FakeScheduler : TaskScheduler {
        private data class Entry(
            val delay: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false
        )

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingCount(): Int = entries.count { !it.cancelled }
        fun nextDelay(): Long = entries.firstOrNull { !it.cancelled }?.delay ?: -1L

        fun runNext() {
            val entry = entries.firstOrNull { !it.cancelled } ?: error("No pending task")
            entry.cancelled = true
            entry.task()
        }

        fun runNextWithDelay(delayMillis: Long) {
            val entry = entries.firstOrNull { !it.cancelled && it.delay == delayMillis }
                ?: error("No pending task with delay=$delayMillis")
            entry.cancelled = true
            entry.task()
        }
    }
}
