package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiNavigationFlowControllerTest {
    private val controller = MomoSayHiNavigationFlowController(
        requiredStableSamples = 2,
        minimumStableSampleIntervalMs = 250L
    )

    @Test
    fun intermediateListActivityBeforeCardCannotStartListTraversal() {
        controller.beginEntryNavigation()

        assertTrue(controller.isWaitingForCard())
        assertFalse(controller.isReceivedListConfirmed())
        assertEquals(MomoSayHiNavigationPhase.WAITING_FOR_PROFILE_CARD, controller.phase)
    }

    @Test
    fun pendingEntryTapDefersMainActivityIdentityCheckUntilProfileCardArrives() {
        controller.beginEntryNavigation()

        assertTrue(
            controller.shouldAwaitProfileCardActivity(
                currentActivityClass = "com.immomo.momo.maintab.MaintabActivity",
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = true
            )
        )
        assertEquals(MomoSayHiNavigationPhase.WAITING_FOR_PROFILE_CARD, controller.phase)
    }

    @Test
    fun intermediateListHostAndUnknownActivityAreAllowedDuringPendingNavigation() {
        controller.beginEntryNavigation()

        assertTrue(
            controller.shouldAwaitProfileCardActivity(
                currentActivityClass = MomoSayHiPageContract.LIST_ACTIVITY,
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = true
            )
        )
        assertTrue(
            controller.shouldAwaitProfileCardActivity(
                currentActivityClass = "com.immomo.momo.message.sayhi.activity.TransitionActivity",
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = true
            )
        )
        assertFalse(
            controller.shouldAwaitProfileCardActivity(
                currentActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = true
            )
        )
        assertFalse(
            controller.shouldAwaitProfileCardActivity(
                currentActivityClass = "com.immomo.momo.maintab.MaintabActivity",
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = false
            )
        )
    }

    @Test
    fun entryNavigationWaitIsDisabledAfterControllerLeavesWaitingForCardPhase() {
        controller.beginEntryNavigation()
        assertFalse(controller.observeStableCardTarget("card-window", "close/list", 1_000L))
        assertTrue(controller.observeStableCardTarget("card-window", "close/list", 1_300L))

        assertFalse(
            controller.shouldAwaitProfileCardActivity(
                currentActivityClass = "com.immomo.momo.maintab.MaintabActivity",
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = true
            )
        )
    }

    @Test
    fun cardTargetMustRemainStableAcrossSpacedSnapshotsBeforeTapCanBeSubmitted() {
        controller.beginEntryNavigation()

        assertFalse(controller.observeStableCardTarget("card-window", "close/list@10,20", 1_000L))
        assertFalse(controller.observeStableCardTarget("card-window", "close/list@10,20", 1_120L))
        assertTrue(controller.observeStableCardTarget("card-window", "close/list@10,20", 1_260L))
        assertTrue(controller.markCardListTapSubmitted())
        assertEquals(MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST, controller.phase)
    }

    @Test
    fun changedCardWindowOrTargetRestartsStabilityCount() {
        controller.beginEntryNavigation()
        assertFalse(controller.observeStableCardTarget("card-window-a", "target-a", 1_000L))
        assertFalse(controller.observeStableCardTarget("card-window-b", "target-a", 1_300L))
        assertFalse(controller.observeStableCardTarget("card-window-b", "target-b", 1_600L))
        assertTrue(controller.observeStableCardTarget("card-window-b", "target-b", 1_900L))
    }

    @Test
    fun listMustBeConfirmedOnlyAfterCardListTapAndTwoStableListSnapshots() {
        controller.beginEntryNavigation()
        assertFalse(controller.observeStableReceivedList("list-window", "list@0,100", 1_000L))
        assertFalse(controller.isReceivedListConfirmed())

        assertFalse(controller.observeStableCardTarget("card-window", "close/list", 1_100L))
        assertTrue(controller.observeStableCardTarget("card-window", "close/list", 1_400L))
        assertTrue(controller.markCardListTapSubmitted())

        assertFalse(controller.observeStableReceivedList("list-window", "list@0,100", 1_500L))
        assertFalse(controller.isReceivedListConfirmed())
        assertTrue(controller.observeStableReceivedList("list-window", "list@0,100", 1_800L))
        assertTrue(controller.isReceivedListConfirmed())
    }

    @Test
    fun failedCardTapRequiresFreshCardEvidenceBeforeRetry() {
        controller.beginEntryNavigation()
        assertFalse(controller.observeStableCardTarget("card-window", "close/list", 1_000L))
        assertTrue(controller.observeStableCardTarget("card-window", "close/list", 1_300L))
        assertTrue(controller.markCardListTapSubmitted())

        controller.markCardListTapFailed()
        assertEquals(MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD, controller.phase)
        assertFalse(controller.observeStableCardTarget("card-window", "close/list", 1_600L))
        assertTrue(controller.observeStableCardTarget("card-window", "close/list", 1_900L))
    }

    @Test
    fun confirmedOrAwaitingReceivedListUsesDedicatedInspectorInsteadOfGenericChatWalker() {
        val controller = MomoSayHiNavigationFlowController()
        controller.beginEntryNavigation()
        assertFalse(controller.shouldUseDedicatedReceivedListInspector(false))

        controller.observeStableCardTarget("card-window", "close-list", 1_000L)
        assertFalse(controller.shouldUseDedicatedReceivedListInspector(false))
        controller.observeStableCardTarget("card-window", "close-list", 1_300L)
        assertTrue(controller.markCardListTapSubmitted())
        assertTrue(controller.shouldUseDedicatedReceivedListInspector(true))
        assertFalse(controller.shouldUseDedicatedReceivedListInspector(false))

        assertFalse(controller.observeStableReceivedList("list-window", "listview", 2_000L))
        assertTrue(controller.observeStableReceivedList("list-window", "listview", 2_300L))
        assertTrue(controller.shouldUseDedicatedReceivedListInspector(true))
        assertFalse(controller.shouldUseDedicatedReceivedListInspector(false))
    }

    @Test
    fun manuallyOpenedListCanBeConfirmedWithoutProfileCardNavigation() {
        assertEquals(MomoSayHiNavigationPhase.IDLE, controller.phase)
        assertFalse(controller.isReceivedListConfirmed())

        assertFalse(
            controller.observeStableManuallyOpenedReceivedList(
                windowKey = "manual-window",
                listSignature = "listview@0,100",
                capturedAtMs = 1_000L
            )
        )
        assertTrue(controller.isManuallyStabilizingReceivedList())
        assertTrue(controller.shouldUseDedicatedReceivedListInspector(true))

        assertTrue(
            controller.observeStableManuallyOpenedReceivedList(
                windowKey = "manual-window",
                listSignature = "listview@0,100",
                capturedAtMs = 1_300L
            )
        )
        assertTrue(controller.isReceivedListConfirmed())
        assertEquals(MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED, controller.phase)
    }

    @Test
    fun manualListStabilityRestartsWhenWindowOrListSignatureChanges() {
        assertFalse(controller.observeStableManuallyOpenedReceivedList("window-a", "list-a", 1_000L))
        assertFalse(controller.observeStableManuallyOpenedReceivedList("window-b", "list-a", 1_300L))
        assertFalse(controller.isReceivedListConfirmed())
        assertFalse(controller.observeStableManuallyOpenedReceivedList("window-b", "list-b", 1_600L))
        assertTrue(controller.observeStableManuallyOpenedReceivedList("window-b", "list-b", 1_900L))
        assertTrue(controller.isReceivedListConfirmed())
    }

}
