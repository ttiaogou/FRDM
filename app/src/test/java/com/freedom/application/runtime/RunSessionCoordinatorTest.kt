package com.freedom.application.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
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
        var manualReviewRequired = false
        override fun isRunRequested() = requested
        override fun isInputManualReviewRequired() = manualReviewRequired
        override fun setInputManualReviewRequired(required: Boolean) {
            manualReviewRequired = required
            writes += "manual-review:$required"
        }
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
        override fun onPreemptiveTargetInteraction(event: AccessibilityEventEnvelope) {
            calls += "preemptive:${event.type}"
        }
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
    fun aiReplyRunForwardsGlobalTouchAndPreemptiveTargetInteractionWithTraceLogs() {
        val state = FakeState()
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val logs = mutableListOf<String>()
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { logs += it }
        )
        state.requested = true
        state.mode = RunMode.AI_REPLY
        coordinator.synchronizeRunRequest()
        val touch = AccessibilityEventEnvelope(
            type = com.freedom.core.accessibility.events.AccessibilityEventType.TOUCH_INTERACTION_START,
            packageName = "",
            className = "",
            contentChanges = emptySet(),
            eventTimeMillis = 123L,
            eventText = emptyList(),
            contentDescription = null,
            source = null
        )
        val click = touch.copy(
            type = com.freedom.core.accessibility.events.AccessibilityEventType.VIEW_CLICKED,
            packageName = "com.immomo.momo"
        )

        coordinator.dispatchGlobalTouchEvent(touch)
        coordinator.dispatchPreemptiveTargetInteraction(click)

        assertTrue(ai.calls.contains("global:TOUCH_INTERACTION_START"))
        assertTrue(ai.calls.contains("preemptive:VIEW_CLICKED"))
        assertTrue(logs.any { it.contains("全局触摸链路[RUN_SESSION_FORWARDED]") })
        assertTrue(logs.any { it.contains("人工交互抢占链路[RUN_SESSION_FORWARDED]") })
    }

    @Test
    fun implicitRunRequestSynchronizationDoesNotClearInputReviewHold() {
        val state = FakeState().apply {
            requested = true
            mode = RunMode.AI_REPLY
            manualReviewRequired = true
        }
        val ai = FakeRuntime(RunMode.AI_REPLY)
        val coordinator = RunSessionCoordinator(
            state,
            mapOf(RunMode.AI_REPLY to ai),
            RuntimeLogSink { }
        )

        coordinator.synchronizeRunRequest()

        assertTrue(state.manualReviewRequired)
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
}
