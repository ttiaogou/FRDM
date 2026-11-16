package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesTaskControllerTest {
    @Test
    fun quotaCountsOnlyExplicitlyRecordedSuccessfulObservations() {
        val controller = PrivateMessagesTaskController(
            ownership = AiReplyTaskOwnership(
                coordinator = TaskCoordinator(UiOperationOwnershipCoordinator()),
                currentRunGeneration = { 4L },
                onLog = {}
            ),
            chooseRandomInt = { minimum, maximum ->
                assertEquals(5, minimum)
                assertEquals(11, maximum)
                6
            }
        )

        controller.resetQuota()

        assertEquals(0, controller.completedObservationCount)
        assertEquals(6, controller.targetObservationCount)
        repeat(5) { controller.recordSuccessfulObservation() }
        assertFalse(controller.quotaReached())
        assertEquals(6, controller.recordSuccessfulObservation())
        assertTrue(controller.quotaReached())
    }

    @Test
    fun eachNewPrivateMessageCycleResetsCountAndChoosesANewBoundedTarget() {
        val selectedTargets = ArrayDeque(listOf(5, 11))
        val controller = PrivateMessagesTaskController(
            ownership = AiReplyTaskOwnership(null, { 2L }, {}),
            chooseRandomInt = { _, _ -> selectedTargets.removeFirst() }
        )

        controller.resetQuota()
        repeat(5) { controller.recordSuccessfulObservation() }
        assertTrue(controller.quotaReached())

        controller.resetQuota()

        assertEquals(0, controller.completedObservationCount)
        assertEquals(11, controller.targetObservationCount)
        assertFalse(controller.quotaReached())
    }

    @Test
    fun privateMessagesTaskOwnsItsTaskLeaseAndCanTransferItToAnotherAiTask() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ownership = AiReplyTaskOwnership(coordinator, { 9L }, {})
        val privateTask = PrivateMessagesTaskController(ownership) { _, _ -> 8 }
        val greetingsTask = ReceivedGreetingsTaskController(ownership)
        privateTask.beginRun(9L)

        assertTrue(privateTask.claimOwnership("private start"))
        assertTrue(privateTask.ownsTask())
        assertTrue(greetingsTask.claimOwnership("greetings handoff"))
        assertFalse(privateTask.ownsTask())
        assertTrue(greetingsTask.ownsTask())
    }
}
