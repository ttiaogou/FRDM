package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesTaskControllerTest {
    @Test
    fun privateMessageTaskRequiresAnExplicitLeaseAndCanReleaseIt() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val controller = PrivateMessagesTaskController(
            ownership = AiReplyTaskOwnership(
                coordinator = coordinator,
                currentRunGeneration = { 4L },
                onLog = {}
            )
        )
        controller.beginRun(4L)
        assertFalse(controller.ownsTask())
        assertTrue(controller.claimOwnership("start private-message task"))
        assertEquals(PrivateMessagesTaskController.TASK_ID, coordinator.activeTaskId())
        assertTrue(controller.ownsTask())
        assertTrue(controller.releaseOwnership("finish private-message task"))
        assertFalse(controller.ownsTask())
    }

    @Test
    fun staleRunCannotReuseOrClaimPrivateMessageTaskLease() {
        var generation = 2L
        val ownership = AiReplyTaskOwnership(
            TaskCoordinator(UiOperationOwnershipCoordinator()),
            { generation }, {}
        )
        val controller = PrivateMessagesTaskController(ownership)
        controller.beginRun(2L)
        assertTrue(controller.claimOwnership("old private-message run"))
        assertTrue(controller.ownsTask())

        generation = 3L
        assertFalse(controller.ownsTask())
        assertFalse(controller.claimOwnership("stale run must not regain UI lease"))
        controller.beginRun(3L)
        assertTrue(controller.claimOwnership("new private-message run"))
        assertTrue(controller.ownsTask())
    }

    @Test
    fun privateMessagesTaskOwnsItsTaskLeaseAndCanTransferItToAnotherAiTask() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ownership = AiReplyTaskOwnership(coordinator, { 9L }, {})
        val privateTask = PrivateMessagesTaskController(ownership)
        val greetingsTask = ReceivedGreetingsTaskController(ownership)
        privateTask.beginRun(9L)

        assertTrue(privateTask.claimOwnership("private start"))
        assertTrue(privateTask.ownsTask())
        assertTrue(greetingsTask.claimOwnership("greetings handoff"))
        assertFalse(privateTask.ownsTask())
        assertTrue(greetingsTask.ownsTask())
    }
}
