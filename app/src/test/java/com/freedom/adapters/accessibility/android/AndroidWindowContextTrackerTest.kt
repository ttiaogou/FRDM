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
