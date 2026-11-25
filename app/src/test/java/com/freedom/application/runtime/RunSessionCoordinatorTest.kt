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
        var mode: RunMode? = null
        val writes = mutableListOf<String>()
        override fun isRunRequested() = requested
        override fun activeMode() = mode
        override fun setActiveMode(mode: RunMode?) {
            this.mode = mode
            writes += "mode:${mode?.storageValue ?: "null"}"
        }
        override fun setRunRequested(requested: Boolean) {
            this.requested = requested
            writes += "running:$requested"
            if (!requested) mode = null
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
    fun pageDrivenStartAndReconnectIgnorePersistedModeUntilFreshRoute() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
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
        coordinator.markPageRuntimeReady(coordinator.currentRunGeneration())
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
        assertNull(coordinator.markPageRuntimeReady(first.runGeneration))
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
        val ai = object : ModeRuntimePort {
            override val mode = RunMode.AI_REPLY
            override fun onRunStarted() = Unit
            override fun onRunStopped(reason: String) = Unit
            override fun onPageRuntimeSuspended(reason: String) {
                if (failCleanup) error("cleanup failed")
            }
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(state, mapOf(ai.mode to ai, browse.mode to browse),
            RuntimeLogSink { }, taskCoordinator = tasks, onObserveCurrentPage = {})
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
        failCleanup = false
        assertEquals("BROWSE", coordinator.routePageTask(browse.mode, "BROWSE")?.taskId)
        assertEquals(listOf("start"), browse.calls)
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
    fun synchronizationStartsOnlyThePersistedActiveRuntime() {
        val state = FakeState()
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse, RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        // The command use case owns persisted writes; the coordinator only reconciles them.
        state.mode = RunMode.ACCOUNT_BROWSE
        state.requested = true

        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(1L, coordinator.currentRunGeneration())
        assertTrue(state.writes.isEmpty())
        assertEquals(listOf("start"), browse.calls)
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun repeatedSynchronizationDoesNotStartTheSameRuntimeTwice() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse),
            RuntimeLogSink { }
        )

        assertTrue(coordinator.synchronizeRunRequest())
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
            mode = RunMode.ACCOUNT_BROWSE
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse),
            RuntimeLogSink { }
        )

        coordinator.onExternalRefresh()
        assertEquals(listOf("start"), browse.calls)
        assertEquals(0, browse.externalRefreshCalls)

        coordinator.onExternalRefresh()
        assertEquals(1, browse.externalRefreshCalls)
    }

    @Test
    fun nullPersistedModeRemainsManualObservationEvenWhenUiPreferenceHasModeSelected() {
        val state = FakeState().apply { requested = true }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val logs = mutableListOf<String>()
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { logs += it }
        )

        assertNull(coordinator.resolveActiveModeForRestore())
        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(1L, coordinator.currentRunGeneration())
        assertNull(state.mode)
        assertTrue(ai.calls.isEmpty())
        assertTrue(logs.any { it.contains("当前请求模式=null") })
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
        state.mode = RunMode.AI_REPLY
        coordinator.synchronizeRunRequest()
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
            mode = RunMode.AI_REPLY
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )

        coordinator.synchronizeRunRequest()

        assertTrue(state.writes.isEmpty())
        assertEquals(listOf("start"), ai.calls)
    }

    @Test
    fun stopClearsRunIntentAndStopsTheLatchedRuntime() {
        val state = FakeState().apply {
            mode = RunMode.AI_REPLY
            requested = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai, RunMode.ACCOUNT_BROWSE to FakeRuntime(RunMode.ACCOUNT_BROWSE)),
            RuntimeLogSink { }
        )
        coordinator.stop("user")
        assertFalse(state.requested)
        assertNull(state.mode)
        assertEquals(listOf("running:false"), state.writes)
        assertEquals(listOf("stop:user"), ai.calls)
        assertEquals(1L, coordinator.currentRunGeneration())
    }

    @Test
    fun runGenerationAdvancesWhenTheActiveModeChangesAndWhenTheRunStops() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.ACCOUNT_BROWSE to browse, RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )

        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(1L, coordinator.currentRunGeneration())

        state.mode = RunMode.AI_REPLY
        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(1, browse.calls.count { it.startsWith("stop:") })
        assertEquals(listOf("start"), ai.calls)

        coordinator.stop("test_stop")
        assertEquals(3L, coordinator.currentRunGeneration())
    }

    @Test
    fun sharedTaskLifecycleRevokesLeasesAcrossModeSwitchReconnectAndUserStop() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
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
        ai.beginRun(session.currentRunGeneration())
        assertTrue(ai.claim("AI_REPLY_PRIVATE_MESSAGES", "confirmed inbox"))
        val old = requireNotNull(shared.currentLease())
        assertTrue(ai.claim("AI_REPLY_RECEIVED_GREETINGS", "confirmed greeting page"))
        assertFalse(ai.owns("AI_REPLY_PRIVATE_MESSAGES"))
        assertTrue(ai.owns("AI_REPLY_RECEIVED_GREETINGS"))
        assertFalse(shared.release(old))

        state.mode = RunMode.ACCOUNT_BROWSE
        assertTrue(session.synchronizeRunRequest())
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
            mode = RunMode.AI_REPLY
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
        owner.beginRun(session.currentRunGeneration())
        assertTrue(owner.claim("AI_REPLY_PRIVATE_MESSAGES", "start"))

        session.resetRuntime(RunMode.AI_REPLY, "activity invalidated")
        assertNull(shared.activeTaskId())
        assertFalse(owner.owns("AI_REPLY_PRIVATE_MESSAGES"))

        owner.beginRun(session.currentRunGeneration())
        assertTrue(owner.claim("AI_REPLY_PRIVATE_MESSAGES", "revalidated"))
        session.onAccessibilityServiceInterrupted("connection lost")
        assertNull(shared.activeTaskId())
        assertFalse(owner.owns("AI_REPLY_PRIVATE_MESSAGES"))

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
    fun reconnectReusesPersistedRunIntentButRebindsOnlyServiceLifecycle() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(state, mapOf(RunMode.AI_REPLY to ai), RuntimeLogSink { })

        coordinator.onAccessibilityServiceConnected()
        assertEquals(listOf("start", "connected"), ai.calls)
        assertEquals(1L, coordinator.currentRunGeneration())

        coordinator.onAccessibilityServiceDisconnected()
        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(listOf("start", "connected", "disconnected"), ai.calls)
        assertTrue(state.requested)

        coordinator.onAccessibilityServiceConnected()
        assertEquals(listOf("start", "connected", "disconnected", "connected"), ai.calls)
        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(1, ai.calls.count { it == "start" })
        assertTrue(state.writes.isEmpty())
    }

    @Test
    fun userStopBeforeServiceReconnectCannotRestartOldRuntime() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
        }
        val browse = FakeRuntime(RunMode.ACCOUNT_BROWSE)
        val coordinator = RunSessionCoordinator(state, mapOf(RunMode.ACCOUNT_BROWSE to browse), RuntimeLogSink { })

        coordinator.onAccessibilityServiceConnected()
        coordinator.onAccessibilityServiceDisconnected()
        coordinator.stop("user_stopped")
        coordinator.onAccessibilityServiceConnected()

        assertEquals(1, browse.calls.count { it == "start" })
        assertEquals(1, browse.calls.count { it == "connected" })
        assertEquals(1, browse.calls.count { it == "disconnected" })
        assertTrue(browse.calls.any { it == "stop:user_stopped" })
        assertFalse(state.requested)
        assertNull(coordinator.resolveActiveModeForRestore())
    }


    @Test
    fun disconnectedRunRevokesOldTaskExecutionAndReconnectCanClaimNewGeneration() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.ACCOUNT_BROWSE
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
        execution.beginRun(session.currentRunGeneration())
        assertTrue(execution.claim("ACCOUNT_BROWSE", "connected"))
        assertTrue(execution.owns("ACCOUNT_BROWSE"))

        session.onAccessibilityServiceDisconnected()
        assertFalse(execution.owns("ACCOUNT_BROWSE"))
        assertFalse(execution.claim("ACCOUNT_BROWSE", "disconnected old callback"))

        session.onAccessibilityServiceConnected()
        execution.beginRun(session.currentRunGeneration())
        assertTrue(execution.claim("ACCOUNT_BROWSE", "reconnected"))
        assertTrue(execution.owns("ACCOUNT_BROWSE"))
        assertEquals(session.currentRunGeneration(), coordinator.currentLease()?.runGeneration)
    }

    @Test
    fun recreatedServiceUsesProcessGenerationAndCanClaimReleasedAiTaskSlot() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
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
        recreatedSession.resetRuntime(RunMode.ACCOUNT_BROWSE, "AI mode restore")
        recreatedSession.onAccessibilityServiceConnected()
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
        assertEquals(listOf("start", "connected"), recreatedRuntime.calls)
    }

    @Test
    fun repeatedServiceRecreationsKeepUiLeaseEpochMonotonicAcrossThreeConnections() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
        }
        val processClock = RunGenerationClock()
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        var previousRunGeneration = -1L

        repeat(3) { reconnect ->
            val runtime = FakeRuntime(RunMode.AI_REPLY)
            val serviceSession = RunSessionCoordinator(
                state, mapOf(RunMode.AI_REPLY to runtime), RuntimeLogSink { }, processClock
            )
            serviceSession.resetRuntime(RunMode.ACCOUNT_BROWSE, "AI mode reconnect")
            serviceSession.onAccessibilityServiceConnected()

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
            assertEquals(listOf("start", "connected", "disconnected"), runtime.calls)
            previousRunGeneration = serviceSession.currentRunGeneration()
        }

        assertTrue("Reconnect must preserve persisted run intent", state.requested)
        assertTrue(state.writes.isEmpty())
    }

    @Test
    fun serviceDisconnectInvalidatesTheGenerationBeforeRuntimeCleanup() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        coordinator.synchronizeRunRequest()
        assertEquals(1L, coordinator.currentRunGeneration())

        coordinator.onAccessibilityServiceDisconnected()

        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(listOf("start", "disconnected"), ai.calls)
    }

    @Test
    fun externalRuntimeResetAlsoInvalidatesTheGeneration() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )
        coordinator.synchronizeRunRequest()

        coordinator.resetRuntime(RunMode.AI_REPLY, "target changed")

        assertEquals(2L, coordinator.currentRunGeneration())
        assertEquals(listOf("start"), ai.calls)
    }
}
