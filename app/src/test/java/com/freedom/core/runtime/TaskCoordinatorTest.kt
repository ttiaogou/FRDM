package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCoordinatorTest {
    @Test
    fun oneTaskOwnsTheUiSlotUntilItTransfersOrReleases() {
        val uiOwnership = UiOperationOwnershipCoordinator()
        val coordinator = TaskCoordinator(uiOwnership)

        val privateTask = requireNotNull(coordinator.tryAcquire("ai.private_messages", 7L))

        assertEquals("ai.private_messages", coordinator.activeTaskId())
        assertNull(coordinator.tryAcquire("ai.received_greetings", 7L))
        assertTrue(coordinator.isCurrent(privateTask))

        val greetingsTask = requireNotNull(
            coordinator.transfer(privateTask, "ai.received_greetings")
        )

        assertFalse(coordinator.isCurrent(privateTask))
        assertTrue(coordinator.isCurrent(greetingsTask))
        assertEquals("ai.received_greetings", uiOwnership.currentOwnerId())
        assertTrue(coordinator.release(greetingsTask))
        assertNull(coordinator.activeTaskId())
        assertNull(uiOwnership.currentOwnerId())
    }

    @Test
    fun staleTaskLeaseCannotTransferOrReleaseTheCurrentTask() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val first = requireNotNull(coordinator.tryAcquire("account_browse", 2L))
        val second = requireNotNull(coordinator.transfer(first, "ai.private_messages"))

        assertNull(coordinator.transfer(first, "ai.received_greetings"))
        assertFalse(coordinator.release(first))
        assertTrue(coordinator.isCurrent(second))
        assertEquals("ai.private_messages", coordinator.activeTaskId())
    }

    @Test
    fun nextRunReleasesPreviousTaskAndRejectsLateOldGenerationWork() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val oldTask = requireNotNull(coordinator.tryAcquire("account_browse", 10L))
        val nextTask = requireNotNull(coordinator.tryAcquire("ai.private_messages", 11L))

        assertFalse(coordinator.isCurrent(oldTask))
        assertFalse(coordinator.release(oldTask))
        assertNull(coordinator.tryAcquire("ai.received_greetings", 10L))
        assertTrue(coordinator.isCurrent(nextTask))
        assertEquals("ai.private_messages", coordinator.activeTaskId())
    }
}
