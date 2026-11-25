package com.freedom.application.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskExecutionController
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionCoordinatorTest {
    private class FakeState : RunStateRepository {
        var requested = false
        val writes = mutableListOf<String>()
        override fun isRunRequested() = requested
        override fun setRunRequested(requested: Boolean) {
            this.requested = requested
            writes += "running:$requested"
        }
    }

    private class FakeRuntime(override val mode: RunMode) : ModeRuntimePort {
        val calls = mutableListOf<String>()
        var externalRefreshCalls = 0
        override fun onRunStarted() { calls += "start" }
        override fun onRunStopped(reason: String) { calls += "stop:$reason" }
        override fun onAccessibilityServiceConnected() { calls += "connected" }
        override fun onAccessibilityServiceDisconnected() { calls += "disconnected" }
        override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) { calls += "target" }
        override fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) { calls += "global:${event.type}" }
        override fun onExternalRefresh() { externalRefreshCalls++ }
    }

    @Test
    fun pageDrivenStartAndReconnectWaitForFreshRoute() {
        val state = FakeState().apply {
            requested = true
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val ai = FakeRuntime(RunMode.AI_REPLY)
        var observations = 0
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse, RunMode.AI_REPLY to ai),
            RuntimeLogSink { },
            onObserveCurrentPage = { observations++ }
        )
        coordinator.onAccessibilityServiceConnected()
        assertEquals(1, observations)
        assertNull(coordinator.activeMode())
        assertTrue(browse.calls.isEmpty())
        assertTrue(ai.calls.isEmpty())
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        assertEquals(listOf("start"), ai.calls)
        assertEquals(RunMode.AI_REPLY, coordinator.activeMode())
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.READY, coordinator.currentPageRuntimeSession()?.lifecycle)
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        assertEquals(listOf("start"), ai.calls)
        coordinator.onAccessibilityServiceDisconnected()
        coordinator.onAccessibilityServiceConnected()
        assertEquals(2, observations)
        assertNull(coordinator.activeMode())
        assertTrue(browse.calls.isEmpty())
        assertEquals(listOf("start", "disconnected"), ai.calls)
    }

    @Test
    fun allSixIndependentPageSwitchesRevokeSourceAndDispatchOnlyToFreshDestination() {
        val routes = listOf(
            RunMode.ACCOUNT_BROWSE to "ACCOUNT_BROWSE",
            RunMode.AI_REPLY to "AI_REPLY_PRIVATE_MESSAGES",
            RunMode.AI_REPLY to "AI_REPLY_RECEIVED_GREETINGS"
        )
        for (source in routes) for (destination in routes.filter { it != source }) {
            val state = FakeState().apply { requested = true }
            val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
            val trace = mutableListOf<String>()
            lateinit var coordinator: RunSessionCoordinator
            fun runtime(runtimeMode: RunMode) = object : ModeRuntimePort {
                override val mode = runtimeMode
                override fun onRunStarted() = error("page route required")
                override fun onRunStopped(reason: String) = error("switch is not user stop")
                override fun onAccessibilityServiceConnected() = Unit
                override fun onAccessibilityServiceDisconnected() = Unit
                override fun onPageRuntimeStarted(
                    session: com.freedom.core.runtime.PageRuntimeSessionState,
                    onPrepared: (com.freedom.core.runtime.PageRuntimeSessionState) -> Unit
                ) {
                    trace += "start:${session.taskId}"
                    onPrepared(session)
                }
                override fun onPageRuntimeWillSuspend(reason: String) {
                    assertNull(coordinator.currentWriteRoute())
                    trace += "save:${tasks.activeTaskId()}"
                }
                override fun onPageRuntimeSuspended(reason: String) {
                    assertNull(tasks.currentLease())
                    assertNull(coordinator.currentWriteRoute())
                    trace += "isolated"
                }
                override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) {
                    trace += "execute:${coordinator.currentWriteRoute()?.taskId}"
                }
            }
            coordinator = RunSessionCoordinator(state,
                routes.map { it.first }.distinct().associateWith { runtime(it) }, RuntimeLogSink { },
                taskCoordinator = tasks)
            coordinator.synchronizeRunRequest()
            val first = requireNotNull(coordinator.routeIndependentPageTask(source.first, source.second))
            val sourceLease = requireNotNull(tasks.tryAcquire(source.second, first.runGeneration))
            assertTrue(coordinator.admitBusinessPageTaskOwnership(sourceLease, false))
            // An unresolved page hold is not a task stop and must not block a new task.
            coordinator.reconcileUnclassifiedPage(first.runGeneration, false)
            assertNull(coordinator.currentWriteRoute())

            assertNull(coordinator.routeIndependentPageTask(destination.first, destination.second, first.runGeneration))
            assertNull(tasks.currentLease())
            assertNull(coordinator.currentWriteRoute())
            assertFalse(coordinator.admitBusinessPageTaskOwnership(sourceLease, true))
            // The old snapshot cannot start either task after source isolation.
            assertNull(coordinator.routeIndependentPageTask(destination.first, destination.second, first.runGeneration))
            assertNull(coordinator.routeIndependentPageTask(source.first, source.second, first.runGeneration))
            val next = requireNotNull(coordinator.routeIndependentPageTask(destination.first, destination.second,
                coordinator.currentRunGeneration()))
            assertTrue(next.runGeneration > first.runGeneration)
            assertNull(coordinator.currentWriteRoute())
            val destinationLease = requireNotNull(tasks.tryAcquire(destination.second, next.runGeneration))
            assertTrue(coordinator.admitBusinessPageTaskOwnership(destinationLease, false))
            assertEquals(destination.second, coordinator.currentWriteRoute()?.taskId)
            coordinator.dispatchTargetApplicationEvent(AccessibilityEventEnvelope(
                type = com.freedom.core.accessibility.events.AccessibilityEventType.VIEW_CLICKED,
                packageName = "com.immomo.momo", className = "android.widget.Button",
                contentChanges = emptySet(), eventTimeMillis = 123L, eventText = emptyList(),
                contentDescription = null, source = null
            ))
            assertEquals(listOf("start:${source.second}", "save:${source.second}", "isolated",
                "start:${destination.second}", "execute:${destination.second}"), trace)
            assertTrue(state.requested)
        }
    }

    @Test
    fun independentSiblingPageSwitchSuspendsOldTaskAndRequiresNewReadInBothDirections() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val trace = mutableListOf<String>()
        var reads = 0
        lateinit var coordinator: RunSessionCoordinator
        val ai = object : ModeRuntimePort {
            override val mode = RunMode.AI_REPLY
            override fun onRunStarted() = error("selected task required")
            override fun onRunStopped(reason: String) = error("not a user stop")
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
            override fun onPageRuntimeStarted(session: com.freedom.core.runtime.PageRuntimeSessionState,
                onPrepared: (com.freedom.core.runtime.PageRuntimeSessionState) -> Unit) {
                trace += "start:${session.taskId}"
                onPrepared(session)
            }
            override fun onPageRuntimeWillSuspend(reason: String) {
                assertNull(coordinator.currentWriteRoute())
                trace += "save:${tasks.currentLease()?.taskId}"
            }
            override fun onPageRuntimeSuspended(reason: String) {
                assertNull(tasks.currentLease())
                assertNull(coordinator.currentWriteRoute())
                trace += "isolate"
            }
        }
        coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai), RuntimeLogSink { },
            taskCoordinator = tasks, onObserveCurrentPage = { reads++ })
        coordinator.synchronizeRunRequest()
        val ids = listOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS", "AI_REPLY_PRIVATE_MESSAGES")
        var previousGeneration = -1L
        for ((index, id) in ids.withIndex()) {
            val next = requireNotNull(coordinator.routeIndependentPageTask(ai.mode, id))
            assertTrue(next.runGeneration > previousGeneration)
            previousGeneration = next.runGeneration
            assertNull(coordinator.currentWriteRoute())
            val lease = requireNotNull(tasks.tryAcquire(id, next.runGeneration))
            assertTrue(coordinator.admitBusinessPageTaskOwnership(lease, false))
            assertEquals(id, coordinator.currentWriteRoute()?.taskId)
            if (index < ids.lastIndex) {
                assertNull(coordinator.routeIndependentPageTask(ai.mode, ids[index + 1]))
                assertNull(tasks.currentLease())
                assertNull(coordinator.currentWriteRoute())
                assertFalse(tasks.isCurrent(lease))
                assertFalse(coordinator.admitBusinessPageTaskOwnership(lease, true))
                assertNull(coordinator.markPageRuntimeReady(next))
                assertTrue(state.requested)
            }
        }
        assertEquals(listOf("start:${ids[0]}", "save:${ids[0]}", "isolate",
            "start:${ids[1]}", "save:${ids[1]}", "isolate", "start:${ids[2]}"), trace)
        assertEquals(3, reads)
    }

    @Test
    fun failedBusinessAdmissionRetiresReadyTokenWithoutRevivingOldOwner() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ai = FakeRuntime(RunMode.AI_REPLY)
        var reads = 0
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai), RuntimeLogSink { },
            taskCoordinator = tasks, onObserveCurrentPage = { reads++ })
        coordinator.synchronizeRunRequest()
        val source = requireNotNull(coordinator.routePageTask(ai.mode, "AI_REPLY_PRIVATE_MESSAGES"))
        val sourceLease = requireNotNull(tasks.tryAcquire(source.taskId, source.runGeneration))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(sourceLease, false))
        val targetLease = requireNotNull(tasks.transfer(sourceLease, "AI_REPLY_RECEIVED_GREETINGS"))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(targetLease, true))
        val ready = requireNotNull(coordinator.currentWriteRoute())
        assertTrue(tasks.release(targetLease))
        assertTrue(coordinator.rollbackBusinessPageTaskOwnership(targetLease))
        val retry = requireNotNull(coordinator.currentPageRuntimeSession())
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STARTING, retry.lifecycle)
        assertTrue(retry.routeRevision > ready.routeRevision)
        assertNull(coordinator.currentWriteRoute())
        assertNull(tasks.currentLease())
        assertNull(coordinator.markPageRuntimeReady(ready))
        val newLease = requireNotNull(tasks.tryAcquire(retry.taskId, retry.runGeneration))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(newLease, false))
        assertFalse(coordinator.rollbackBusinessPageTaskOwnership(targetLease))
        assertEquals(newLease.taskId, coordinator.currentWriteRoute()?.taskId)
        assertTrue(state.requested)
        assertEquals(2, reads)
    }

    @Test
    fun freshMatchingPageRetriesUnpreparedStartupButNotPreparedRuntimeWaitingForLease() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        var initialStarts = 0
        var retries = 0
        var observations = 0
        var canPrepare = false
        val ai = object : ModeRuntimePort {
            override val mode = RunMode.AI_REPLY
            override fun onRunStarted() = error("page route is required")
            override fun onRunStopped(reason: String) = Unit
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
            override fun onPageRuntimeStarted(session: com.freedom.core.runtime.PageRuntimeSessionState,
                onPrepared: (com.freedom.core.runtime.PageRuntimeSessionState) -> Unit) {
                initialStarts++
                if (canPrepare) onPrepared(session)
            }
            override fun onPageRuntimePreparationRetry(session: com.freedom.core.runtime.PageRuntimeSessionState,
                onPrepared: (com.freedom.core.runtime.PageRuntimeSessionState) -> Unit) {
                retries++
                if (canPrepare) onPrepared(session)
            }
            override fun onPageTaskChanged(session: com.freedom.core.runtime.PageRuntimeSessionState) {
                observations++
            }
        }
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai), RuntimeLogSink { },
            taskCoordinator = tasks, onObserveCurrentPage = {})
        coordinator.synchronizeRunRequest()
        val id = "AI_REPLY_PRIVATE_MESSAGES"
        val first = requireNotNull(coordinator.routePageTask(ai.mode, id))
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STARTING, first.lifecycle)
        assertNull(coordinator.currentWriteRoute())
        coordinator.routePageTask(ai.mode, id)
        assertEquals(1, retries)
        canPrepare = true
        coordinator.routePageTask(ai.mode, id)
        assertEquals(2, retries)
        assertNull(coordinator.currentWriteRoute())
        coordinator.routePageTask(ai.mode, id)
        assertEquals(2, retries)
        assertEquals(1, observations)
        val lease = requireNotNull(tasks.tryAcquire(id, first.runGeneration))
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.READY,
            coordinator.confirmPageTaskOwnership(lease)?.lifecycle)
        assertEquals(first.routeRevision, coordinator.currentWriteRoute()?.routeRevision)
        coordinator.routePageTask(ai.mode, id)
        assertEquals(1, initialStarts)
        assertEquals(2, retries)
        assertEquals(1, observations)
        assertTrue(state.requested)
    }

    @Test
    fun pageDrivenHandoffWaitsForExactTargetLeaseAndDoesNotRestartSameRuntime() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state, mapOf(RunMode.AI_REPLY to ai), RuntimeLogSink { },
            taskCoordinator = tasks, onObserveCurrentPage = {}
        )
        coordinator.synchronizeRunRequest()
        val first = requireNotNull(coordinator.routePageTask(RunMode.AI_REPLY, "PRIVATE"))
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STARTING, first.lifecycle)
        assertNull(coordinator.markPageRuntimeReady(first))
        val lease = requireNotNull(tasks.tryAcquire("PRIVATE", first.runGeneration))
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.READY,
            coordinator.confirmPageTaskOwnership(lease)?.lifecycle)
        val second = requireNotNull(coordinator.routePageTask(RunMode.AI_REPLY, "GREETINGS"))
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STARTING, second.lifecycle)
        assertEquals(first.runGeneration, second.runGeneration)
        assertTrue(second.routeRevision > first.routeRevision)
        assertNull(coordinator.markPageRuntimeReady(first))
        assertNull(coordinator.confirmPageTaskOwnership(lease))
        assertEquals(listOf("start"), ai.calls)
        val newLease = requireNotNull(tasks.transfer(lease, "GREETINGS"))
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.READY,
            coordinator.confirmPageTaskOwnership(newLease)?.lifecycle)
        assertFalse(tasks.isCurrent(lease))
        assertEquals(second.routeRevision,
            coordinator.routePageTask(RunMode.AI_REPLY, "GREETINGS")?.routeRevision)
        coordinator.routePageTask(null)
        assertNull(coordinator.currentPageRuntimeSession())
        assertNull(tasks.currentLease())
        assertTrue(state.requested)
        assertNull(coordinator.markPageRuntimeReady(second))
    }

    @Test
    fun pageDrivenCrossRuntimeStopRevokesBeforeStartingNextRuntime() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val trace = mutableListOf<String>()
        lateinit var coordinator: RunSessionCoordinator
        val ai = object : ModeRuntimePort {
            override val mode = RunMode.AI_REPLY
            override fun onRunStarted() { trace += "ai:start" }
            override fun onRunStopped(reason: String) { error("page exit is not user stop") }
            override fun onPageRuntimeSuspended(reason: String) {
                assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STOPPING,
                    coordinator.currentPageRuntimeSession()?.lifecycle)
                assertNull(tasks.currentLease())
                assertNull(coordinator.activeMode())
                trace += "ai:suspend"
            }
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
        }
        val browse = object : ModeRuntimePort {
            override val mode = RunMode.ACCOUNT_BROWSE
            override fun onRunStarted() {
                assertNull(tasks.currentLease())
                trace += "browse:start"
                tasks.tryAcquire("BROWSE", coordinator.currentRunGeneration())
            }
            override fun onRunStopped(reason: String) = Unit
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
        }
        coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai, browse.mode to browse),
            RuntimeLogSink { }, taskCoordinator = tasks, onObserveCurrentPage = {})
        coordinator.synchronizeRunRequest()
        val first = requireNotNull(coordinator.routePageTask(ai.mode, "PRIVATE"))
        val oldLease = requireNotNull(tasks.tryAcquire("PRIVATE", first.runGeneration))
        coordinator.confirmPageTaskOwnership(oldLease)
        val next = requireNotNull(coordinator.routePageTask(browse.mode, "BROWSE"))
        assertEquals(listOf("ai:start", "ai:suspend", "browse:start"), trace)
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.READY, next.lifecycle)
        assertTrue(next.runGeneration > first.runGeneration)
        assertFalse(tasks.isCurrent(oldLease))
        assertNull(coordinator.markPageRuntimeReady(first))
    }

    @Test
    fun pageDrivenFailedCleanupKeepsStoppingAndDoesNotStartReplacement() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        var failCleanup = true
        var checkpointSaves = 0
        var cleanupAttempts = 0
        var reads = 0
        val ai = object : ModeRuntimePort {
            override val mode = RunMode.AI_REPLY
            override fun onRunStarted() = Unit
            override fun onRunStopped(reason: String) = Unit
            override fun onPageRuntimeWillSuspend(reason: String) { checkpointSaves++ }
            override fun onPageRuntimeSuspended(reason: String) {
                cleanupAttempts++
                if (failCleanup) error("cleanup failed")
            }
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai, browse.mode to browse),
            RuntimeLogSink { }, taskCoordinator = tasks, onObserveCurrentPage = { reads++ })
        coordinator.synchronizeRunRequest()
        val first = requireNotNull(coordinator.routePageTask(ai.mode, "PRIVATE"))
        val lease = requireNotNull(tasks.tryAcquire("PRIVATE", first.runGeneration))
        coordinator.confirmPageTaskOwnership(lease)
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STOPPING,
            coordinator.routePageTask(browse.mode, "BROWSE")?.lifecycle)
        assertNull(coordinator.activeMode())
        assertNull(tasks.currentLease())
        assertTrue(browse.calls.isEmpty())
        assertTrue(state.requested)
        assertNull(coordinator.markPageRuntimeReady(first))
        val exitGeneration = coordinator.currentRunGeneration()
        assertFalse(coordinator.retryPendingPageCleanup())
        assertFalse(coordinator.retryPendingPageCleanup())
        assertEquals(3, cleanupAttempts)
        assertEquals(1, checkpointSaves)
        assertEquals(exitGeneration, coordinator.currentRunGeneration())
        assertNull(coordinator.currentWriteRoute())
        assertTrue(browse.calls.isEmpty())
        failCleanup = false
        assertTrue(coordinator.retryPendingPageCleanup())
        assertEquals(4, cleanupAttempts)
        assertEquals(1, checkpointSaves)
        assertEquals(exitGeneration, coordinator.currentRunGeneration())
        assertNull(coordinator.currentPageRuntimeSession())
        assertTrue(browse.calls.isEmpty())
        assertEquals(3, reads)
        // Only a newly read page may select a replacement after successful cleanup.
        assertEquals("BROWSE", coordinator.routeIndependentPageTask(browse.mode, "BROWSE")?.taskId)
        assertEquals(listOf("start"), browse.calls)
    }

    @Test
    fun unknownEntryRevokesWriteTokenButRetainsRuntimeAndRequiresFreshOwnedPageProof() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai), RuntimeLogSink { },
            taskCoordinator = tasks, onObserveCurrentPage = {})
        coordinator.synchronizeRunRequest()
        val id = "AI_REPLY_PRIVATE_MESSAGES"
        val first = requireNotNull(coordinator.routeIndependentPageTask(ai.mode, id))
        val lease = requireNotNull(tasks.tryAcquire(id, first.runGeneration))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(lease, false))
        val ready = requireNotNull(coordinator.currentWriteRoute())
        coordinator.reconcileUnclassifiedPage(first.runGeneration, false)
        val held = requireNotNull(coordinator.currentPageRuntimeSession())
        assertEquals(com.freedom.core.runtime.PageRuntimeLifecycle.STARTING, held.lifecycle)
        assertTrue(held.routeRevision > ready.routeRevision)
        assertTrue(coordinator.isRuntimeObserving(ai.mode))
        assertTrue(tasks.isCurrent(lease))
        assertNull(coordinator.currentWriteRoute())
        assertNull(coordinator.markPageRuntimeReady(ready))
        assertNull(coordinator.confirmPageTaskOwnership(lease))
        coordinator.routePageTask(ai.mode, id)
        assertNull(coordinator.currentWriteRoute())
        coordinator.reconcileUnclassifiedPage(first.runGeneration - 1L, true)
        assertNull(coordinator.currentWriteRoute())
        coordinator.reconcileUnclassifiedPage(first.runGeneration, true)
        assertEquals(held.routeRevision, coordinator.currentWriteRoute()?.routeRevision)
        assertEquals(listOf("start"), ai.calls)
        assertTrue(state.requested)
        coordinator.reconcileUnclassifiedPage(first.runGeneration, false)
        coordinator.routeIndependentPageTask(ai.mode, id)
        assertTrue(coordinator.isRuntimeReady(ai.mode))
        assertEquals(listOf("start"), ai.calls)
    }

    @Test
    fun independentCrossRuntimeSwitchRequiresNewSnapshotAfterOldGenerationEnds() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        var reads = 0
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai, browse.mode to browse),
            RuntimeLogSink { }, taskCoordinator = tasks, onObserveCurrentPage = { reads++ })
        coordinator.synchronizeRunRequest()
        val first = requireNotNull(coordinator.routeIndependentPageTask(ai.mode, "AI_REPLY_PRIVATE_MESSAGES"))
        val lease = requireNotNull(tasks.tryAcquire(first.taskId, first.runGeneration))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(lease, false))
        assertNull(coordinator.routeIndependentPageTask(browse.mode, "ACCOUNT_BROWSE"))
        assertTrue(browse.calls.isEmpty())
        assertNull(coordinator.currentWriteRoute())
        assertNull(tasks.currentLease())
        assertFalse(tasks.isCurrent(lease))
        assertTrue(coordinator.currentRunGeneration() > first.runGeneration)
        assertEquals(2, reads)
        val next = requireNotNull(coordinator.routeIndependentPageTask(browse.mode, "ACCOUNT_BROWSE"))
        assertEquals(coordinator.currentRunGeneration(), next.runGeneration)
        assertEquals(listOf("start"), browse.calls)
        assertNull(coordinator.currentWriteRoute())
    }

    @Test
    fun productionWriteRouteFollowsVerifiedBusinessHandoffAndBlocksOrdinaryMismatchedClaim() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai), RuntimeLogSink { },
            taskCoordinator = tasks, onObserveCurrentPage = {})
        coordinator.synchronizeRunRequest()
        val first = requireNotNull(coordinator.routePageTask(ai.mode, "AI_REPLY_PRIVATE_MESSAGES"))
        assertNull(coordinator.currentWriteRoute())
        val privateLease = requireNotNull(tasks.tryAcquire(first.taskId, first.runGeneration))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(privateLease, false))
        assertTrue(coordinator.isRuntimeReady(ai.mode))
        val greetingLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        assertFalse(coordinator.admitBusinessPageTaskOwnership(greetingLease, false))
        assertFalse(coordinator.isRuntimeReady(ai.mode))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(greetingLease, true))
        val current = requireNotNull(coordinator.currentWriteRoute())
        assertEquals(greetingLease.taskId, current.taskId)
        assertTrue(current.routeRevision > first.routeRevision)
        assertFalse(coordinator.admitBusinessPageTaskOwnership(privateLease, true))
        assertEquals(listOf("start"), ai.calls)
        coordinator.stop("user")
        assertNull(coordinator.currentWriteRoute())
        assertNull(tasks.currentLease())
    }

    @Test
    fun disconnectedServiceCannotRouteUntilReconnectAndStopClearsSuspendedPlans() {
        val state = FakeState().apply { requested = true }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        var reads = 0
        var cancellations = 0
        var ended = 0
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai), RuntimeLogSink { },
            onObserveCurrentPage = { reads++ }, cancelCurrentPageObservation = { cancellations++ },
            onUserRunEnded = { ended++ }, initiallyServiceConnected = false)
        coordinator.synchronizeRunRequest()
        assertFalse(coordinator.isPageObservationAllowed())
        assertNull(coordinator.routePageTask(ai.mode, "PRIVATE"))
        coordinator.onAccessibilityServiceConnected()
        assertEquals(1, reads)
        coordinator.routePageTask(ai.mode, "PRIVATE")
        coordinator.onAccessibilityServiceDisconnected()
        assertNull(coordinator.currentWriteRoute())
        assertFalse(coordinator.isPageObservationAllowed())
        assertNull(coordinator.routePageTask(ai.mode, "PRIVATE"))
        assertTrue(state.requested)
        assertEquals(0, ended)
        state.requested = false
        coordinator.onExternalRefresh()
        assertEquals(1, ended)
        coordinator.onAccessibilityServiceConnected()
        assertNull(coordinator.currentPageRuntimeSession())
        assertEquals(1, reads)
        assertTrue(cancellations > 0)
        coordinator.onServiceDestroyed()
        assertFalse(coordinator.isPageObservationAllowed())
        assertEquals(listOf("start", "disconnected"), ai.calls)
    }

    @Test
    fun pageDrivenServiceDestroyRevokesAuthorityBeforeExactlyOneActiveRuntimeCleanup() {
        val state = FakeState().apply { requested = true }
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        lateinit var coordinator: RunSessionCoordinator
        var destroyed = 0
        val ai = object : ModeRuntimePort {
            override val mode = RunMode.AI_REPLY
            override fun onRunStarted() = Unit
            override fun onRunStopped(reason: String) = error("not a user stop")
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = error("duplicate disconnect cleanup")
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
            override fun onServiceDestroyed() {
                assertNull(coordinator.currentWriteRoute())
                assertNull(tasks.currentLease())
                destroyed++
            }
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai, browse.mode to browse),
            RuntimeLogSink { }, taskCoordinator = tasks, onObserveCurrentPage = {})
        coordinator.synchronizeRunRequest()
        val session = requireNotNull(coordinator.routePageTask(ai.mode, "AI_REPLY_PRIVATE_MESSAGES"))
        val lease = requireNotNull(tasks.tryAcquire(session.taskId, session.runGeneration))
        assertTrue(coordinator.admitBusinessPageTaskOwnership(lease, false))
        assertTrue(coordinator.isRuntimeReady(ai.mode))
        coordinator.onServiceDestroyed()
        coordinator.onAccessibilityServiceDisconnected()
        coordinator.onServiceDestroyed()
        assertEquals(1, destroyed)
        assertTrue(browse.calls.isEmpty())
        assertTrue(state.requested)
        assertFalse(coordinator.isPageObservationAllowed())
        assertNull(coordinator.routePageTask(ai.mode, session.taskId))
    }

    @Test
    fun synchronizationStartsOnlyTheFreshPageSelectedRuntime() {
        val state = FakeState()
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse, RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        // The command use case owns persisted writes; the coordinator only reconciles them.
        state.requested = true

        assertTrue(coordinator.synchronizeRunRequest())
        assertTrue(browse.calls.isEmpty())
        assertTrue(ai.calls.isEmpty())
        coordinator.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        assertEquals(1L, coordinator.currentRunGeneration())
        assertTrue(state.writes.isEmpty())
        assertEquals(listOf("start"), browse.calls)
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun repeatedSynchronizationDoesNotStartTheSameRuntimeTwice() {
        val state = FakeState().apply {
            requested = true
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse),
            RuntimeLogSink { }
        )

        assertTrue(coordinator.synchronizeRunRequest())
        coordinator.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        assertEquals(1L, coordinator.currentRunGeneration())
        assertFalse(coordinator.synchronizeRunRequest())
        assertEquals(1L, coordinator.currentRunGeneration())
        assertTrue(state.writes.isEmpty())
        assertEquals(listOf("start"), browse.calls)
    }

    @Test
    fun firstRefreshThatCreatesSessionDoesNotAlsoDispatchIdleRefresh() {
        val state = FakeState().apply {
            requested = true
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse),
            RuntimeLogSink { }
        )

        coordinator.onExternalRefresh()
        assertTrue(browse.calls.isEmpty())
        coordinator.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        assertEquals(listOf("start"), browse.calls)
        assertEquals(0, browse.externalRefreshCalls)

        coordinator.onExternalRefresh()
        assertEquals(1, browse.externalRefreshCalls)
    }

    @Test
    fun runAuthorizationWithoutRecognizedPageRemainsObservationOnly() {
        val state = FakeState().apply { requested = true }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val logs = mutableListOf<String>()
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { logs += it }
        )

        assertNull(coordinator.activeMode())
        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(1L, coordinator.currentRunGeneration())
        assertTrue(ai.calls.isEmpty())
        assertNull(coordinator.currentWriteRoute())
        assertNull(coordinator.currentPageRuntimeSession())
        assertFalse(coordinator.synchronizeRunRequest())
    }

    @Test
    fun targetApplicationEventsRemainTheOnlyAiReplyInteractionDispatchPath() {
        val state = FakeState()
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        state.requested = true
        coordinator.synchronizeRunRequest()
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        val click = AccessibilityEventEnvelope(
            type = com.freedom.core.accessibility.events.AccessibilityEventType.VIEW_CLICKED,
            packageName = "com.immomo.momo",
            className = "android.widget.Button",
            contentChanges = emptySet(),
            eventTimeMillis = 123L,
            eventText = emptyList(),
            contentDescription = null,
            source = null
        )

        coordinator.dispatchTargetApplicationEvent(click)

        assertTrue(ai.calls.contains("target"))
        assertTrue(ai.calls.none { it.startsWith("preemptive:") })
    }

    @Test
    fun implicitRunRequestSynchronizationKeepsActiveRuntime() {
        val state = FakeState().apply {
            requested = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )

        coordinator.synchronizeRunRequest()
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")

        assertFalse(coordinator.synchronizeRunRequest())
        assertTrue(state.writes.isEmpty())
        assertEquals(listOf("start"), ai.calls)
    }

    @Test
    fun stopClearsRunIntentAndStopsThePageSelectedRuntime() {
        val state = FakeState().apply {
            requested = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai, RunMode.ACCOUNT_BROWSE to FakeRuntime(RunMode.ACCOUNT_BROWSE)),
            RuntimeLogSink { }
        )
        assertTrue(coordinator.synchronizeRunRequest())
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        coordinator.stop("user")
        assertFalse(state.requested)
        assertEquals(listOf("running:false"), state.writes)
        assertEquals(listOf("start", "stop:user"), ai.calls)
        assertNull(coordinator.currentWriteRoute())
        assertNull(coordinator.currentPageRuntimeSession())
        assertEquals(2L, coordinator.currentRunGeneration())
    }

    @Test
    fun runGenerationAdvancesWhenThePageRuntimeChangesAndWhenTheRunStops() {
        val state = FakeState().apply {
            requested = true
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse, RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )

        assertTrue(coordinator.synchronizeRunRequest())
        coordinator.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        assertEquals(1L, coordinator.currentRunGeneration())

        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(1, browse.calls.count { it.startsWith("stop:") })
        assertEquals(listOf("start"), ai.calls)

        coordinator.stop("test_stop")
        assertEquals(3L, coordinator.currentRunGeneration())
    }

    @Test
    fun sharedTaskLifecycleRevokesLeasesAcrossPageSwitchReconnectAndUserStop() {
        val state = FakeState().apply {
            requested = true
        }
        val shared = TaskCoordinator(UiOperationOwnershipCoordinator())
        val session = RunSessionCoordinator(
            state = state,
            runtimes = mapOf(
                RunMode.AI_REPLY to FakeRuntime(RunMode.AI_REPLY),
                RunMode.ACCOUNT_BROWSE to FakeRuntime(RunMode.ACCOUNT_BROWSE)
            ),
            log = RuntimeLogSink { },
            taskCoordinator = shared
        )
        val ai = TaskExecutionController(shared, session::currentRunGeneration)
        val browse = TaskExecutionController(shared, session::currentRunGeneration)

        assertTrue(session.synchronizeRunRequest())
        session.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        ai.beginRun(session.currentRunGeneration())
        assertTrue(ai.claim("AI_REPLY_PRIVATE_MESSAGES", "confirmed inbox"))
        val old = requireNotNull(shared.currentLease())
        assertTrue(ai.claim("AI_REPLY_RECEIVED_GREETINGS", "confirmed greeting page"))
        assertFalse(ai.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertTrue(ai.owns("AI_REPLY_RECEIVED_GREETINGS"))
        assertFalse(shared.release(old))

        session.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        assertNull(shared.activeTaskId())
        assertFalse(ai.owns("AI_REPLY_RECEIVED_GREETINGS"))
        assertFalse(ai.release("AI_REPLY_RECEIVED_GREETINGS", "late AI stop"))
        assertFalse(ai.claim("AI_REPLY_PRIVATE_MESSAGES", "late AI callback"))
        browse.beginRun(session.currentRunGeneration())
        assertTrue(browse.claim("ACCOUNT_BROWSE", "verified browser run"))
        assertFalse(ai.claim("AI_REPLY_PRIVATE_MESSAGES", "other mode is active"))

        val beforeDisconnect = requireNotNull(shared.currentLease())
        session.onAccessibilityServiceDisconnected()
        assertNull(shared.activeTaskId())
        assertFalse(shared.isCurrent(beforeDisconnect))
        assertFalse(browse.owns("ACCOUNT_BROWSE"))
        assertFalse(browse.claim("ACCOUNT_BROWSE", "late queued event"))

        session.onAccessibilityServiceConnected()
        session.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        browse.beginRun(session.currentRunGeneration())
        assertTrue(browse.claim("ACCOUNT_BROWSE", "reconnected validated run"))
        assertTrue(browse.owns("ACCOUNT_BROWSE"))

        session.stop("user stop")
        assertNull(shared.activeTaskId())
        assertFalse(browse.owns("ACCOUNT_BROWSE"))
        assertFalse(browse.claim("ACCOUNT_BROWSE", "post-stop callback"))
    }

    @Test
    fun resetInterruptionAndDestructionRevokeTaskSlotImmediately() {
        val state = FakeState().apply {
            requested = true
        }
        val shared = TaskCoordinator(UiOperationOwnershipCoordinator())
        val session = RunSessionCoordinator(
            state = state,
            runtimes = mapOf(RunMode.AI_REPLY to FakeRuntime(RunMode.AI_REPLY)),
            log = RuntimeLogSink { },
            taskCoordinator = shared
        )
        val owner = TaskExecutionController(shared, session::currentRunGeneration)
        session.synchronizeRunRequest()
        session.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        owner.beginRun(session.currentRunGeneration())
        assertTrue(owner.claim("AI_REPLY_PRIVATE_MESSAGES", "start"))

        session.onTargetPackageChanged("activity invalidated")
        assertNull(shared.activeTaskId())
        assertFalse(owner.owns("AI_REPLY_PRIVATE_MESSAGES"))

        session.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        owner.beginRun(session.currentRunGeneration())
        assertTrue(owner.claim("AI_REPLY_PRIVATE_MESSAGES", "revalidated"))
        session.onAccessibilityServiceInterrupted("connection lost")
        assertNull(shared.activeTaskId())
        assertFalse(owner.owns("AI_REPLY_PRIVATE_MESSAGES"))

        session.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        owner.beginRun(session.currentRunGeneration())
        assertTrue(owner.claim("AI_REPLY_PRIVATE_MESSAGES", "revalidated again"))
        session.onServiceDestroyed()
        assertNull(shared.activeTaskId())
        assertFalse(owner.owns("AI_REPLY_PRIVATE_MESSAGES"))
    }

    @Test
    fun manualObservationRunAlsoReceivesItsOwnGeneration() {
        val state = FakeState().apply { requested = true }
        val coordinator = RunSessionCoordinator(state, emptyMap(), RuntimeLogSink { })

        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(1L, coordinator.currentRunGeneration())
        assertFalse(coordinator.synchronizeRunRequest())

        coordinator.stop("manual_stop")
        assertEquals(2L, coordinator.currentRunGeneration())
        assertFalse(state.requested)
    }


    @Test
    fun reconnectReusesUserRunIntentButRequiresNewPageSelection() {
        val state = FakeState().apply {
            requested = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(state, mapOf(RunMode.AI_REPLY to ai), RuntimeLogSink { })

        coordinator.onAccessibilityServiceConnected()
        assertTrue(ai.calls.isEmpty())
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        assertEquals(listOf("start"), ai.calls)
        assertEquals(1L, coordinator.currentRunGeneration())

        coordinator.onAccessibilityServiceDisconnected()
        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(listOf("start", "disconnected"), ai.calls)
        assertTrue(state.requested)

        coordinator.onAccessibilityServiceConnected()
        assertEquals(listOf("start", "disconnected"), ai.calls)
        assertNull(coordinator.activeMode())
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        assertEquals(listOf("start", "disconnected", "start"), ai.calls)
        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(2, ai.calls.count { it == "start" })
        assertTrue(state.writes.isEmpty())
    }

    @Test
    fun userStopBeforeServiceReconnectCannotRestartOldRuntime() {
        val state = FakeState().apply {
            requested = true
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        var ended = 0
        val coordinator = RunSessionCoordinator(state, mapOf(RunMode.ACCOUNT_BROWSE to browse), RuntimeLogSink { },
            onUserRunEnded = { ended++ })

        coordinator.onAccessibilityServiceConnected()
        coordinator.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        coordinator.onAccessibilityServiceDisconnected()
        coordinator.stop("user_stopped")
        coordinator.onAccessibilityServiceConnected()

        assertEquals(1, browse.calls.count { it == "start" })
        assertEquals(0, browse.calls.count { it == "connected" })
        assertEquals(1, browse.calls.count { it == "disconnected" })
        assertTrue(ended >= 1)
        assertNull(coordinator.currentWriteRoute())
        assertFalse(state.requested)
        assertNull(coordinator.activeMode())
    }


    @Test
    fun disconnectedRunRevokesOldTaskExecutionAndReconnectCanClaimNewGeneration() {
        val state = FakeState().apply {
            requested = true
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val session = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse),
            RuntimeLogSink { }
        )
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val execution = TaskExecutionController(coordinator, session::currentRunGeneration)

        session.onAccessibilityServiceConnected()
        session.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        execution.beginRun(session.currentRunGeneration())
        assertTrue(execution.claim("ACCOUNT_BROWSE", "connected"))
        assertTrue(execution.owns("ACCOUNT_BROWSE"))

        session.onAccessibilityServiceDisconnected()
        assertFalse(execution.owns("ACCOUNT_BROWSE"))
        assertFalse(execution.claim("ACCOUNT_BROWSE", "disconnected old callback"))

        session.onAccessibilityServiceConnected()
        session.routePageTask(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE")
        execution.beginRun(session.currentRunGeneration())
        assertTrue(execution.claim("ACCOUNT_BROWSE", "reconnected"))
        assertTrue(execution.owns("ACCOUNT_BROWSE"))
        assertEquals(session.currentRunGeneration(), coordinator.currentLease()?.runGeneration)
    }

    @Test
    fun recreatedServiceUsesProcessGenerationAndCanClaimReleasedAiTaskSlot() {
        val state = FakeState().apply {
            requested = true
        }
        // The Application root and its UI lease coordinators survive a Service recreation.
        val processClock = RunGenerationClock()
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val originalRuntime = FakeRuntime(RunMode.AI_REPLY)
        val originalSession = RunSessionCoordinator(
            state, mapOf(RunMode.AI_REPLY to originalRuntime),
            RuntimeLogSink { }, processClock
        )
        val oldExecution = TaskExecutionController(taskCoordinator, originalSession::currentRunGeneration)

        originalSession.onAccessibilityServiceConnected()
        originalSession.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        oldExecution.beginRun(originalSession.currentRunGeneration())
        assertTrue(oldExecution.claim("AI_REPLY_PRIVATE_MESSAGES", "original service"))
        val oldLease = requireNotNull(taskCoordinator.currentLease())
        val oldGeneration = originalSession.currentRunGeneration()

        originalSession.onAccessibilityServiceDisconnected()
        assertFalse(oldExecution.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertFalse(oldExecution.claim("AI_REPLY_PRIVATE_MESSAGES", "old disconnected callback"))
        oldExecution.releaseCurrent("service cleanup")
        originalSession.onServiceDestroyed()
        assertNull(taskCoordinator.activeTaskId())

        // A fresh Service owns a fresh RunSessionCoordinator but not a fresh TaskCoordinator.
        val recreatedRuntime = FakeRuntime(RunMode.AI_REPLY)
        val recreatedSession = RunSessionCoordinator(
            state, mapOf(RunMode.AI_REPLY to recreatedRuntime),
            RuntimeLogSink { }, processClock
        )
        recreatedSession.onAccessibilityServiceConnected()
        assertNull(recreatedSession.activeMode())
        recreatedSession.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        val newGeneration = recreatedSession.currentRunGeneration()
        assertTrue(newGeneration > oldGeneration)

        val newExecution = TaskExecutionController(taskCoordinator, recreatedSession::currentRunGeneration)
        newExecution.beginRun(newGeneration)
        assertTrue(newExecution.claim("AI_REPLY_PRIVATE_MESSAGES", "service reconnect"))
        assertTrue(newExecution.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals(newGeneration, taskCoordinator.currentLease()?.runGeneration)

        // Stale capabilities still cannot alter the new run, even when the old owner is the same.
        assertFalse(taskCoordinator.isCurrent(oldLease))
        assertFalse(taskCoordinator.release(oldLease))
        assertFalse(oldExecution.claim("AI_REPLY_PRIVATE_MESSAGES", "late callback after recreation"))
        assertNull(taskCoordinator.tryAcquire("AI_REPLY_RECEIVED_GREETINGS", oldGeneration))
        assertTrue(newExecution.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals(listOf("start"), recreatedRuntime.calls)
    }

    @Test
    fun repeatedServiceRecreationsKeepUiLeaseEpochMonotonicAcrossThreeConnections() {
        val state = FakeState().apply {
            requested = true
        }
        val processClock = RunGenerationClock()
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        var previousRunGeneration = -1L

        repeat(3) { reconnect ->
            val runtime = FakeRuntime(RunMode.AI_REPLY)
            val serviceSession = RunSessionCoordinator(
                state, mapOf(RunMode.AI_REPLY to runtime), RuntimeLogSink { }, processClock
            )
            serviceSession.onAccessibilityServiceConnected()
            assertNull(serviceSession.activeMode())
            serviceSession.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")

            val generation = serviceSession.currentRunGeneration()
            assertTrue("Service $reconnect must advance the process clock", generation > previousRunGeneration)
            val taskExecution = TaskExecutionController(taskCoordinator, serviceSession::currentRunGeneration)
            taskExecution.beginRun(generation)
            assertTrue(taskExecution.claim("AI_REPLY_PRIVATE_MESSAGES", "service reconnect $reconnect"))
            assertTrue(taskExecution.owns("AI_REPLY_PRIVATE_MESSAGES"))
            val ownedLease = requireNotNull(taskCoordinator.currentLease())
            if (previousRunGeneration >= 0L) {
                assertNull(taskCoordinator.tryAcquire("AI_REPLY_RECEIVED_GREETINGS", previousRunGeneration))
            }

            serviceSession.onAccessibilityServiceDisconnected()
            assertFalse(taskExecution.owns("AI_REPLY_PRIVATE_MESSAGES"))
            assertFalse(taskExecution.claim("AI_REPLY_PRIVATE_MESSAGES", "disconnected callback"))
            taskExecution.releaseCurrent("service disconnected")
            serviceSession.onServiceDestroyed()

            assertFalse(taskCoordinator.isCurrent(ownedLease))
            assertFalse(taskCoordinator.release(ownedLease))
            assertNull(taskCoordinator.activeTaskId())
            assertEquals(listOf("start", "disconnected"), runtime.calls)
            previousRunGeneration = serviceSession.currentRunGeneration()
        }

        assertTrue("Reconnect must preserve persisted run intent", state.requested)
        assertTrue(state.writes.isEmpty())
    }

    @Test
    fun serviceDisconnectInvalidatesTheGenerationBeforeRuntimeCleanup() {
        val state = FakeState().apply {
            requested = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        coordinator.synchronizeRunRequest()
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")
        assertEquals(1L, coordinator.currentRunGeneration())

        coordinator.onAccessibilityServiceDisconnected()

        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(listOf("start", "disconnected"), ai.calls)
    }

    @Test
    fun externalRuntimeResetAlsoInvalidatesTheGeneration() {
        val state = FakeState().apply {
            requested = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        coordinator.synchronizeRunRequest()
        coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES")

        coordinator.onTargetPackageChanged("target changed")

        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(listOf("start", "stop:target changed"), ai.calls)
        assertNull(coordinator.currentPageRuntimeSession())
    }
}
