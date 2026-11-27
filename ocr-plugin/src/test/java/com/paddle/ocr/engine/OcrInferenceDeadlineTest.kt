package com.paddle.ocr.engine

import org.junit.Assert.*
import org.junit.Test

class OcrInferenceDeadlineTest {
    @Test fun oneImageSharesBudgetAcrossDetectionAndRecognition() {
        var now = 100L
        var calls = 0
        val deadline = OcrInferenceDeadline({ now }, { calls++ })
        now = 3100L
        deadline.checkActive()
        now = 5099L
        deadline.checkActive()
        assertEquals(0, calls)
        now = 5100L
        assertTrue(deadline.requestTerminationIfDue())
        assertFalse(deadline.requestTerminationIfDue())
        assertThrows(OcrInferenceTimeout::class.java) { deadline.checkActive() }
        assertThrows(OcrInferenceTimeout::class.java) { deadline.complete() }
        assertEquals(1, calls)
    }

    @Test fun lateSuccessCannotPublishEvenIfWatchdogWasDelayed() {
        var now = 0L
        var calls = 0
        val deadline = OcrInferenceDeadline({ now }, { calls++ })
        now = 5001L
        assertThrows(OcrInferenceTimeout::class.java) { deadline.complete() }
        assertEquals(1, calls)
        deadline.retire()
        assertFalse(deadline.requestTerminationIfDue())
    }

    @Test fun successfulOrRetiredRequestCannotCancelItsSuccessor() {
        var now = 0L
        var oldCalls = 0
        val first = OcrInferenceDeadline({ now }, { oldCalls++ })
        now = 100L
        first.complete()
        var newCalls = 0
        val second = OcrInferenceDeadline({ now }, { newCalls++ })
        now = 5100L
        assertFalse(first.requestTerminationIfDue())
        assertTrue(second.requestTerminationIfDue())
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test fun failedRequestRetirementStopsWatchdog() {
        var now = 0L
        var calls = 0
        val deadline = OcrInferenceDeadline({ now }, { calls++ })
        deadline.retire()
        now = 6000L
        assertFalse(deadline.requestTerminationIfDue())
        assertEquals(0, calls)
    }
}
