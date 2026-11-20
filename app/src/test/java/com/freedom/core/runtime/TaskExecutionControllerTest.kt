package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionControllerTest {
    @Test
    fun oneControllerCanTransferOwnershipBetweenBusinessTasksWithoutAnUnownedGap() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val execution = TaskExecutionController(coordinator, currentRunGeneration = { 4L })
        execution.beginRun(4L)

        assertTrue(execution.claim("AI_REPLY_PRIVATE_MESSAGES", "private messages started"))
        assertTrue(execution.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", coordinator.activeTaskId())

        assertTrue(execution.claim("AI_REPLY_RECEIVED_GREETINGS", "greetings page confirmed"))
        assertFalse(execution.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertTrue(execution.owns("AI_REPLY_RECEIVED_GREETINGS"))
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", coordinator.activeTaskId())

        assertTrue(execution.release("AI_REPLY_RECEIVED_GREETINGS", "greetings completed"))
        assertEquals(null, coordinator.activeTaskId())
    }

    @Test
    fun separateBusinessControllerCannotStealAnActiveLease() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val privateMessages = TaskExecutionController(coordinator, currentRunGeneration = { 9L })
        val greetings = TaskExecutionController(coordinator, currentRunGeneration = { 9L })
        privateMessages.beginRun(9L)
        greetings.beginRun(9L)

        assertTrue(privateMessages.claim("AI_REPLY_PRIVATE_MESSAGES", "private started"))
        assertFalse(greetings.claim("AI_REPLY_RECEIVED_GREETINGS", "unconfirmed page"))
        assertTrue(privateMessages.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertFalse(greetings.owns("AI_REPLY_RECEIVED_GREETINGS"))
    }

    @Test
    fun sameControllerDoesNotReuseItsLeaseAcrossRunGenerations() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val execution = TaskExecutionController(coordinator, currentRunGeneration = { 12L })
        execution.beginRun(11L)
        assertTrue(execution.claim("ACCOUNT_BROWSE", "first generation"))

        execution.beginRun(12L)

        assertFalse(execution.owns("ACCOUNT_BROWSE"))
        assertTrue(execution.claim("ACCOUNT_BROWSE", "reconnected generation"))
        assertTrue(execution.owns("ACCOUNT_BROWSE"))
        assertEquals(12L, coordinator.tryAcquire("ACCOUNT_BROWSE", 12L)?.runGeneration)
    }

    @Test
    fun newerRunInvalidatesThePreviousControllerLeaseAndOldReleaseCannotAffectIt() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val oldController = TaskExecutionController(coordinator, currentRunGeneration = { 2L })
        val newController = TaskExecutionController(coordinator, currentRunGeneration = { 3L })
        oldController.beginRun(2L)
        newController.beginRun(3L)

        assertTrue(oldController.claim("ACCOUNT_BROWSE", "old run started"))
        assertTrue(newController.claim("AI_REPLY_PRIVATE_MESSAGES", "new run started"))
        assertFalse(oldController.owns("ACCOUNT_BROWSE"))
        assertFalse(oldController.release("ACCOUNT_BROWSE", "late stop"))
        assertTrue(newController.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", coordinator.activeTaskId())
    }

    @Test
    fun isolatedControllerClearsLocalLeaseWhenReleasedWithoutCoordinator() {
        val execution = TaskExecutionController(coordinator = null, currentRunGeneration = { 2L })
        execution.beginRun(2L)

        assertTrue(execution.claim("AI_REPLY_PRIVATE_MESSAGES", "isolated start"))
        assertTrue(execution.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertTrue(execution.release("AI_REPLY_PRIVATE_MESSAGES", "isolated stop"))
        assertFalse(execution.owns("AI_REPLY_PRIVATE_MESSAGES"))
    }
}
