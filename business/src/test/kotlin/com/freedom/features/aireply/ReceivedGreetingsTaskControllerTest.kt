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

        controller.requestExhaustedReturn()

        assertTrue(controller.isActive)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        assertFalse(controller.returnRequested)
        assertTrue(controller.ownsTask())
    }

    @Test
    fun traversalReturnClearsOnlyItsOwnTaskState() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.requestExhaustedReturn()
        controller.markReturnRequested()

        // Submission is not destination confirmation.
        assertFalse(controller.finishVerifiedHandoff())
        assertTrue(controller.isActive)
        controller.markInboxConfirmed()
        assertTrue(controller.finishVerifiedHandoff())

        assertFalse(controller.isActive)
        assertTrue(controller.isSuspended)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
        assertFalse(controller.returnRequested)
    }

    @Test
    fun repeatedCompletionDoesNotEndTaskOrRestartTraversal() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.requestExhaustedReturn()
        controller.markReturnRequested()

        controller.requestExhaustedReturn()
        controller.markStarted()

        assertTrue(controller.isActive)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        assertTrue(controller.returnRequested)
    }

    @Test
    fun suspensionAndReadmissionDoNotBehaveLikeRunReset() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.suspendForRecovery()
        assertFalse(controller.isActive)
        assertTrue(controller.isSuspended)
        assertFalse(controller.requestReturn(AiReplyTaskYieldReason.SAFE_SESSION_BOUNDARY))
        controller.markStarted()
        assertTrue(controller.isActive)
        assertFalse(controller.isSuspended)
        controller.reset()
        assertFalse(controller.isActive)
        assertFalse(controller.isSuspended)
    }

    @Test
    fun resetClearsLifecycleFlagsWithoutRestoringCompletedTask() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.requestExhaustedReturn()
        controller.markReturnRequested()
        assertTrue(controller.isActive)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        assertTrue(controller.returnRequested)
        controller.reset()
        controller.requestExhaustedReturn()
        controller.markReturnRequested()
        assertFalse(controller.isActive)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
        assertFalse(controller.returnRequested)
    }
}
