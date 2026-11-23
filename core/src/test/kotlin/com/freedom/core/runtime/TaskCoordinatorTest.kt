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
    fun currentLeaseAndGuardOnlyExposeTheExactActiveTaskCapability() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val oldLease = requireNotNull(coordinator.tryAcquire("ai.private_messages", 5L))

        assertEquals(oldLease, coordinator.currentLease())
        val nextLease = requireNotNull(coordinator.transfer(oldLease, "ai.received_greetings"))

        assertEquals(nextLease, coordinator.currentLease())
        var oldActionRan = false
        assertNull(coordinator.withCurrentLease(oldLease) {
            oldActionRan = true
            "old"
        })
        assertFalse(oldActionRan)

        assertEquals("current", coordinator.withCurrentLease(nextLease) { "current" })
    }

    @Test
    fun runGenerationBoundaryRevokesTaskAndUiLeaseBeforeAnyReplacementClaim() {
        val uiOwnership = UiOperationOwnershipCoordinator()
        val coordinator = TaskCoordinator(uiOwnership)
        val old = requireNotNull(coordinator.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 4L))

        coordinator.advanceRunGeneration(5L)

        assertNull(coordinator.activeTaskId())
        assertNull(coordinator.currentLease())
        assertNull(uiOwnership.currentOwnerId())
        assertFalse(coordinator.isCurrent(old))
        assertFalse(coordinator.release(old))
        assertNull(coordinator.tryAcquire("ACCOUNT_BROWSE", 4L))

        val fresh = requireNotNull(coordinator.tryAcquire("ACCOUNT_BROWSE", 5L))
        assertTrue(coordinator.isCurrent(fresh))
        assertEquals("ACCOUNT_BROWSE", uiOwnership.currentOwnerId())
    }

    @Test
    fun repeatedOrStaleGenerationNotificationCannotRevokeCurrentTask() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        coordinator.advanceRunGeneration(6L)
        val current = requireNotNull(coordinator.tryAcquire("AI_REPLY_RECEIVED_GREETINGS", 6L))

        coordinator.advanceRunGeneration(6L)
        coordinator.advanceRunGeneration(5L)

        assertTrue(coordinator.isCurrent(current))
        assertEquals(current, coordinator.currentLease())
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", coordinator.activeTaskId())

        coordinator.advanceRunGeneration(7L)
        assertNull(coordinator.activeTaskId())
        assertFalse(coordinator.release(current))
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
