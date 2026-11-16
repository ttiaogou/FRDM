package com.freedom.adapters.social.momo

import org.junit.Assert.*
import org.junit.Test

class MomoSayHiAutonomousBrowseControllerTest {
    @Test fun waitsForFirstSnapshotAndRateLimitsScrolling() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 1_200L)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        assertFalse(controller.shouldScroll("page-a", 2_000L, true))
        assertTrue(controller.shouldScroll("page-a", 2_201L, true))
    }

    @Test fun waitsForPostGestureSnapshotBeforeCountingUnchangedPages() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 10L, maximumUnchangedPages = 2)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        assertTrue(controller.shouldScroll("page-a", 1_011L, true))
        controller.onGestureCompleted(true)
        assertTrue(controller.shouldScroll("page-a", 1_022L, true))
        controller.onGestureCompleted(true)
        assertFalse(controller.shouldScroll("page-a", 1_033L, true))
        assertTrue(controller.isFinished())
    }

    @Test fun changedRowsResetEndDetection() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 10L, maximumUnchangedPages = 2)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        assertTrue(controller.shouldScroll("page-a", 1_011L, true))
        controller.onGestureCompleted(true)
        assertTrue(controller.shouldScroll("page-b", 1_022L, true))
        controller.onGestureCompleted(true)
        assertTrue(controller.shouldScroll("page-b", 1_033L, true))
        assertFalse(controller.isFinished())
    }

    @Test fun explicitlyMarkedFinishedCannotScheduleAnotherScroll() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 1L)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        controller.markFinished()
        assertTrue(controller.isFinished())
        assertFalse(controller.shouldScroll("page-b", 2_000L, true))
    }

    @Test fun neverScrollsWhenListIsNotScrollableOrSignatureMissing() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 1L)
        assertFalse(controller.shouldScroll("page-a", 1_000L, false))
        assertFalse(controller.shouldScroll("", 1_000L, true))
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
    }

    @Test fun repeatedGestureCancellationNeverProvesEndOfList() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 1L)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        assertTrue(controller.shouldScroll("page-a", 1_002L, true))
        controller.onGestureCompleted(false)
        assertFalse(controller.shouldScroll("page-a", 1_003L, true))
        assertTrue(controller.shouldScroll("page-a", System.currentTimeMillis() + 2L, true))
        controller.onGestureCompleted(false)
        assertFalse(controller.isFinished())
    }

    @Test fun nonScrollableBoundaryRequiresTwoSpacedIdenticalSnapshots() {
        val controller = MomoSayHiAutonomousBrowseController()
        assertFalse(controller.observeNonScrollableBoundary("empty-boundary", 1_000L))
        assertFalse(controller.isFinished())
        assertFalse(controller.observeNonScrollableBoundary("empty-boundary", 1_100L))
        assertFalse(controller.isFinished())
        assertTrue(controller.observeNonScrollableBoundary("empty-boundary", 1_300L))
        assertTrue(controller.isFinished())
    }

    @Test fun changedBoundarySnapshotRestartsConfirmation() {
        val controller = MomoSayHiAutonomousBrowseController()
        assertFalse(controller.observeNonScrollableBoundary("boundary-a", 1_000L))
        assertFalse(controller.observeNonScrollableBoundary("boundary-b", 1_300L))
        assertFalse(controller.isFinished())
        assertTrue(controller.observeNonScrollableBoundary("boundary-b", 1_600L))
    }

    @Test fun gateDeferralDoesNotCountAsGestureCancellation() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 1_000L)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        assertTrue(controller.shouldScroll("page-a", 2_001L, true))

        controller.onGestureDeferred()

        assertFalse(controller.isFinished())
        // A gate deferral does not consume one of the two real platform-cancellation attempts.
        assertTrue(controller.shouldScroll("page-a", 2_002L, true))
        controller.onGestureCompleted(false)
        assertFalse(controller.isFinished())
    }

    @Test fun cancelledGestureDoesNotImmediatelyRetry() {
        val controller = MomoSayHiAutonomousBrowseController(minimumIntervalMs = 1_000L)
        assertFalse(controller.shouldScroll("page-a", 1_000L, true))
        assertTrue(controller.shouldScroll("page-a", 2_001L, true))
        controller.onGestureCompleted(false)
        assertFalse(controller.shouldScroll("page-a", 2_002L, true))
    }
}
