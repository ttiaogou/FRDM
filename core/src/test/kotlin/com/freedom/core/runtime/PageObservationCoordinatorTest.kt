package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageObservationCoordinatorTest {
    @Test
    fun coalescesFullPageAndKnownRegionRequestsIndependently() {
        val scheduler = FakeScheduler()
        var now = 0L
        var fullPageSnapshots = 0
        var regionSnapshots = 0
        val coordinator = PageObservationCoordinator(
            scheduler = scheduler,
            fullPageDebounceMs = 250L,
            knownRegionDebounceMs = 450L,
            maxEventBurstWaitMs = 1_200L,
            runFullPageSnapshot = { fullPageSnapshots++ },
            runKnownRegionSnapshot = { regionSnapshots++ },
            nowMillis = { now }
        )

        coordinator.requestFullPageSnapshot()
        now = 50L
        coordinator.requestFullPageSnapshot()
        coordinator.requestKnownRegionSnapshot()

        assertTrue(coordinator.isFullPageSnapshotPending())
        assertTrue(coordinator.isKnownRegionSnapshotPending())
        assertEquals(2, scheduler.pendingCount())

        scheduler.runNextWithDelay(250L)
        assertEquals(1, fullPageSnapshots)
        assertEquals(0, regionSnapshots)
        assertFalse(coordinator.isFullPageSnapshotPending())
        assertTrue(coordinator.isKnownRegionSnapshotPending())

        scheduler.runNextWithDelay(450L)
        assertEquals(1, fullPageSnapshots)
        assertEquals(1, regionSnapshots)
        assertFalse(coordinator.isKnownRegionSnapshotPending())
    }

    @Test
    fun fullPageNavigationCanCancelRegionRefreshWithoutCancellingFullPageRequest() {
        val scheduler = FakeScheduler()
        var fullPageSnapshots = 0
        var regionSnapshots = 0
        val coordinator = PageObservationCoordinator(
            scheduler = scheduler,
            fullPageDebounceMs = 300L,
            knownRegionDebounceMs = 500L,
            maxEventBurstWaitMs = 1_200L,
            runFullPageSnapshot = { fullPageSnapshots++ },
            runKnownRegionSnapshot = { regionSnapshots++ }
        )

        coordinator.requestFullPageSnapshot()
        coordinator.requestKnownRegionSnapshot()
        coordinator.cancelKnownRegionSnapshot()

        assertTrue(coordinator.isFullPageSnapshotPending())
        assertFalse(coordinator.isKnownRegionSnapshotPending())
        scheduler.runNextWithDelay(300L)
        assertEquals(1, fullPageSnapshots)
        assertEquals(0, regionSnapshots)
    }

    @Test
    fun resetCancelsBothObservationChannels() {
        val scheduler = FakeScheduler()
        val coordinator = PageObservationCoordinator(
            scheduler = scheduler,
            fullPageDebounceMs = 250L,
            knownRegionDebounceMs = 450L,
            maxEventBurstWaitMs = 1_200L,
            runFullPageSnapshot = {},
            runKnownRegionSnapshot = {}
        )

        coordinator.requestFullPageSnapshot()
        coordinator.requestKnownRegionSnapshot()
        coordinator.reset()

        assertEquals(0, scheduler.pendingCount())
        assertFalse(coordinator.isFullPageSnapshotPending())
        assertFalse(coordinator.isKnownRegionSnapshotPending())
    }

    @Test
    fun sharedCoordinatorCoalescesBusinessChannelsIndependently() {
        val scheduler = FakeScheduler()
        val coordinator = PageObservationCoordinator(scheduler)
        var aiReplySnapshots = 0
        var accountBrowseSnapshots = 0
        val aiReply = coordinator.registerChannel(
            channelId = "AI_REPLY_FULL_INSPECTION",
            quietPeriodMs = 250L,
            maxWaitMs = 1_000L,
            onObservationRequested = { aiReplySnapshots++ }
        )
        val accountBrowse = coordinator.registerChannel(
            channelId = "ACCOUNT_BROWSE_PAGE_OBSERVATION",
            quietPeriodMs = 300L,
            maxWaitMs = 1_200L,
            onObservationRequested = { accountBrowseSnapshots++ }
        )

        aiReply.requestSnapshot()
        aiReply.requestSnapshot()
        accountBrowse.requestSnapshot()
        accountBrowse.requestSnapshot()

        assertEquals(
            setOf("AI_REPLY_FULL_INSPECTION", "ACCOUNT_BROWSE_PAGE_OBSERVATION"),
            coordinator.registeredChannelIds()
        )
        assertEquals(2, scheduler.pendingCount())

        aiReply.cancel()
        assertFalse(aiReply.isPending())
        assertTrue(accountBrowse.isPending())
        scheduler.runNextWithDelay(300L)
        assertEquals(0, aiReplySnapshots)
        assertEquals(1, accountBrowseSnapshots)
        assertFalse(accountBrowse.isPending())

        aiReply.close()
        accountBrowse.close()
        assertTrue(coordinator.registeredChannelIds().isEmpty())
    }

    @Test
    fun closingOneSharedChannelDoesNotCancelAnotherBusinessChannel() {
        val scheduler = FakeScheduler()
        val coordinator = PageObservationCoordinator(scheduler)
        var observations = 0
        val first = coordinator.registerChannel("first", 200L, 800L) { observations += 1 }
        val second = coordinator.registerChannel("second", 200L, 800L) { observations += 10 }

        first.requestSnapshot()
        second.requestSnapshot()
        first.close()

        assertEquals(setOf("second"), coordinator.registeredChannelIds())
        scheduler.runNextWithDelay(200L)
        assertEquals(10, observations)
    }

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

        fun runNextWithDelay(delayMillis: Long) {
            val entry = entries.firstOrNull { !it.cancelled && it.delay == delayMillis }
                ?: error("No pending task with delay=$delayMillis")
            entry.cancelled = true
            entry.task()
        }
    }
}
