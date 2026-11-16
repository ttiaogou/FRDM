package com.freedom.adapters.social.momo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoChatNodeDiagnosticPolicyTest {
    @Test
    fun alternateListviewIsOnlyProbedInsideExactChatActivity() {
        assertTrue(
            MomoChatNodeDiagnosticPolicy.isAlternateMessageListRoot(
                MomoChatPageContract.CHAT_DETAIL_ACTIVITY,
                MomoChatPageContract.MESSAGE_LIST_CLASS_NAME,
                MomoChatNodeDiagnosticPolicy.ALTERNATE_MESSAGE_LIST_VIEW_ID
            )
        )
        assertFalse(
            MomoChatNodeDiagnosticPolicy.isAlternateMessageListRoot(
                "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                MomoChatPageContract.MESSAGE_LIST_CLASS_NAME,
                MomoChatNodeDiagnosticPolicy.ALTERNATE_MESSAGE_LIST_VIEW_ID
            )
        )
        assertFalse(
            MomoChatNodeDiagnosticPolicy.isAlternateMessageListRoot(
                MomoChatPageContract.CHAT_DETAIL_ACTIVITY,
                "android.view.ViewGroup",
                MomoChatNodeDiagnosticPolicy.ALTERNATE_MESSAGE_LIST_VIEW_ID
            )
        )
    }

    @Test
    fun onlyObservedGreetingMessageContainerIdIsFlaggedAsRowCandidate() {
        assertTrue(
            MomoChatNodeDiagnosticPolicy.isGreetingMessageRowCandidate(
                MomoChatNodeDiagnosticPolicy.GREETING_MESSAGE_ROW_VIEW_ID
            )
        )
        assertFalse(
            MomoChatNodeDiagnosticPolicy.isGreetingMessageRowCandidate(
                "com.immomo.momo:id/message_chat_recycler_view"
            )
        )
    }
}
