package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiOperationOwnershipCoordinatorTest {
    @Test
    fun admitsOnlyOneOwnerAndAllowsIdempotentReacquisition() {
        val coordinator = UiOperationOwnershipCoordinator()

        val first = coordinator.tryAcquire("task.a", 1L)

        assertNotNull(first)
        assertEquals(first, coordinator.tryAcquire("task.a", 1L))
        assertNull(coordinator.tryAcquire("task.b", 1L))
        assertEquals("task.a", coordinator.currentOwnerId())
    }

    @Test
    fun transferInvalidatesOldLeaseAndReleaseOpensTheSlot() {
        val coordinator = UiOperationOwnershipCoordinator()
        val first = requireNotNull(coordinator.tryAcquire("task.private", 4L))

        val transferred = coordinator.transfer(first, "task.greetings")

        assertNotNull(transferred)
        assertFalse(coordinator.isCurrent(first))
        assertFalse(coordinator.release(first))
        assertEquals("task.greetings", coordinator.currentOwnerId())
        assertTrue(coordinator.release(requireNotNull(transferred)))
        assertNull(coordinator.currentOwnerId())
        assertNotNull(coordinator.tryAcquire("task.private", 4L))
    }

    @Test
    fun newerRunInvalidatesOldLeaseAndRejectsLateAcquisition() {
        val coordinator = UiOperationOwnershipCoordinator()
        val oldLease = requireNotNull(coordinator.tryAcquire("task.old", 1L))
        val newLease = requireNotNull(coordinator.tryAcquire("task.new", 2L))

        assertFalse(coordinator.isCurrent(oldLease))
        assertFalse(coordinator.release(oldLease))
        assertNull(coordinator.tryAcquire("task.late", 1L))
        assertTrue(coordinator.isCurrent(newLease))
        assertEquals("task.new", coordinator.currentOwnerId())
    }
}
