package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.ChatMessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoChatMessageTextEvidencePolicyTest {
    @Test
    fun dynamicCardIsSeparatedFromInteractiveCard() {
        assertEquals(
            ChatMessageType.DYNAMIC_CARD,
            MomoChatMessageTextEvidencePolicy.inferMessageType(
                listOf("message_root", "card_view", "layout_content"), "发布了动态"
            )
        )
        assertEquals(
            ChatMessageType.INTERACTIVE_CARD,
            MomoChatMessageTextEvidencePolicy.inferMessageType(
                listOf("message_root", "message_btn_ai_chat", "game_content")
            )
        )
    }

    @Test
    fun standaloneEmojiIsSeparatedButEmojiInProseIsNotMisclassified() {
        assertEquals(
            ChatMessageType.EMOJI,
            MomoChatMessageTextEvidencePolicy.inferMessageType(
                listOf("message_root", "message_content_layout"), "😂"
            )
        )
        assertEquals(
            ChatMessageType.UNKNOWN,
            MomoChatMessageTextEvidencePolicy.inferMessageType(
                listOf("message_root", "message_content_layout"), "挺近的😂"
            )
        )
    }

    @Test
    fun conflictingMediaSignalsAreNotGuessed() {
        assertEquals(
            ChatMessageType.UNKNOWN,
            MomoChatMessageTextEvidencePolicy.inferMessageType(
                listOf("message_root", "msg_image", "message_btn_ai_chat")
            )
        )
    }
    @Test
    fun l08aMediaTypeRequiresExactScopedBodyRatherThanAvatarOrStatus() {
        val policy = MomoChatMessageTextEvidencePolicy
        val trail = listOf("message_root", "message_content_layout", "message_layout_messagecontainer")
        assertEquals(ChatMessageType.IMAGE, policy.classifiedMediaBodyType("image_content", trail + "image_content"))
        assertEquals(ChatMessageType.IMAGE, policy.classifiedMediaBodyType("message_gifview", trail + "message_gifview"))
        assertEquals(null, policy.classifiedMediaBodyType("message_iv_userphoto", trail + "message_iv_userphoto"))
        assertEquals(null, policy.classifiedMediaBodyType("image_content", listOf("message_root", "message_content_layout", "image_content")))
        assertEquals(null, policy.classifiedMediaBodyType("iv_message_status", trail + "iv_message_status"))
    }
}
