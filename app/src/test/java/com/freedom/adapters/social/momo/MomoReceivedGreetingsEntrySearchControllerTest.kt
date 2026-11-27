package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoReceivedGreetingsEntrySearchControllerTest {
    private var nextRequest = 0
    private var clockMs = 0L

    private fun observe(
        controller: MomoReceivedGreetingsEntrySearchController,
        fingerprint: String,
        edge: Boolean? = false
    ): MomoReceivedGreetingsEntrySearchController.Observation {
        clockMs += 500L
        return controller.observeAfterGesture(fingerprint, edge, clockMs)
    }

    private fun confirmEdge(
        controller: MomoReceivedGreetingsEntrySearchController,
        fingerprint: String,
        expected: MomoReceivedGreetingsEntrySearchController.Observation
    ) {
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(controller, fingerprint, true))
        assertEquals(expected, observe(controller, fingerprint, true))
    }

    private fun readmit(controller: MomoReceivedGreetingsEntrySearchController, key: String = "ready") {
        clockMs += com.freedom.features.recovery.RecoveryScheduler.longWaitDelayMs
        assertFalse(controller.mayDispatch(key, clockMs))
        clockMs += 500L
        assertTrue(controller.mayDispatch(key, clockMs))
    }

    private fun beginGesture(controller: MomoReceivedGreetingsEntrySearchController): String {
        val id = "request-${++nextRequest}"
        assertTrue(controller.tryBeginGesture(controller.captureSearchGeneration(), id))
        return id
    }

    private fun completeGesture(controller: MomoReceivedGreetingsEntrySearchController, baseline: String) {
        assertTrue(controller.finishGesture(beginGesture(controller), baseline, clockMs))
    }

    @Test
    fun startsBySearchingLaterRowsAndReversesAfterTwoIndependentEdgeConfirmations() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        completeGesture(controller, "top-viewport")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "lower-viewport"))
        confirmEdge(controller, "bottom-viewport", MomoReceivedGreetingsEntrySearchController.Observation.REVERSED)
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        assertTrue(controller.isActive())
    }

    @Test
    fun knownAboveStartsTowardEarlierRowsAndReversesTowardLaterRowsOnlyAtEdge() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        assertTrue(controller.start(MomoReceivedGreetingsEntryPosition.ABOVE))
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        completeGesture(controller, "middle")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "earlier"))
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        confirmEdge(controller, "top", MomoReceivedGreetingsEntrySearchController.Observation.REVERSED)
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        completeGesture(controller, "top")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "bottom"))
        confirmEdge(controller, "bottom", MomoReceivedGreetingsEntrySearchController.Observation.COMPLETE)
    }

    @Test
    fun knownBelowAndUnknownStartTowardLaterRows() {
        for (position in listOf(MomoReceivedGreetingsEntryPosition.BELOW, MomoReceivedGreetingsEntryPosition.UNKNOWN)) {
            val controller = MomoReceivedGreetingsEntrySearchController()
            assertTrue(controller.start(position))
            assertEquals(VerticalSwipeDirection.UP, controller.direction())
            confirmEdge(controller, "bottom", MomoReceivedGreetingsEntrySearchController.Observation.REVERSED)
            assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
            assertTrue(controller.isActive())
            assertFalse(controller.isComplete())
        }
    }

    @Test
    fun visiblePositionCannotAuthorizeSearchOrLeaveOldSweepActive() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start(MomoReceivedGreetingsEntryPosition.ABOVE)
        val oldGeneration = controller.captureSearchGeneration()
        val request = beginGesture(controller)
        assertFalse(controller.start(MomoReceivedGreetingsEntryPosition.VISIBLE))
        assertFalse(controller.isActive())
        assertFalse(controller.isComplete())
        assertTrue(controller.isGestureInFlight())
        assertFalse(controller.tryBeginGesture(oldGeneration, "stale"))
        assertFalse(controller.finishGesture(request, "old", clockMs))
        assertFalse(controller.isGestureInFlight())
    }

    @Test
    fun rejectedOrDeferredSwipeNeverReversesKnownAboveSearch() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start(MomoReceivedGreetingsEntryPosition.ABOVE)
        repeat(5) { index ->
            assertTrue(controller.finishGesture(beginGesture(controller), null, clockMs))
            assertEquals(index + 1, controller.retryAttempt())
            assertFalse(controller.tryBeginGesture(controller.captureSearchGeneration(), "immediate-$index"))
            assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
                observe(controller, "same", null))
            assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
            assertFalse(controller.isComplete())
            readmit(controller)
        }
    }

    @Test
    fun newSweepUsesLatestHintRatherThanPreviousDirectionOrEdgeEvidence() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start(MomoReceivedGreetingsEntryPosition.ABOVE)
        completeGesture(controller, "old")
        observe(controller, "old")
        controller.reset()
        controller.start(MomoReceivedGreetingsEntryPosition.UNKNOWN)
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        completeGesture(controller, "new")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "new"))
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun viewportMovementResetsBoundaryCounter() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()

        completeGesture(controller, "viewport-a")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "viewport-a")
        )
        completeGesture(controller, "viewport-a")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "viewport-b")
        )
        completeGesture(controller, "viewport-b")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "viewport-b")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun completesOnlyAfterBothDirectionsReachIndependentlyConfirmedEdges() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        confirmEdge(controller, "start", MomoReceivedGreetingsEntrySearchController.Observation.REVERSED)
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        completeGesture(controller, "start")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE, observe(controller, "end"))
        confirmEdge(controller, "end", MomoReceivedGreetingsEntrySearchController.Observation.COMPLETE)
        assertTrue(controller.isComplete())
        assertFalse(controller.isActive())
    }

    @Test
    fun cancelledGestureDoesNotCountAsAListBoundary() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        assertTrue(controller.finishGesture(beginGesture(controller), null, clockMs))
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "same")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun twoCompletedStationarySwipesWithUnknownOrContradictoryEdgeEnterObservationHold() {
        for (edge in listOf(null, false)) {
            val controller = MomoReceivedGreetingsEntrySearchController()
            controller.start()
            completeGesture(controller, "same")
            assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
                observe(controller, "same", edge))
            completeGesture(controller, "same")
            assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
                observe(controller, "same", edge))
            assertEquals(1, controller.retryAttempt())
            assertEquals(VerticalSwipeDirection.UP, controller.direction())
            assertFalse(controller.isComplete())
            assertFalse(controller.tryBeginGesture(controller.captureSearchGeneration(), "blocked"))
        }
    }

    @Test
    fun edgeRequiresSpacedMatchingFreshProofAndNoInFlightGesture() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(controller, "edge-a", true))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(controller, "edge-b", true))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "edge-b", false))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(controller, "edge-b", true))
        controller.invalidateObservation()
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(controller, "edge-b", true))
        val request = beginGesture(controller)
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(controller, "edge-b", true))
        assertTrue(controller.finishGesture(request, null, clockMs))
        confirmEdge(controller, "edge-b", MomoReceivedGreetingsEntrySearchController.Observation.REVERSED)
    }

    @Test
    fun rejectionRequiresDeadlineAndTwoMatchingReadyObservationsThenAllowsOnlyOneProbe() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start(MomoReceivedGreetingsEntryPosition.ABOVE)
        assertTrue(controller.finishGesture(beginGesture(controller), null, 0L))
        assertFalse(controller.mayDispatch("ready", 499L))
        assertFalse(controller.mayDispatch("ready", 500L))
        assertFalse(controller.mayDispatch(null, 1000L))
        assertFalse(controller.mayDispatch("ready", 1500L))
        assertFalse(controller.mayDispatch("changed", 2000L))
        assertTrue(controller.mayDispatch("changed", 2500L))
        val retry = beginGesture(controller)
        assertFalse(controller.tryBeginGesture(controller.captureSearchGeneration(), "overlap"))
        assertTrue(controller.finishGesture(retry, null, 2500L))
        assertEquals(2, controller.retryAttempt())
        assertFalse(controller.mayDispatch("changed", 3499L))
        assertFalse(controller.mayDispatch("changed", 3500L))
        assertTrue(controller.mayDispatch("changed", 4000L))
        val probe = beginGesture(controller)
        assertTrue(controller.finishGesture(probe, "before", 4500L))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("after", false, 5000L))
        assertEquals(0, controller.retryAttempt())
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        assertTrue(controller.mayDispatch("new-ready", 5000L))
    }

    @Test
    fun invalidObservationRetiresRetrySamplesButPreservesDirectionAndBackoff() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start(MomoReceivedGreetingsEntryPosition.ABOVE)
        assertTrue(controller.finishGesture(beginGesture(controller), null, 0L))
        assertFalse(controller.mayDispatch("ready", 500L))
        controller.invalidateObservation()
        assertFalse(controller.mayDispatch("ready", 1000L))
        assertTrue(controller.mayDispatch("ready", 1500L))
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())
        assertEquals(1, controller.retryAttempt())
        controller.reset()
        controller.start()
        assertEquals(0, controller.retryAttempt())
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
            observe(controller, "middle-viewport")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())

        completeGesture(controller, "middle-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "lower-viewport")
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
        assertFalse(controller.finishGesture(oldRequest, "old-viewport", clockMs))
        assertFalse(controller.isGestureInFlight())
        assertTrue(controller.isActive())

        val newRequest = beginGesture(controller)
        assertFalse(controller.finishGesture(oldRequest, "new-viewport", clockMs))
        assertFalse(controller.finishGesture(oldRequest, null, clockMs))
        assertTrue(controller.isGestureInFlight())
        assertTrue(controller.finishGesture(newRequest, "new-viewport", clockMs))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "new-viewport"))
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
        assertFalse(controller.finishGesture(oldRequest, "same", clockMs))
        assertTrue(controller.isGestureInFlight())
        assertTrue(controller.finishGesture(newRequest, "same", clockMs))
        assertFalse(controller.finishGesture(newRequest, "same", clockMs))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "same"))
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
    }

    @Test
    fun completedSearchResetAllowsFreshSearchWithoutStaleBoundaryEvidence() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()
        confirmEdge(controller, "same", MomoReceivedGreetingsEntrySearchController.Observation.REVERSED)
        confirmEdge(controller, "same", MomoReceivedGreetingsEntrySearchController.Observation.COMPLETE)
        assertTrue(controller.isComplete())
        assertFalse(controller.isActive())
        controller.reset()
        assertFalse(controller.isComplete())
        assertFalse(controller.isActive())
        controller.start()
        assertTrue(controller.isActive())
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(controller, "same"))
    }
}
