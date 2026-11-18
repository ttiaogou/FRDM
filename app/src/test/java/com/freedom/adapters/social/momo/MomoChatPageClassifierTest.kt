package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Test

class MomoChatPageClassifierTest {
    private fun stableDetailSignals(
        activityClass: String = MomoChatPageContract.CHAT_DETAIL_ACTIVITY
    ) = MomoChatPageSignals(
        packageMatches = true,
        activityClass = activityClass,
        // A changing/empty message RecyclerView must not affect page identity.
        messageListCount = 0,
        hasCanonicalMessageList = false,
        hasChatTitleAnchor = true,
        hasMessageComposerAnchor = true,
        hasMessageEmotesAnchor = true
    )

    @Test
    fun rejectsWindowFromAnotherPackage() {
        assertEquals(
            MomoChatPageType.UNKNOWN,
            MomoChatPageClassifier.classify(
                stableDetailSignals().copy(packageMatches = false)
            )
        )
    }

    @Test
    fun incompleteMainActivityInspectionIsUnknownRatherThanNonChat() {
        assertEquals(
            MomoChatPageType.UNKNOWN,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    inspectionIncomplete = true
                )
            )
        )
    }

    @Test
    fun incompleteInboxInspectionCannotConfirmEvenWhenAllVisibleAnchorsMatch() {
        assertEquals(
            MomoChatPageType.UNKNOWN,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    hasChatTab = true,
                    messageTabSelected = true,
                    hasInboxRecyclerView = true,
                    hasMessageTopTab = true,
                    messageTopTabSelected = true,
                    hasSessionListHierarchy = true,
                    hasMessageSearchAndContactActions = true,
                    inspectionIncomplete = true
                )
            )
        )
    }

    @Test
    fun recognizesInboxThroughTopMessageTabSelectedAndCompleteListShell() {
        assertEquals(
            MomoChatPageType.CHAT_LIST,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    hasMessageTopTab = true,
                    messageTopTabSelected = true,
                    hasSessionListHierarchy = true,
                    hasInboxRecyclerView = true
                )
            )
        )
    }

    @Test
    fun recognizesInboxThroughBottomChatTabSelectedAndCompleteListShell() {
        assertEquals(
            MomoChatPageType.CHAT_LIST,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    hasChatTab = true,
                    messageTabSelected = true,
                    hasSessionListHierarchy = true,
                    hasInboxRecyclerView = true
                )
            )
        )
    }

    @Test
    fun appBarSearchAndContactActionsAreNotAThirdListFingerprint() {
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    hasSessionListHierarchy = true,
                    hasInboxRecyclerView = true,
                    hasMessageSearchAndContactActions = true
                )
            )
        )
    }

    @Test
    fun selectedTabWithoutUniqueUsableInboxRecyclerViewCannotConfirmList() {
        val topTab = MomoChatPageSignals(
            packageMatches = true,
            activityClass = MomoChatPageContract.MAIN_ACTIVITY,
            hasMessageTopTab = true,
            messageTopTabSelected = true,
            hasSessionListHierarchy = true,
            hasInboxRecyclerView = false
        )
        assertEquals(MomoChatPageType.NON_CHAT, MomoChatPageClassifier.classify(topTab))
    }

    @Test
    fun selectedBottomChatTabWithoutCompleteSessionHierarchyCannotConfirmList() {
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    hasChatTab = true,
                    messageTabSelected = true,
                    hasInboxRecyclerView = true,
                    hasSessionListHierarchy = false
                )
            )
        )
    }

    @Test
    fun recognizesStableFingerprintWithoutRequiringMessageListOrMessageStructureCounts() {
        assertEquals(
            MomoChatPageType.CHAT_DETAIL_CANDIDATE,
            MomoChatPageClassifier.classify(stableDetailSignals())
        )
    }

    @Test
    fun eachStableAnchorIsRequired() {
        val valid = stableDetailSignals()
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(valid.copy(hasChatTitleAnchor = false))
        )
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(valid.copy(hasMessageComposerAnchor = false))
        )
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(valid.copy(hasMessageEmotesAnchor = false))
        )
    }


    @Test
    fun avatarAndMessageListCannotReplaceTheThreeNodeFingerprint() {
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(
                stableDetailSignals().copy(
                    hasChatTitleAnchor = false,
                    hasMessageComposerAnchor = false,
                    hasPeerMessageAvatarAnchor = true,
                    hasCanonicalMessageList = true,
                    hasToolbarBackButton = true
                )
            )
        )
    }

    @Test
    fun incompleteDetailInspectionCannotMatchTheThreeNodeFingerprint() {
        assertEquals(
            MomoChatPageType.UNKNOWN,
            MomoChatPageClassifier.classify(
                stableDetailSignals().copy(
                    hasChatTitleAnchor = false,
                    hasMessageComposerAnchor = false,
                    hasCanonicalMessageList = true,
                    hasToolbarBackButton = true,
                    inspectionIncomplete = true
                )
            )
        )
    }

    @Test
    fun toolbarBackButtonIsNotRequiredForPageIdentity() {
        assertEquals(
            MomoChatPageType.CHAT_DETAIL_CANDIDATE,
            MomoChatPageClassifier.classify(
                stableDetailSignals().copy(hasToolbarBackButton = false)
            )
        )
    }

    @Test
    fun alternateTitleComposerAndMessageRegionPathSurvivesTemporarilyHiddenAvatar() {
        assertEquals(
            MomoChatPageType.CHAT_DETAIL_CANDIDATE,
            MomoChatPageClassifier.classify(
                stableDetailSignals().copy(
                    hasPeerMessageAvatarAnchor = false,
                    hasCanonicalMessageList = true,
                    hasToolbarBackButton = false
                )
            )
        )
    }

    @Test
    fun mainActivityDetailOverlayUsesTheSameStableFingerprint() {
        assertEquals(
            MomoChatPageType.CHAT_DETAIL_CANDIDATE,
            MomoChatPageClassifier.classify(
                stableDetailSignals(MomoChatPageContract.MAIN_ACTIVITY)
            )
        )
    }

    @Test
    fun activityNameCannotVetoACompleteStructuralChatFingerprint() {
        val activities = listOf(
            "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiCardActivity",
            "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            "com.immomo.momo.mk.MomoMKWebActivity",
            "com.immomo.momo.luaview.LuaViewActivity",
            "com.immomo.momo.ContainerActivity"
        )
        activities.forEach { activity ->
            assertEquals(
                activity,
                MomoChatPageType.CHAT_DETAIL_CANDIDATE,
                MomoChatPageClassifier.classify(stableDetailSignals(activity))
            )
        }
    }

    @Test
    fun incompleteInspectionWithoutAllFingerprintAnchorsRemainsUnknown() {
        assertEquals(
            MomoChatPageType.UNKNOWN,
            MomoChatPageClassifier.classify(
                stableDetailSignals().copy(
                    hasMessageEmotesAnchor = false,
                    inspectionIncomplete = true
                )
            )
        )
    }

    @Test
    fun activityNameAloneIsNeverSufficientToIdentifyChatDetail() {
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.CHAT_DETAIL_ACTIVITY
                )
            )
        )
    }

    @Test
    fun oldToolbarAvatarAndCloseButtonSignalsNoLongerDefineChatDetail() {
        assertEquals(
            MomoChatPageType.NON_CHAT,
            MomoChatPageClassifier.classify(
                MomoChatPageSignals(
                    packageMatches = true,
                    activityClass = MomoChatPageContract.MAIN_ACTIVITY,
                    hasCanonicalMessageList = true,
                    messageListCount = 1
                )
            )
        )
    }
}
