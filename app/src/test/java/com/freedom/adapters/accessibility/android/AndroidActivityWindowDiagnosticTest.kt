package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.RunMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidActivityWindowDiagnosticTest {
    private fun event(
        eventType: String = "TYPE_WINDOW_STATE_CHANGED",
        source: String? = "com.immomo.momo",
        clazz: String? = "com.immomo.momo.maintab.MaintabActivity",
        accepted: Boolean = true,
        running: Boolean = true,
        mode: RunMode? = RunMode.AI_REPLY
    ) = formatActivityWindowCallbackDiagnostic(
        eventTypeName = eventType,
        eventPackage = source,
        targetPackage = "com.immomo.momo",
        eventClassName = clazz,
        windowId = 116,
        callbackAccepted = accepted,
        runRequested = running,
        mode = mode,
        runGeneration = 5L,
        windowGeneration = 2L,
        sequence = 1L
    )

    @Test
    fun targetWindowStateEventIsAnEvidenceCandidateNotReadiness() {
        val log = event()
        assertTrue(log.contains("stage=SERVICE_CALLBACK"))
        assertTrue(log.contains("sourcePackage=TARGET"))
        assertTrue(log.contains("eventClass=com.immomo.momo.maintab.MaintabActivity"))
        assertTrue(log.contains("pipelineEligible=true"))
        assertTrue(log.contains("rejectReason=FORWARDED_ACTIVITY_CANDIDATE"))
        assertFalse(log.contains("RECOVERY_EXECUTION_READY"))
    }

    @Test
    fun foreignWindowCallbackDoesNotLeakSourcePackageOrActivityClass() {
        val log = event(source = "com.private.other.app",
            clazz = null, accepted = false)
        assertTrue(log.contains("sourcePackage=OTHER"))
        assertTrue(log.contains("pipelineEligible=false"))
        assertTrue(log.contains("rejectReason=CALLBACK_FILTER_REJECTED"))
        assertFalse(log.contains("com.private.other.app"))
    }

    @Test
    fun missingRunOrClassProofHasDistinctReason() {
        assertTrue(event(running = false).contains("rejectReason=RUN_NOT_REQUESTED"))
        assertTrue(event(mode = null).contains("rejectReason=MODE_UNAVAILABLE"))
        assertTrue(event(clazz = "android.widget.FrameLayout")
            .contains("rejectReason=NO_ACTIVITY_CLASS_CANDIDATE"))
        assertTrue(event(eventType = "TYPE_WINDOWS_CHANGED")
            .contains("rejectReason=WINDOW_CHANGE_ONLY"))
    }

    @Test
    fun maliciousClassTextCannotInjectLogLines() {
        val log = event(clazz = "SomeActivity\nprivate message text")
        assertTrue(log.contains("eventClass=UNAVAILABLE_OR_REDACTED"))
        assertFalse(log.contains("private message text"))
        assertFalse(log.contains("\n"))
    }
}
