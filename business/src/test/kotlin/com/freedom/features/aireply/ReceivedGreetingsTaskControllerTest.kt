package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsTaskControllerTest {
    @Test
    fun confirmedListTaskTransitionsToCompletedWaitingForUserExit() {
        val controller = ReceivedGreetingsTaskController(
            AiReplyTaskOwnership(TaskCoordinator(UiOperationOwnershipCoordinator()), { 3L }, {})
        )

        controller.markStarted()
        assertTrue(controller.isActive)
        assertFalse(controller.completedAwaitingUserExit)

        controller.nextRecoveryRetryCount()
        controller.markCompletedAwaitingUserExit()

        assertFalse(controller.isActive)
        assertTrue(controller.completedAwaitingUserExit)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
        assertEquals(0, controller.recoveryRetryCount)
    }

    @Test
    fun traversalReturnClearsOnlyItsOwnTaskState() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.markAwaitingNormalInboxAfterReturn()
        controller.nextRecoveryRetryCount()

        controller.markTraversalReturnedToInbox()

        assertFalse(controller.isActive)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
        assertEquals(0, controller.recoveryRetryCount)
    }

    @Test
    fun confirmedUserExitClearsOnlyTheCompletedPageLatch() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markCompletedAwaitingUserExit()
        controller.nextRecoveryRetryCount()

        controller.acknowledgeCompletedTaskExit()

        assertFalse(controller.isActive)
        assertFalse(controller.completedAwaitingUserExit)
        assertEquals(1, controller.recoveryRetryCount)
    }

    @Test
    fun retryCounterIsOwnedAndResetByGreetingsTaskController() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))

        assertEquals(1, controller.nextRecoveryRetryCount())
        assertEquals(2, controller.nextRecoveryRetryCount())
        controller.resetRecoveryRetryCount()
        assertEquals(0, controller.recoveryRetryCount)
    }
}
