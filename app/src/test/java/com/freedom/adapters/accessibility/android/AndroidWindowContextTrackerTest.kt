package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidWindowContextTrackerTest {
    @Test
    fun activityClueCanBeUpdatedAndClearedAtExistingLifecycleBoundaries() {
        val tracker = AndroidWindowContextTracker()

        assertEquals("", tracker.currentActivityClass())
        tracker.observeWindowStateEvent("com.immomo.momo.maintab.MaintabActivity", 116, 0L)
        assertEquals("com.immomo.momo.maintab.MaintabActivity", tracker.currentActivityClass())

        tracker.clearActivityClass()
        assertEquals("", tracker.currentActivityClass())
    }

    @Test
    fun recoveredActivityIsScopedToVerifiedWindowAndGeneration() {
        val tracker = AndroidWindowContextTracker()
        assertEquals(true, tracker.restoreActivityClass("com.immomo.momo.maintab.MaintabActivity", 116, 0L))
        assertEquals("com.immomo.momo.maintab.MaintabActivity", tracker.activityForWindow(116, 0L))
        assertEquals("", tracker.activityForWindow(117, 0L))
        assertEquals("", tracker.activityForWindow(116, 1L))
        tracker.invalidateRecoveredActivity()
        assertEquals("", tracker.currentActivityClass())
    }

    @Test
    fun platformActivityEventOverridesReadOnlyRecoveryEvidence() {
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 100L })
        tracker.restoreActivityClass("com.immomo.momo.maintab.MaintabActivity", 116, 0L)
        tracker.observeWindowStateEvent("com.immomo.momo.message.activity.ChatActivity", 117, 1L)
        assertEquals("com.immomo.momo.message.activity.ChatActivity", tracker.currentActivityClass())
        assertEquals("", tracker.activityForWindow(116, 0L))
        assertEquals("", tracker.activityForWindow(117, 0L))
        assertEquals("com.immomo.momo.message.activity.ChatActivity", tracker.activityForWindow(117, 1L))
    }

    @Test
    fun platformActivityNeverLeaksIntoNewWindowBeforeItsActivityEvent() {
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 100L })
        val list = "com.immomo.momo.maintab.MaintabActivity"
        val chat = "com.immomo.momo.message.activity.ChatActivity"
        tracker.observeWindowStateEvent(list, 116, 5L)
        assertEquals(list, tracker.activityForWindow(116, 5L))
        assertEquals(list, tracker.activityForWindow(116, 6L))
        assertEquals("", tracker.activityForWindow(117, 6L))
        tracker.observeWindowStateEvent(chat, 117, 7L)
        assertEquals("", tracker.activityForWindow(116, 7L))
        assertEquals("", tracker.activityForWindow(117, 6L))
        assertEquals(chat, tracker.activityForWindow(117, 7L))
        tracker.observeWindowStateEvent(list, 116, 8L)
        assertEquals(list, tracker.activityForWindow(116, 8L))
        assertEquals("", tracker.activityForWindow(117, 8L))
    }

    @Test
    fun returningToOriginalWindowRequiresExplicitRevalidationOfRealPlatformEvidence() {
        val tracker = AndroidWindowContextTracker()
        val list = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity"
        val chat = "com.immomo.momo.message.activity.ChatActivity"
        tracker.observeWindowStateEvent(list, 3083, 23L)
        tracker.observeWindowStateEvent(chat, 3097, 24L)

        assertEquals("", tracker.activityForWindow(3083, 24L))
        assertEquals(null, tracker.platformRecoveryCandidate(4000, 24L))
        assertEquals(null, tracker.platformRecoveryCandidate(3083, 22L))
        assertEquals(PlatformActivityWindowEvidence(list, 3083, 23L), tracker.platformRecoveryCandidate(3083, 24L))
        val proof = ActivityReadOnlyEvidence(list, 3083, 24L, null, 23L)
        assertEquals(false, tracker.restorePlatformActivity(proof.copy(activityClass = chat)))
        assertEquals(false, tracker.restorePlatformActivity(proof.copy(platformEventGeneration = 22L)))
        assertEquals(true, tracker.restorePlatformActivity(proof))
        assertEquals(list, tracker.activityForWindow(3083, 24L))
        assertEquals("", tracker.activityForWindow(3097, 24L))
        assertEquals("", tracker.activityForWindow(3083, 25L))
        tracker.invalidateRecoveredActivity()
        assertEquals("", tracker.currentActivityClass())

        // Returning a second time without a list Activity callback still uses the real original event.
        tracker.observeWindowStateEvent(chat, 3101, 26L)
        assertEquals(PlatformActivityWindowEvidence(list, 3083, 23L), tracker.platformRecoveryCandidate(3083, 26L))
        tracker.clearActivityClass()
        assertEquals(null, tracker.platformRecoveryCandidate(3083, 26L))
        assertEquals(false, tracker.restorePlatformActivity(proof))
    }

    @Test
    fun onlyRealPlatformEventsBecomeCandidatesAndNewEventsReplaceOldEvidence() {
        val tracker = AndroidWindowContextTracker()
        val list = "com.immomo.momo.maintab.MaintabActivity"
        val chat = "com.immomo.momo.message.activity.ChatActivity"
        tracker.restoreActivityClass(list, 116, 1L)
        tracker.observeWindowStateEvent(chat, 117, 2L)
        assertEquals(null, tracker.platformRecoveryCandidate(116, 2L))
        tracker.observeWindowStateEvent(list, 116, 3L)
        tracker.observeWindowStateEvent(chat, 116, 4L)
        tracker.observeWindowStateEvent(list, 117, 5L)
        assertEquals(PlatformActivityWindowEvidence(chat, 116, 4L), tracker.platformRecoveryCandidate(116, 5L))
        assertEquals(false, tracker.restorePlatformActivity(ActivityReadOnlyEvidence(list, 116, 5L, null, 3L)))
        tracker.discardPlatformRecoveryEvidence()
        assertEquals(null, tracker.platformRecoveryCandidate(116, 5L))
        assertEquals(list, tracker.activityForWindow(117, 5L))
    }

    @Test
    fun missingWindowIdentityDoesNotAuthorizeAnyRootAndRecoveryCanReplaceStaleClue() {
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 100L })
        val list = "com.immomo.momo.maintab.MaintabActivity"
        val chat = "com.immomo.momo.message.activity.ChatActivity"
        tracker.observeWindowStateEvent(list, -1, 2L)
        assertEquals("", tracker.activityForWindow(116, 2L))
        tracker.observeWindowStateEvent(list, 116, 3L)
        assertEquals(false, tracker.restoreActivityClass(chat, 116, 3L))
        assertEquals(true, tracker.restoreActivityClass(chat, 117, 4L))
        assertEquals(chat, tracker.activityForWindow(117, 4L))
        assertEquals("", tracker.activityForWindow(116, 4L))
        assertEquals("", tracker.activityForWindow(117, 5L))
    }

    @Test
    fun dialogWindowIsTrackedSeparatelyAndClearedWhenActivityReturns() {
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 12_345L })
        tracker.observeWindowStateEvent("com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity", 215, 0L)

        tracker.observeWindowStateEvent("com.immomo.momo.android.view.dialog.h", 216, 1L)

        assertEquals(
            "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            tracker.currentActivityClass()
        )
        assertEquals("com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            tracker.activityForWindow(215, 1L))
        assertEquals("", tracker.activityForWindow(216, 1L))
        assertEquals(216, tracker.currentDialogWindowObservation()?.windowId)
        assertEquals("com.immomo.momo.android.view.dialog.h", tracker.currentDialogWindowObservation()?.className)
        assertEquals(12_345L, tracker.currentDialogWindowObservation()?.observedAtUptimeMs)

        tracker.observeWindowStateEvent("com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK", 215, 2L)
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
        tracker.observeWindowStateEvent("com.immomo.momo.android.view.dialog.h", 216, 1L)
        assertEquals(216, tracker.currentDialogWindowObservation()?.windowId)
        assertEquals(20_000L, tracker.currentDialogWindowObservation()?.observedAtUptimeMs)
        now = 30_000L
        assertEquals(20_000L, tracker.currentDialogWindowObservation()?.observedAtUptimeMs)
        tracker.observeWindowStateEvent("com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK", 215, 2L)
        assertEquals(null, tracker.currentDialogWindowObservation())
    }
}
