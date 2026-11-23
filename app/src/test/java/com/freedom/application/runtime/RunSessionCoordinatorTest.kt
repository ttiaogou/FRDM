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
