package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
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

        controller.markCompletedAwaitingUserExit()

        assertFalse(controller.isActive)
        assertTrue(controller.completedAwaitingUserExit)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
    }

    @Test
    fun traversalReturnClearsOnlyItsOwnTaskState() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.markAwaitingNormalInboxAfterReturn()

        controller.markTraversalReturnedToInbox()

        assertFalse(controller.isActive)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
    }

    @Test
    fun confirmedUserExitClearsOnlyTheCompletedPageLatch() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markCompletedAwaitingUserExit()

        controller.markAwaitingNormalInboxAfterReturn()
        controller.acknowledgeCompletedTaskExit()

        assertFalse(controller.isActive)
        assertFalse(controller.completedAwaitingUserExit)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
    }

    @Test
    fun resetClearsLifecycleFlagsWithoutRestoringCompletedTask() {
        val controller = ReceivedGreetingsTaskController(AiReplyTaskOwnership(null, { 1L }, {}))
        controller.markStarted()
        controller.markCompletedAwaitingUserExit()
        controller.markAwaitingNormalInboxAfterReturn()
        assertFalse(controller.isActive)
        assertTrue(controller.completedAwaitingUserExit)
        assertTrue(controller.awaitingNormalInboxAfterReturn)
        controller.reset()
        assertFalse(controller.isActive)
        assertFalse(controller.completedAwaitingUserExit)
        assertFalse(controller.awaitingNormalInboxAfterReturn)
    }
}
