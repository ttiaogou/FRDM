package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWriteAuthorizationTest {
    @Test
    fun noLeaseOrNoRunModeNeverAuthorizesAnAutonomousWrite() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        var generation = 1L
        var mode: RunMode? = RunMode.AI_REPLY
        val gate = UiWriteAuthorization(tasks, { generation }, { mode })
        var dispatched = false

        for (scope in UiWriteScope.entries) {
            assertFalse(gate.permits(scope))
            assertNull(gate.withAuthorizedWrite(scope) { dispatched = true; "dispatched" })
        }
        assertFalse(dispatched)

        tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 1L)
        mode = null
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { "forbidden" })
        generation = 2L
        mode = RunMode.AI_REPLY
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
    }

    @Test
    fun browseLeaseCannotWriteAiComposerAndAiLeaseCannotPerformBrowseNodeAction() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        var mode = RunMode.ACCOUNT_BROWSE
        var generation = 3L
        val gate = UiWriteAuthorization(tasks, { generation }, { mode })
        val browse = requireNotNull(tasks.tryAcquire("ACCOUNT_BROWSE", generation))

        assertTrue(gate.permits(UiWriteScope.BROWSE_NODE_ACTION))
        assertEquals("browse", gate.withAuthorizedWrite(UiWriteScope.BROWSE_NODE_ACTION) { "browse" })
        assertTrue(gate.permits(UiWriteScope.GLOBAL_NAVIGATION))
        assertTrue(gate.permits(UiWriteScope.AUTONOMOUS_GESTURE))
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertFalse(gate.permits(UiWriteScope.VERIFIED_AI_BACK))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { "wrong owner" })

        tasks.advanceRunGeneration(4L)
        generation = 4L
        mode = RunMode.AI_REPLY
        assertFalse(tasks.isCurrent(browse))
        tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", generation)
        assertTrue(gate.permits(UiWriteScope.AI_COMPOSER))
        assertTrue(gate.permits(UiWriteScope.VERIFIED_AI_BACK))
        assertFalse(gate.permits(UiWriteScope.BROWSE_NODE_ACTION))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.BROWSE_NODE_ACTION) { "wrong mode" })
    }

    @Test
    fun greetingsLeaseCanNavigateButNeverMutatePrivateComposer() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val gate = UiWriteAuthorization(tasks, { 8L }, { RunMode.AI_REPLY })
        val privateLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 8L))
        assertTrue(gate.permits(UiWriteScope.AI_COMPOSER))

        val greetingsLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        assertTrue(tasks.isCurrent(greetingsLease))
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) { "wrong AI task" })
        assertEquals("nav", gate.withAuthorizedWrite(UiWriteScope.VERIFIED_AI_BACK) { "nav" })
        assertEquals("gesture", gate.withAuthorizedWrite(UiWriteScope.AUTONOMOUS_GESTURE) { "gesture" })
    }

    @Test
    fun staleOrReleasedLeaseNeverRunsPlatformMutationCallback() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        var generation = 11L
        val gate = UiWriteAuthorization(tasks, { generation }, { RunMode.ACCOUNT_BROWSE })
        val old = requireNotNull(tasks.tryAcquire("ACCOUNT_BROWSE", generation))
        var dispatched = 0
        assertEquals(1, gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++dispatched })

        generation = 12L
        assertNull(gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++dispatched })
        tasks.advanceRunGeneration(generation)
        assertFalse(tasks.release(old))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++dispatched })
        assertEquals(1, dispatched)

        val current = requireNotNull(tasks.tryAcquire("ACCOUNT_BROWSE", generation))
        assertTrue(tasks.release(current))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AUTONOMOUS_GESTURE) { ++dispatched })
        assertEquals(1, dispatched)
    }

    @Test
    fun fakeTaskIdOrWrongModeCannotUseAnotherOwnersLease() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        tasks.tryAcquire("UNREGISTERED_AUTOMATION", 15L)
        val gate = UiWriteAuthorization(tasks, { 15L }, { RunMode.AI_REPLY })
        UiWriteScope.entries.forEach { scope ->
            assertFalse(gate.permits(scope))
            assertNull(gate.withAuthorizedWrite(scope) { true })
        }
    }
}
