package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.RunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 combined regression for P0 admission diagnostics, P1 run isolation and
 * P2 fresh-window profile permits/DEFERRED handling. No Android gesture is dispatched.
 */
class MomoSayHiP4RegressionTest {
    private val profileIds = setOf(
        "com.immomo.momo:id/root_layout",
        "com.immomo.momo:id/personal_profile_name",
        "com.immomo.momo:id/toolbar_id",
        "com.immomo.momo:id/tabs",
        "com.immomo.momo:id/pagertabcontent"
    )
    private val window = WindowRef(
        packageName = "com.immomo.momo",
        activityClass = "NewSayHiSessionListActivity",
        generation = 4L,
        windowId = 31
    )

    private fun admission(stage: String = "IDLE", busy: Boolean = false) =
        MomoReceivedGreetingsAdmissionDiagnostic(
            listConfirmed = true,
            listStructureVisible = true,
            windowTargetPresent = true,
            windowCurrent = true,
            sayHiGestureInFlight = busy,
            readOnlyStage = stage,
            entrySearchGestureInFlight = false
        )

    @Test
    fun staleRunCallbackAndResidualReadOnlyStageCannotAuthorizeNextRun() {
        val runGuard = MomoSayHiRunGenerationGuard()
        runGuard.invalidatePreviousRun()
        val oldCallback = runGuard.capture()

        // Stop invalidates the in-flight gesture and the preflight sees old state as blocked.
        assertFalse(admission(stage = "WAITING_PROFILE", busy = true).isReadyForCurrentList())
        assertEquals(
            listOf("SAYHI_GESTURE_IN_FLIGHT", "READ_ONLY_STAGE_NOT_IDLE"),
            admission(stage = "WAITING_PROFILE", busy = true).rejectedGates()
        )
        runGuard.invalidatePreviousRun()
        assertFalse(runGuard.isCurrent(oldCallback))

        // Next run must acquire fresh page/window evidence; an old completion is not proof.
        val current = runGuard.invalidatePreviousRun()
        assertFalse(runGuard.isCurrent(oldCallback))
        assertTrue(runGuard.isCurrent(current))
        assertTrue(admission().isReadyForCurrentList())
    }

    @Test
    fun staleActivityAloneCannotBypassProfileAnchorsOrWindowBoundPermit() {
        assertTrue(MomoSayHiProfileScrollPolicy.permitsPendingProfileScroll(
            RunMode.AI_REPLY, true, window.windowId, profileIds
        ))
        assertFalse(MomoSayHiProfileScrollPolicy.permitsPendingProfileScroll(
            RunMode.AI_REPLY, true, window.windowId, profileIds.filterNot { it.endsWith("/tabs") }
        ))
        assertFalse(MomoSayHiProfileScrollPolicy.permitsPendingProfileScroll(
            RunMode.AI_REPLY, false, window.windowId, profileIds
        ))
        assertFalse(MomoSayHiProfileScrollPolicy.permitsPendingProfileScroll(
            RunMode.ACCOUNT_BROWSE, true, window.windowId, profileIds
        ))
        assertFalse(MomoSayHiProfileScrollPolicy.permitsPendingProfileScroll(
            RunMode.AI_REPLY, true, -1, profileIds
        ))
    }

    @Test
    fun deferralDoesNotConsumeSwipeAndNewRunCannotInheritPreviousRetryHold() {
        val generationGuard = MomoSayHiRunGenerationGuard()
        val retry = MomoSayHiProfileDeferredRetryGate(sameWindowProbeDelayMs = 5_000L)
        generationGuard.invalidatePreviousRun()
        val oldCallback = generationGuard.capture()

        retry.onDeferred("first", window, 1_000L)
        assertFalse(retry.mayAttempt(window, 2_000L))
        assertTrue(retry.mayAttempt(window, 6_000L))
        retry.onDeferred("second", window, 6_000L)
        assertFalse(retry.mayAttempt(window, 100_000L))

        generationGuard.invalidatePreviousRun() // Stop
        assertFalse(generationGuard.isCurrent(oldCallback))
        retry.reset() // Adapter-owned transaction cleanup at new run
        generationGuard.invalidatePreviousRun() // Restart
        assertTrue(retry.mayAttempt(window, 100_001L))
        assertFalse(generationGuard.isCurrent(oldCallback))
        assertTrue(admission().isReadyForCurrentList())
    }
}
