package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoSayHiTapReadinessPolicyTest {
    private val display = ScreenRect(0, 0, 720, 1554)

    @Test
    fun clippedBottomAvatarDefersToListScrollInsteadOfBeingTreatedAsHandledTap() {
        val result = MomoSayHiTapReadinessPolicy.evaluate(
            snapshotFresh = true,
            packageMatches = true,
            windowCurrent = true,
            targetBounds = ScreenRect(30, 1485, 140, 1595),
            preferredBounds = ScreenRect(30, 1485, 140, 1595),
            displayBounds = display
        )

        assertEquals(MomoSayHiTapReadiness.NEEDS_SCROLL, result)
    }

    @Test
    fun fullyVisibleAvatarCanProceed() {
        val result = MomoSayHiTapReadinessPolicy.evaluate(
            snapshotFresh = true,
            packageMatches = true,
            windowCurrent = true,
            targetBounds = ScreenRect(30, 1340, 140, 1450),
            preferredBounds = ScreenRect(30, 1340, 140, 1450),
            displayBounds = display
        )

        assertEquals(MomoSayHiTapReadiness.READY, result)
    }

    @Test
    fun staleSnapshotOrChangedWindowRequiresReacquisitionAndNeverScrollsOldCoordinates() {
        val stale = MomoSayHiTapReadinessPolicy.evaluate(
            snapshotFresh = false,
            packageMatches = true,
            windowCurrent = true,
            targetBounds = ScreenRect(30, 1485, 140, 1595),
            preferredBounds = ScreenRect(30, 1485, 140, 1595),
            displayBounds = display
        )
        val changedWindow = MomoSayHiTapReadinessPolicy.evaluate(
            snapshotFresh = true,
            packageMatches = true,
            windowCurrent = false,
            targetBounds = ScreenRect(30, 1485, 140, 1595),
            preferredBounds = ScreenRect(30, 1485, 140, 1595),
            displayBounds = display
        )

        assertEquals(MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT, stale)
        assertEquals(MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT, changedWindow)
    }

    @Test
    fun invalidGeometryRequiresReacquisitionRatherThanFallbackTap() {
        val result = MomoSayHiTapReadinessPolicy.evaluate(
            snapshotFresh = true,
            packageMatches = true,
            windowCurrent = true,
            targetBounds = ScreenRect(0, 0, 0, 0),
            preferredBounds = ScreenRect(0, 0, 0, 0),
            displayBounds = display
        )

        assertEquals(MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT, result)
    }
}
