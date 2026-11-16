package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoReceivedGreetingsEntrySearchControllerTest {
    @Test
    fun startsBySearchingLaterRowsAndReversesAfterTwoNoMotionConfirmations() {
        val controller = MomoReceivedGreetingsEntrySearchController()
        controller.start()

        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        controller.onGestureCompleted("top-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("top-viewport")
        )
        controller.onGestureCompleted("top-viewport")
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

        controller.onGestureCompleted("viewport-a")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("viewport-a")
        )
        controller.onGestureCompleted("viewport-a")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("viewport-b")
        )
        controller.onGestureCompleted("viewport-b")
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
            controller.onGestureCompleted("start")
            controller.observeAfterGesture("start")
        }
        assertEquals(VerticalSwipeDirection.DOWN, controller.direction())

        controller.onGestureCompleted("end")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("end")
        )
        controller.onGestureCompleted("end")
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
        controller.onGestureCompleted("same")
        controller.onGestureFailed()
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
        controller.onGestureCompleted("top-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("middle-viewport")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())

        controller.onGestureCompleted("middle-viewport")
        assertEquals(
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            controller.observeAfterGesture("lower-viewport")
        )
        assertEquals(VerticalSwipeDirection.UP, controller.direction())
        assertTrue(controller.isActive())
    }

}
