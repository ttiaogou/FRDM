package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoReceivedGreetingsAdmissionDiagnosticTest {
    private fun ready() = MomoReceivedGreetingsAdmissionDiagnostic(
        listConfirmed = true,
        listStructureVisible = true,
        windowTargetPresent = true,
        windowCurrent = true,
        sayHiGestureInFlight = false,
        readOnlyStage = "IDLE",
        entrySearchGestureInFlight = false
    )

    @Test fun allReadyHasNoRejectedGateAndSignalsAnAsyncRace() {
        val snapshot = ready()
        assertTrue(snapshot.rejectedGates().isEmpty())
        assertTrue(snapshot.toLogFields().contains("rejectReason=STATE_CHANGED_AFTER_REJECTION"))
    }

    @Test fun reportsEveryIndependentGateWithoutLosingOtherFailures() {
        val snapshot = MomoReceivedGreetingsAdmissionDiagnostic(
            listConfirmed = false,
            listStructureVisible = false,
            windowTargetPresent = false,
            windowCurrent = false,
            sayHiGestureInFlight = true,
            readOnlyStage = "WAITING_PROFILE",
            entrySearchGestureInFlight = true
        )
        assertEquals(
            listOf(
                "LIST_NOT_CONFIRMED", "LIST_STRUCTURE_NOT_VISIBLE", "WINDOW_TARGET_MISSING",
                "WINDOW_NOT_CURRENT", "SAYHI_GESTURE_IN_FLIGHT", "READ_ONLY_STAGE_NOT_IDLE",
                "ENTRY_SEARCH_GESTURE_IN_FLIGHT"
            ),
            snapshot.rejectedGates()
        )
        assertTrue(snapshot.toLogFields().contains("readOnlyStage=WAITING_PROFILE"))
        assertTrue(snapshot.toLogFields().contains("failedGates=LIST_NOT_CONFIRMED"))
    }

    @Test fun reportsSingleResidualStageEvenWhenPageAndWindowAreValid() {
        val snapshot = ready().copy(readOnlyStage = "PROFILE_SCROLL_PENDING")
        assertEquals(listOf("READ_ONLY_STAGE_NOT_IDLE"), snapshot.rejectedGates())
        assertTrue(snapshot.toLogFields().contains("rejectReason=READ_ONLY_STAGE_NOT_IDLE"))
        assertFalse(snapshot.toLogFields().contains("userId="))
    }
}
