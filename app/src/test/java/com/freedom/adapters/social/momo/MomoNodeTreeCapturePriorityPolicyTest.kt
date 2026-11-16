package com.freedom.adapters.social.momo

import com.freedom.core.logging.NodeTreeCapturePriority
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoNodeTreeCapturePriorityPolicyTest {
    @Test
    fun receivedGreetingsListAndCardAreKeyPages() {
        assertEquals(
            NodeTreeCapturePriority.KEY_PAGE,
            MomoNodeTreeCapturePriorityPolicy.classify(MomoSayHiPageContract.LIST_ACTIVITY)
        )
        assertEquals(
            NodeTreeCapturePriority.KEY_PAGE,
            MomoNodeTreeCapturePriorityPolicy.classify(MomoSayHiPageContract.CARD_ACTIVITY)
        )
    }

    @Test
    fun profileAndChatDetailAreKeyPages() {
        assertEquals(
            NodeTreeCapturePriority.KEY_PAGE,
            MomoNodeTreeCapturePriorityPolicy.classify(
                "com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK"
            )
        )
        assertEquals(
            NodeTreeCapturePriority.KEY_PAGE,
            MomoNodeTreeCapturePriorityPolicy.classify(MomoChatPageContract.CHAT_DETAIL_ACTIVITY)
        )
        assertEquals(
            NodeTreeCapturePriority.KEY_PAGE,
            MomoNodeTreeCapturePriorityPolicy.classify(
                MomoChatPageContract.MAIN_ACTIVITY,
                MomoChatPageType.CHAT_DETAIL_CANDIDATE
            )
        )
    }

    @Test
    fun ordinaryMainActivityListUsesStandardQuota() {
        assertEquals(
            NodeTreeCapturePriority.STANDARD,
            MomoNodeTreeCapturePriorityPolicy.classify(MomoChatPageContract.MAIN_ACTIVITY, MomoChatPageType.CHAT_LIST)
        )
    }
}
