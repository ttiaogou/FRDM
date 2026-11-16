package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoPopupCandidatePolicyTest {
    private val cardActivity = MomoSayHiPageContract.CARD_ACTIVITY

    @Test
    fun recognizesOnlyTheProfileCardSlideStackAsExpectedDeck() {
        assertTrue(
            MomoPopupCandidatePolicy.isInsideExpectedSayHiCardDeck(
                activityClass = cardActivity,
                inherited = false,
                viewId = MomoPopupCandidatePolicy.SAY_HI_CARD_STACK_VIEW_ID
            )
        )
        assertFalse(
            MomoPopupCandidatePolicy.isInsideExpectedSayHiCardDeck(
                activityClass = MomoSayHiPageContract.LIST_ACTIVITY,
                inherited = false,
                viewId = MomoPopupCandidatePolicy.SAY_HI_CARD_STACK_VIEW_ID
            )
        )
        assertFalse(
            MomoPopupCandidatePolicy.isInsideExpectedSayHiCardDeck(
                activityClass = cardActivity,
                inherited = false,
                viewId = "com.immomo.momo:id/view_container"
            )
        )
    }

    @Test
    fun centeredCardLayersAndTheirContentAreNotCountedAsSeparatePopups() {
        val insideDeck = MomoPopupCandidatePolicy.isInsideExpectedSayHiCardDeck(
            activityClass = cardActivity,
            inherited = true,
            viewId = "com.immomo.momo:id/slidecard_layout"
        )

        assertEquals(
            0,
            MomoPopupCandidatePolicy.candidateScore(
                cardActivity, insideDeck, "com.immomo.momo:id/slidecard_layout",
                dismissable = false, dialogClass = false, centeredOverlay = true
            )
        )
        assertEquals(
            0,
            MomoPopupCandidatePolicy.candidateScore(
                cardActivity, insideDeck, "", 
                dismissable = false, dialogClass = false, centeredOverlay = true
            )
        )
    }

    @Test
    fun multiplePreloadedCardLayersDoNotConsumeThePopupCandidateBudget() {
        val expectedDeckNodes = buildList {
            repeat(4) { add("com.immomo.momo:id/slidecard_layout") }
            repeat(4) { add("com.immomo.momo:id/slide_card_container") }
            repeat(4) { add("com.immomo.momo:id/rv_card_content") }
            repeat(20) { add("") } // nested centered content surfaces without their own ViewId
        }

        var candidates = 0
        var ignoredExpectedDeckCandidates = 0
        expectedDeckNodes.forEach { viewId ->
            val insideDeck = MomoPopupCandidatePolicy.isInsideExpectedSayHiCardDeck(
                activityClass = cardActivity,
                inherited = true,
                viewId = viewId
            )
            val score = MomoPopupCandidatePolicy.candidateScore(
                activityClass = cardActivity,
                insideExpectedSayHiCardDeck = insideDeck,
                viewId = viewId,
                dismissable = false,
                dialogClass = false,
                centeredOverlay = true
            )
            if (score > 0) candidates++
            if (score == 0 && MomoPopupCandidatePolicy.isExpectedDeckCandidateSuppressed(
                    activityClass = cardActivity,
                    insideExpectedSayHiCardDeck = insideDeck,
                    viewId = viewId,
                    dismissable = false,
                    centeredOverlay = true,
                    dialogClass = false
                )
            ) {
                ignoredExpectedDeckCandidates++
            }
        }

        assertEquals(0, candidates)
        assertEquals(32, ignoredExpectedDeckCandidates)
    }

    @Test
    fun dialogAndUnknownDismissableOverlayInsideDeckRemainVisibleToSafetyCheck() {
        val insideDeck = true
        assertEquals(
            90,
            MomoPopupCandidatePolicy.candidateScore(
                cardActivity, insideDeck, "", 
                dismissable = false, dialogClass = true, centeredOverlay = true
            )
        )
        assertEquals(
            100,
            MomoPopupCandidatePolicy.candidateScore(
                cardActivity, insideDeck, "com.immomo.momo:id/unknown_overlay",
                dismissable = true, dialogClass = false, centeredOverlay = false
            )
        )
    }

    @Test
    fun realOverlaysOutsideExpectedDeckKeepTheirExistingScores() {
        assertEquals(
            100,
            MomoPopupCandidatePolicy.candidateScore(
                cardActivity, false, "com.immomo.momo:id/external_dialog",
                dismissable = true, dialogClass = false, centeredOverlay = true
            )
        )
        assertEquals(
            90,
            MomoPopupCandidatePolicy.candidateScore(
                cardActivity, false, "com.immomo.momo:id/external_dialog",
                dismissable = false, dialogClass = true, centeredOverlay = true
            )
        )
        assertEquals(
            20,
            MomoPopupCandidatePolicy.candidateScore(
                MomoSayHiPageContract.LIST_ACTIVITY, false, "",
                dismissable = false, dialogClass = false, centeredOverlay = true
            )
        )
    }

    @Test
    fun expectedDeckSuppressionDoesNotHideExplicitModalSemantics() {
        assertTrue(
            MomoPopupCandidatePolicy.isExpectedDeckCandidateSuppressed(
                cardActivity, true, "com.immomo.momo:id/slidecard_layout",
                dismissable = false, centeredOverlay = true, dialogClass = false
            )
        )
        assertFalse(
            MomoPopupCandidatePolicy.isExpectedDeckCandidateSuppressed(
                cardActivity, true, "", 
                dismissable = false, centeredOverlay = true, dialogClass = true
            )
        )
        assertFalse(
            MomoPopupCandidatePolicy.isExpectedDeckCandidateSuppressed(
                MomoSayHiPageContract.LIST_ACTIVITY, true, "",
                dismissable = false, centeredOverlay = true, dialogClass = false
            )
        )
    }
    @Test
    fun knownChatInlineCardsDoNotConsumePopupCandidateBudget() {
        val activity = "com.immomo.momo.message.activity.ChatActivity"
        val cues = listOf("是否关注对方", "相遇成功", "聊天助手")
        cues.forEach { cue ->
            val inside = MomoPopupCandidatePolicy.isInsideKnownChatInlineCard(
                activityClass = activity,
                inherited = false,
                text = cue,
                description = ""
            )
            assertTrue(inside)
            assertEquals(
                0,
                MomoPopupCandidatePolicy.chatCandidateScore(
                    insideKnownInlineCard = inside,
                    dismissable = true,
                    explicitDismissControl = true,
                    dialogClass = false,
                    centeredOverlay = true
                )
            )
        }
    }

    @Test
    fun actualDialogSemanticsRemainCandidatesInsideInlineCards() {
        assertEquals(
            90,
            MomoPopupCandidatePolicy.chatCandidateScore(
                insideKnownInlineCard = true,
                dismissable = true,
                explicitDismissControl = true,
                dialogClass = true,
                centeredOverlay = true
            )
        )
    }

    @Test
    fun freedomFloatingOverlayIsRecognizedAsOwnedAndNotTargetPopup() {
        assertTrue(MomoPopupCandidatePolicy.isFreedomOwnedOverlayPackage("com.freedom"))
        assertTrue(MomoPopupCandidatePolicy.isFreedomOwnedOverlayPackage("com.freedom.debug"))
        assertFalse(MomoPopupCandidatePolicy.isFreedomOwnedOverlayPackage("com.immomo.momo"))
    }

    @Test
    fun inlineCardCuesAreScopedToChatActivityAndPropagateToDescendants() {
        assertFalse(
            MomoPopupCandidatePolicy.isInsideKnownChatInlineCard(
                activityClass = cardActivity,
                inherited = false,
                text = "聊天助手",
                description = ""
            )
        )
        assertTrue(
            MomoPopupCandidatePolicy.isInsideKnownChatInlineCard(
                activityClass = "com.immomo.momo.message.activity.ChatActivity",
                inherited = true,
                text = "刷新",
                description = ""
            )
        )
    }

}
