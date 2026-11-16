package com.freedom.adapters.social.momo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoChatRowCandidatePolicyTest {
    @Test
    fun embeddedGameGreetingCardIsNotAChatMessageRow() {
        val ids = listOf(
            "com.immomo.momo:id/message_layout_messagecontainer",
            "com.immomo.momo:id/mgs_show_web",
            "com.immomo.momo:id/game_content",
            "com.immomo.momo:id/rd_tv_show",
            "com.immomo.momo:id/tv_show"
        )

        assertFalse(MomoChatRowCandidatePolicy.hasMessageRowAnchor(ids))
        assertFalse(
            MomoChatRowCandidatePolicy.isMessageBodyText(
                "com.immomo.momo:id/tv_show",
                ids
            )
        )
    }

    @Test
    fun canonicalMomoMessageRootIsRecognized() {
        val ids = listOf(
            "com.immomo.momo:id/message_root",
            "com.immomo.momo:id/message_iv_avatar_container",
            "com.immomo.momo:id/message_content_layout",
            "com.immomo.momo:id/message_layout_rightcontainer",
            "com.immomo.momo:id/message_layout_messagecontainer"
        )

        assertTrue(MomoChatRowCandidatePolicy.hasMessageRowAnchor(ids))
        assertTrue(
            MomoChatRowCandidatePolicy.isMessageBodyText(
                "com.immomo.momo:id/chat_message_text",
                ids
            )
        )
    }

    @Test
    fun knownMomoMessageBodyViewIsRecognizedWithoutExposedText() {
        assertTrue(
            MomoChatRowCandidatePolicy.hasMessageRowAnchor(
                listOf("com.immomo.momo:id/message_tv_layouttextview")
            )
        )
        assertTrue(
            MomoChatRowCandidatePolicy.isMessageBodyText(
                "com.immomo.momo:id/message_tv_layouttextview",
                emptyList()
            )
        )
    }

    @Test
    fun timestampAndCenteredNoticeAreNotMessageBodyText() {
        val containers = listOf("com.immomo.momo:id/message_content_layout")
        assertFalse(
            MomoChatRowCandidatePolicy.isMessageBodyText(
                "com.immomo.momo:id/message_tv_timestamp",
                containers
            )
        )
        assertFalse(
            MomoChatRowCandidatePolicy.isMessageBodyText(
                "com.immomo.momo:id/message_tv_noticemessage",
                containers
            )
        )
    }
}
