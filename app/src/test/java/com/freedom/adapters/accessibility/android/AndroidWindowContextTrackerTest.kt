package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidWindowContextTrackerTest {
    @Test
    fun activityClueCanBeUpdatedAndClearedAtExistingLifecycleBoundaries() {
        val tracker = AndroidWindowContextTracker()

        assertEquals("", tracker.currentActivityClass())
        tracker.updateActivityClass("com.immomo.momo.maintab.MaintabActivity")
        assertEquals("com.immomo.momo.maintab.MaintabActivity", tracker.currentActivityClass())

        tracker.clearActivityClass()
        assertEquals("", tracker.currentActivityClass())
    }

    @Test
    fun recoveredActivityIsScopedToVerifiedWindowAndGeneration() {
        val tracker = AndroidWindowContextTracker()
        assertEquals(true, tracker.restoreActivityClass("com.immomo.momo.maintab.MaintabActivity", 116, 0L))
        assertEquals(true, tracker.recoveredActivityMatches(116, 0L))
        assertEquals(false, tracker.recoveredActivityMatches(117, 0L))
        assertEquals(false, tracker.recoveredActivityMatches(116, 1L))
        tracker.invalidateRecoveredActivity()
        assertEquals("", tracker.currentActivityClass())
    }

    @Test
    fun platformActivityEventOverridesReadOnlyRecoveryEvidence() {
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 100L })
        tracker.restoreActivityClass("com.immomo.momo.maintab.MaintabActivity", 116, 0L)
        tracker.observeWindowStateEvent("com.immomo.momo.message.activity.ChatActivity", 117)
        assertEquals("com.immomo.momo.message.activity.ChatActivity", tracker.currentActivityClass())
        assertEquals(true, tracker.recoveredActivityMatches(116, 0L))
    }

    @Test
    fun dialogWindowIsTrackedSeparatelyAndClearedWhenActivityReturns() {
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 12_345L })
        tracker.updateActivityClass("com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity")

        tracker.observeWindowStateEvent("com.immomo.momo.android.view.dialog.h", 216)

        assertEquals(
            "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            tracker.currentActivityClass()
        )
        assertEquals(216, tracker.currentDialogWindowObservation()?.windowId)
        assertEquals("com.immomo.momo.android.view.dialog.h", tracker.currentDialogWindowObservation()?.className)
        assertEquals(12_345L, tracker.currentDialogWindowObservation()?.observedAtUptimeMs)

        tracker.observeWindowStateEvent("com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK", 215)
        assertEquals(
            "com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK",
            tracker.currentActivityClass()
        )
        assertEquals(null, tracker.currentDialogWindowObservation())
    }

    @Test
    fun dialogEvidenceDoesNotExpireOnlyBecauseTheWindowRemainsOpen() {
        var now = 20_000L
        val tracker = AndroidWindowContextTracker(uptimeMillis = { now })
        tracker.observeWindowStateEvent("com.immomo.momo.android.view.dialog.h", 216)
        assertEquals(216, tracker.currentDialogWindowObservation()?.windowId)
        assertEquals(20_000L, tracker.currentDialogWindowObservation()?.observedAtUptimeMs)
        now = 30_000L
        assertEquals(20_000L, tracker.currentDialogWindowObservation()?.observedAtUptimeMs)
        tracker.observeWindowStateEvent("com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK", 215)
        assertEquals(null, tracker.currentDialogWindowObservation())
    }
}
