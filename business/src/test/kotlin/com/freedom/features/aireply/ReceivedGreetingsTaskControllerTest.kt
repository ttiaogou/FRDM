package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsTaskControllerTest {
    @Test
    fun confirmedListTaskTransitionsToActiveReturnPending() {
        val ownership = AiReplyTaskOwnership(TaskCoordinator(UiOperationOwnershipCoordinator()), { 3L }, {})
        ownership.beginRun(3L)
        val controller = ReceivedGreetingsTaskController(ownership)

        assertTrue(controller.claimOwnership("start"))
        controller.markStarted()
        assertTrue(controller.isActive)
        assertFalse(controller.awaitingNormalInboxAfterReturn)

        controller.markTraversalCompleted()

        assertTrue(controller.isActive)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        assertFalse(controller.returnRequested)
        assertTrue(controller.ownsTask())
    }

    @Test
    fun traversalReturnClearsOnlyItsOwnTaskState() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.markTraversalCompleted()
        controller.markReturnRequested()

        controller.markTraversalReturnedToInbox()

        assertFalse(controller.isActive)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
        assertFalse(controller.returnRequested)
    }

    @Test
    fun repeatedCompletionDoesNotEndTaskOrRestartTraversal() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.markTraversalCompleted()
        controller.markReturnRequested()

        controller.markTraversalCompleted()
        controller.markNavigationStarted()

        assertTrue(controller.isActive)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        assertTrue(controller.returnRequested)
    }

    @Test
    fun resetClearsLifecycleFlagsWithoutRestoringCompletedTask() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.markTraversalCompleted()
        controller.markReturnRequested()
        assertTrue(controller.isActive)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        assertTrue(controller.returnRequested)
        controller.reset()
        controller.markTraversalCompleted()
        controller.markReturnRequested()
        assertFalse(controller.isActive)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
        assertFalse(controller.returnRequested)
    }
}
