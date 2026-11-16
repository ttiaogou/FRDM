package com.freedom.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLifecycleStateMachineTest {
    @Test
    fun recordsTheCompleteSuccessfulOcrLifecycleInOrder() {
        var now = 100L
        val transitions = mutableListOf<OcrLifecycleTransition>()
        val machine = OcrLifecycleStateMachine({ now }) { transitions += it }

        assertTrue(machine.transition(OcrLifecycleState.DISCOVERING, "request-1", reason = "discover"))
        now += 10
        assertTrue(machine.transition(OcrLifecycleState.BINDING, "request-1", reason = "bind"))
        now += 10
        assertTrue(machine.transition(OcrLifecycleState.SERVICE_READY, "request-1", packageName = "com.freedom.ocr.plugin", reason = "ready"))
        now += 10
        assertTrue(machine.transition(OcrLifecycleState.OCR_RUNNING, "request-1", packageName = "com.freedom.ocr.plugin", reason = "detect"))
        now += 10
        assertTrue(machine.transition(OcrLifecycleState.OCR_SUCCESS, "request-1", packageName = "com.freedom.ocr.plugin", reason = "result"))

        assertEquals(
            listOf(
                OcrLifecycleState.DISCOVERING,
                OcrLifecycleState.BINDING,
                OcrLifecycleState.SERVICE_READY,
                OcrLifecycleState.OCR_RUNNING,
                OcrLifecycleState.OCR_SUCCESS,
            ),
            transitions.map { it.current },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), transitions.map { it.sequence })
        assertEquals(listOf(100L, 110L, 120L, 130L, 140L), transitions.map { it.atElapsedRealtimeMs })
        assertEquals("com.freedom.ocr.plugin", transitions.last().packageName)
        assertEquals(OcrLifecycleState.OCR_SUCCESS, machine.current())
    }

    @Test
    fun emptyRecognitionIsDistinctFromPluginOrCallFailure() {
        val machine = OcrLifecycleStateMachine()
        machine.transition(OcrLifecycleState.DISCOVERING, "request-2", reason = "discover")
        machine.transition(OcrLifecycleState.BINDING, "request-2", reason = "bind")
        machine.transition(OcrLifecycleState.SERVICE_READY, "request-2", reason = "ready")
        machine.transition(OcrLifecycleState.OCR_RUNNING, "request-2", reason = "detect")
        assertTrue(machine.transition(OcrLifecycleState.OCR_EMPTY_RESULT, "request-2", reason = "no text lines"))
        assertEquals(OcrLifecycleState.OCR_EMPTY_RESULT, machine.current())
    }

    @Test
    fun transientBindFailureCanRecoverThroughRediscovery() {
        val machine = OcrLifecycleStateMachine()
        machine.transition(OcrLifecycleState.DISCOVERING, "request-3", reason = "discover")
        machine.transition(OcrLifecycleState.BINDING, "request-3", reason = "bind")
        assertTrue(machine.transition(OcrLifecycleState.BIND_FAILED, "request-3", reason = "bindService false"))
        assertTrue(machine.transition(OcrLifecycleState.DISCOVERING, "request-3", attempt = 2, reason = "retry"))
        assertTrue(machine.transition(OcrLifecycleState.BINDING, "request-3", attempt = 2, reason = "bind"))
        assertTrue(machine.transition(OcrLifecycleState.SERVICE_READY, "request-3", attempt = 2, reason = "ready"))
    }

    @Test
    fun runningRequestCanEndInAnExplicitTimeoutState() {
        val machine = OcrLifecycleStateMachine()
        machine.transition(OcrLifecycleState.DISCOVERING, "request-timeout", reason = "discover")
        machine.transition(OcrLifecycleState.BINDING, "request-timeout", reason = "bind")
        machine.transition(OcrLifecycleState.SERVICE_READY, "request-timeout", reason = "ready")
        machine.transition(OcrLifecycleState.OCR_RUNNING, "request-timeout", reason = "detect")
        assertTrue(machine.transition(OcrLifecycleState.OCR_TIMEOUT, "request-timeout", reason = "batch timeout"))
        assertEquals(OcrLifecycleState.OCR_TIMEOUT, machine.current())
        assertFalse(machine.transition(OcrLifecycleState.OCR_SUCCESS, "request-timeout", reason = "late result after timeout"))
    }

    @Test
    fun timeoutCanEndARequestThatIsStillBinding() {
        val machine = OcrLifecycleStateMachine()
        machine.transition(OcrLifecycleState.DISCOVERING, "request-bind-timeout", reason = "discover")
        machine.transition(OcrLifecycleState.BINDING, "request-bind-timeout", reason = "bind")
        assertTrue(machine.transition(OcrLifecycleState.OCR_TIMEOUT, "request-bind-timeout", reason = "batch timeout"))
        assertFalse(machine.transition(OcrLifecycleState.SERVICE_READY, "request-bind-timeout", reason = "late bind"))
        assertEquals(OcrLifecycleState.OCR_TIMEOUT, machine.current())
    }

    @Test
    fun imageTransferFailureHasItsOwnTerminalState() {
        val machine = OcrLifecycleStateMachine()
        machine.transition(OcrLifecycleState.DISCOVERING, "request-image", reason = "discover")
        machine.transition(OcrLifecycleState.BINDING, "request-image", reason = "bind")
        machine.transition(OcrLifecycleState.SERVICE_READY, "request-image", reason = "ready")
        machine.transition(OcrLifecycleState.OCR_RUNNING, "request-image", reason = "detect")
        assertTrue(machine.transition(OcrLifecycleState.OCR_IMAGE_TRANSFER_FAILED, "request-image", reason = "pipe failed"))
        assertEquals(OcrLifecycleState.OCR_IMAGE_TRANSFER_FAILED, machine.current())
        assertFalse(OcrPluginFailureKind.IMAGE_TRANSFER_FAILED.isRetryable())
    }

    @Test
    fun rejectsUnsafeTransitionAndDoesNotCorruptCurrentState() {
        val machine = OcrLifecycleStateMachine()
        assertFalse(machine.transition(OcrLifecycleState.OCR_SUCCESS, "request-4", reason = "invalid without OCR"))
        assertEquals(OcrLifecycleState.IDLE, machine.current())
    }

    @Test
    fun retainedServiceCanServeMultipleSequentialOcrRequests() {
        val machine = OcrLifecycleStateMachine()
        machine.transition(OcrLifecycleState.DISCOVERING, "preflight", reason = "discover")
        machine.transition(OcrLifecycleState.BINDING, "preflight", reason = "bind")
        machine.transition(OcrLifecycleState.SERVICE_READY, "preflight", reason = "verified")
        machine.transition(OcrLifecycleState.OCR_RUNNING, "ocr-1", reason = "first detect")
        assertTrue(machine.transition(OcrLifecycleState.OCR_SUCCESS, "ocr-1", reason = "first result"))
        assertTrue(machine.transition(OcrLifecycleState.SERVICE_READY, "ocr-2", reason = "reuse retained binder"))
        assertTrue(machine.transition(OcrLifecycleState.OCR_RUNNING, "ocr-2", reason = "second detect"))
        assertTrue(machine.transition(OcrLifecycleState.OCR_SUCCESS, "ocr-2", reason = "second result"))
    }

    @Test
    fun imagePipelineFailureIsNotRetryableAsAPluginBindFailure() {
        assertEquals(
            OcrLifecycleState.OCR_IMAGE_TRANSFER_FAILED,
            OcrPluginFailureKind.IMAGE_TRANSFER_FAILED.toLifecycleState(),
        )
        assertFalse(OcrPluginFailureKind.IMAGE_TRANSFER_FAILED.isRetryable())
    }

    @Test
    fun failureKindsMapToStableUiStatesAndRetryPolicy() {
        assertEquals(OcrLifecycleState.PLUGIN_NOT_INSTALLED, OcrPluginFailureKind.PLUGIN_NOT_INSTALLED.toLifecycleState())
        assertEquals(OcrLifecycleState.PERMISSION_DENIED, OcrPluginFailureKind.PERMISSION_DENIED.toLifecycleState())
        assertEquals(OcrLifecycleState.BIND_FAILED, OcrPluginFailureKind.BIND_REJECTED.toLifecycleState())
        assertEquals(OcrLifecycleState.OCR_TIMEOUT, OcrPluginFailureKind.OCR_TIMEOUT.toLifecycleState())
        assertTrue(OcrPluginFailureKind.BIND_REJECTED.isRetryable())
        assertTrue(OcrPluginFailureKind.BIND_TIMEOUT.isRetryable())
        assertFalse(OcrPluginFailureKind.PERMISSION_DENIED.isRetryable())
        assertFalse(OcrPluginFailureKind.PLUGIN_DISABLED.isRetryable())
        assertFalse(OcrPluginFailureKind.IMAGE_TRANSFER_FAILED.isRetryable())
    }
}
