package com.freedom.application.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionCoordinatorFailureDiagnosticsTest {
    @Test
    fun failedPageStartCleansRuntimeRecordsFailureAndKeepsUserRunIntent() {
        val state = FakeState()
        val log = FakeLog()
        val runtime = FakeRuntime(failOnStart = true)
        val coordinator = RunSessionCoordinator(
            state = state,
            runtimes = mapOf(RunMode.AI_REPLY to runtime),
            log = log
        )

        state.runRequestedState = true
        assertTrue(coordinator.synchronizeRunRequest())
        assertEquals(null, coordinator.routePageTask(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES"))

        assertTrue(state.runRequestedState)
        assertTrue(runtime.stopCalled)
        assertEquals(null, coordinator.currentPageRuntimeSession())
        assertEquals(null, coordinator.currentWriteRoute())
        assertEquals(null, log.finishedResult)
        assertTrue(log.exceptionEvents.contains("page_runtime_start_failed"))
    }

    private class FakeState : RunStateRepository {
        var runRequestedState = false
        override fun isRunRequested() = runRequestedState
        override fun setRunRequested(requested: Boolean) { runRequestedState = requested }
    }

    private class FakeLog : RuntimeLogSink {
        val exceptionEvents = mutableListOf<String>()
        var finishedResult: String? = null
        override fun append(message: String) = Unit
        override fun recordException(component: String, eventType: String, message: String, error: Throwable) {
            exceptionEvents += eventType
        }
        override fun finishRun(result: String, reason: String) { finishedResult = result }
    }

    private class FakeRuntime(private val failOnStart: Boolean) : ModeRuntimePort {
        override val mode = RunMode.AI_REPLY
        var stopCalled = false
        override fun onRunStarted() {
            if (failOnStart) throw IllegalStateException("test failure")
        }
        override fun onRunStopped(reason: String) { stopCalled = true }
        override fun onAccessibilityServiceConnected() = Unit
        override fun onAccessibilityServiceDisconnected() = Unit
        override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
        override fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) = Unit
    }
}
