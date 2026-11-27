package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoReceivedGreetingsEntrySearchControllerTest {
    private var nextRequest = 0

    private fun beginGesture(controller: MomoReceivedGreetingsEntrySearchController): String {
        val id = "request-${++nextRequest}"
        assertTrue(controller.tryBeginGesture(controller.captureSearchGeneration(), id))
        return id
    }

    private fun completeGesture(controller: MomoReceivedGreetingsEntrySearchController, baseline: String) {
        assertTrue(controller.finishGesture(beginGesture(controller), baseline))
    }

    @Test
    fun startsBySearchingLaterRowsAndReversesAfterTwoNoMotionConfirmations() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()

        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        completeGesture(controller, "top-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("top-viewport")
        )
        completeGesture(controller, "top-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.REVERSED,
            controller.observeAfterGesture("top-viewport")
        )
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        assertTrue(controller.isActive())
    }

    @Test
    fun viewportMovementResetsBoundaryCounter() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()

        completeGesture(controller, "viewport-a")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("viewport-a")
        )
        completeGesture(controller, "viewport-a")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("viewport-b")
        )
        completeGesture(controller, "viewport-b")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("viewport-b")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun completesOnlyAfterBothDirectionsReachIndependentlyConfirmedEdges() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()

        repeat(2) {
            completeGesture(controller, "start")
            controller.observeAfterGesture("start")
        }
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())

        completeGesture(controller, "end")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("end")
        )
        completeGesture(controller, "end")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.COMPLETE,
            controller.observeAfterGesture("end")
        )
        assertTrue(controller.isComplete())
        assertFalse(controller.isActive())
    }

    @Test
    fun cancelledGestureDoesNotCountAsAListBoundary() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        assertTrue(controller.finishGesture(beginGesture(controller), null))
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("same")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun intermediateViewportMissesKeepSearchingLaterRowsBeforeBoundaryConfirmation() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()

        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        completeGesture(controller, "top-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("middle-viewport")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())

        completeGesture(controller, "middle-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("lower-viewport")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        assertTrue(controller.isActive())
    }

    @Test
    fun resetRetiresSearchEvidenceButKeepsAcceptedGestureBusyUntilItsOwnTerminalResult() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        val oldGeneration = controller.captureSearchGeneration()
        val oldRequest = beginGesture(controller)

        controller.reset()
        assertFalse(controller.isActive())
        assertFalse(controller.isComplete())
        assertTrue(controller.isGestureInFlight())
        controller.start()
        assertFalse(controller.tryBeginGesture(oldGeneration, "stale-preparation"))
        assertFalse(controller.tryBeginGesture(controller.captureSearchGeneration(), "overlap"))
        assertFalse(controller.finishGesture(oldRequest, "old-viewport"))
        assertFalse(controller.isGestureInFlight())
        assertTrue(controller.isActive())

        val newRequest = beginGesture(controller)
        assertFalse(controller.finishGesture(oldRequest, "new-viewport"))
        assertFalse(controller.finishGesture(oldRequest, null))
        assertTrue(controller.isGestureInFlight())
        assertTrue(controller.finishGesture(newRequest, "new-viewport"))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("new-viewport"))
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        assertFalse(controller.isGestureInFlight())
    }

    @Test
    fun runResetAndDuplicateTerminalCannotReleaseNewRequest() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        val oldRequest = beginGesture(controller)
        controller.resetForRun()
        assertFalse(controller.isGestureInFlight())
        assertFalse(controller.isActive())
        assertFalse(controller.isComplete())

        controller.start()
        val newRequest = beginGesture(controller)
        assertFalse(controller.finishGesture(oldRequest, "same"))
        assertTrue(controller.isGestureInFlight())
        assertTrue(controller.finishGesture(newRequest, "same"))
        assertFalse(controller.finishGesture(newRequest, "same"))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("same"))
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun completedSearchResetAllowsFreshSearchWithoutStaleBoundaryEvidence() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        repeat(4) {
            completeGesture(controller, "same")
            controller.observeAfterGesture("same")
        }
        assertTrue(controller.isComplete())
        assertFalse(controller.isActive())
        controller.reset()
        assertFalse(controller.isComplete())
        assertFalse(controller.isActive())
        controller.start()
        assertTrue(controller.isActive())
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("same"))
    }
}
