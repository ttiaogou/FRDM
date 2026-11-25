package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWriteAuthorizationTest {
    @Test
    fun pageRouteMustBeReadyAndMatchExactTaskEvenWithinSameMode() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val generation = 41L
        val privateLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", generation))
        var route: PageRuntimeSessionState? = PageRuntimeSessionState(
            generation, privateLease.taskId, RunMode.AI_REPLY, PageRuntimeLifecycle.STARTING, 1L
        )
        val gate = UiWriteAuthorization(tasks, { generation }, { RunMode.AI_REPLY }, { route })
        var writes = 0
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AUTONOMOUS_GESTURE) { ++writes })
        route = route?.copy(lifecycle = PageRuntimeLifecycle.READY)
        assertEquals(1, gate.withBoundAuthorizedWrite(UiWriteScope.AI_COMPOSER, privateLease) { ++writes })
        route = PageRuntimeSessionState(generation, "AI_REPLY_RECEIVED_GREETINGS", RunMode.AI_REPLY,
            PageRuntimeLifecycle.STARTING, 2L)
        assertFalse(gate.permitsBound(UiWriteScope.AI_COMPOSER, privateLease))
        val greetingLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.AUTONOMOUS_GESTURE) { ++writes })
        route = route?.copy(lifecycle = PageRuntimeLifecycle.READY)
        assertEquals(2, gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, greetingLease) { ++writes })
        assertNull(gate.withBoundAuthorizedWrite(UiWriteScope.AI_COMPOSER, privateLease) { ++writes })
        route = route?.copy(lifecycle = PageRuntimeLifecycle.STOPPING)
        assertFalse(gate.permits(UiWriteScope.VERIFIED_AI_BACK))
        route = null
        assertNull(gate.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION) { ++writes })
        assertEquals(2, writes)
    }

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
    fun verifiedGreetingComposerRequiresTheExactCurrentAiLease() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        var generation = 20L
        var mode: RunMode? = RunMode.AI_REPLY
        val gate = UiWriteAuthorization(tasks, { generation }, { mode })
        val privateLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", generation))
        assertFalse(gate.permits(UiWriteScope.VERIFIED_GREETING_COMPOSER))
        val greetingLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        var dispatched = 0
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertTrue(gate.permits(UiWriteScope.VERIFIED_GREETING_COMPOSER))
        assertEquals(1, gate.withAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER) { ++dispatched })

        generation = 21L
        assertNull(gate.withAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER) { ++dispatched })
        tasks.advanceRunGeneration(generation)
        assertFalse(tasks.isCurrent(greetingLease))
        assertEquals(1, dispatched)

        val freshLease = requireNotNull(tasks.tryAcquire("AI_REPLY_RECEIVED_GREETINGS", generation))
        mode = RunMode.ACCOUNT_BROWSE
        assertNull(gate.withAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER) { ++dispatched })
        mode = RunMode.AI_REPLY
        assertTrue(gate.permits(UiWriteScope.VERIFIED_GREETING_COMPOSER))
        assertTrue(tasks.release(freshLease))
        assertNull(gate.withAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER) { ++dispatched })
        assertEquals(1, dispatched)
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
    fun oldGreetingDraftLeaseCannotWriteAfterTaskHandoffEvenWhenNewOwnerIsAuthorized() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val gate = UiWriteAuthorization(tasks, { 30L }, { RunMode.AI_REPLY })
        val privateLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 30L))
        val firstGreetingLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        var actions = 0
        assertEquals(1, gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, firstGreetingLease) { ++actions })
        val nextPrivateLease = requireNotNull(tasks.transfer(firstGreetingLease, "AI_REPLY_PRIVATE_MESSAGES"))
        assertNull(gate.withBoundAuthorizedWrite(UiWriteScope.AI_COMPOSER, firstGreetingLease) { ++actions })
        assertNull(gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, firstGreetingLease) { ++actions })
        assertTrue(gate.permitsBound(UiWriteScope.AI_COMPOSER, nextPrivateLease))
        val laterGreetingLease = requireNotNull(tasks.transfer(nextPrivateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        assertNull(gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, firstGreetingLease) { ++actions })
        assertEquals(2, gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, laterGreetingLease) { ++actions })
        assertEquals(2, actions)
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
