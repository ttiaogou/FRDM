package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.ChatMessageDirection
import org.junit.Assert.*
import org.junit.Test

/** 回放用户提供的两份详情节点树；只保留结构，不包含昵称或消息文本。 */
class MomoCapturedTextContainerReplayTest {
    @Test fun capturedFormalBodiesKeepTheirOriginalDirectionEvidence() {
        run {
            val bodyId = "com.immomo.momo:id/message_tv_layouttextview"
            val trail = listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_content_layout", "com.immomo.momo:id/message_layout_leftcontainer", "com.immomo.momo:id/message_layout_messagecontainer", "", "com.immomo.momo:id/message_tv_layouttextview")
            val nodes = listOf(
                MomoChatMessageLayoutNodeEvidence("com.immomo.momo:id/message_root", "relativelayout", emptyList(), listOf("com.immomo.momo:id/message_root")),
                MomoChatMessageLayoutNodeEvidence("com.immomo.momo:id/message_layout_avatarcontainer", "relativelayout", listOf(2), listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_layout_avatarcontainer"))
            )
            assertTrue(MomoChatTextContainerPolicy.isBodyText(bodyId, trail))
            val result = MomoChatMessageDirectionPolicy.resolveForBody(listOf(1, 1, 1, 0, 0), trail, nodes)
            assertEquals(ChatMessageDirection.OUTGOING, result.direction)
        }
        run {
            val bodyId = "com.immomo.momo:id/message_tv_layouttextview"
            val trail = listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_content_layout", "com.immomo.momo:id/message_layout_rightcontainer", "com.immomo.momo:id/message_layout_messagecontainer", "", "com.immomo.momo:id/message_tv_layouttextview")
            val nodes = listOf(
                MomoChatMessageLayoutNodeEvidence("com.immomo.momo:id/message_root", "relativelayout", emptyList(), listOf("com.immomo.momo:id/message_root")),
                MomoChatMessageLayoutNodeEvidence("com.immomo.momo:id/message_iv_avatar_container", "relativelayout", listOf(0), listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_iv_avatar_container"))
            )
            assertTrue(MomoChatTextContainerPolicy.isBodyText(bodyId, trail))
            val result = MomoChatMessageDirectionPolicy.resolveForBody(listOf(1, 0, 1, 0, 0), trail, nodes)
            assertEquals(ChatMessageDirection.INCOMING, result.direction)
        }
        run {
            val bodyId = "com.immomo.momo:id/message_tv_layouttextview"
            val trail = listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_content_layout", "com.immomo.momo:id/message_layout_rightcontainer", "com.immomo.momo:id/message_layout_messagecontainer", "", "com.immomo.momo:id/message_tv_layouttextview")
            val nodes = listOf(
                MomoChatMessageLayoutNodeEvidence("com.immomo.momo:id/message_root", "relativelayout", emptyList(), listOf("com.immomo.momo:id/message_root")),
                MomoChatMessageLayoutNodeEvidence("com.immomo.momo:id/message_iv_avatar_container", "relativelayout", listOf(1), listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_iv_avatar_container"))
            )
            assertTrue(MomoChatTextContainerPolicy.isBodyText(bodyId, trail))
            val result = MomoChatMessageDirectionPolicy.resolveForBody(listOf(2, 0, 1, 0, 0), trail, nodes)
            assertEquals(ChatMessageDirection.INCOMING, result.direction)
        }
    }

    @Test fun capturedNonTextRowsContainNoFormalTextContainers() {
        val rows = listOf(
            listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_end_view", "com.immomo.momo:id/message_layout_top_container", "com.immomo.momo:id/message_content_layout", "com.immomo.momo:id/message_status_view", "com.immomo.momo:id/iv_message_status_svga", "com.immomo.momo:id/iv_message_status", "com.immomo.momo:id/message_layout_leftcontainer", "com.immomo.momo:id/message_layout_bubble_anim", "com.immomo.momo:id/message_layout_messagecontainer", "com.immomo.momo:id/message_gifview", "com.immomo.momo:id/message_layout_avatarcontainer", "com.immomo.momo:id/message_iv_userphoto", "com.immomo.momo:id/message_layout_tail", "com.immomo.momo:id/message_tv_tail"),
            listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_layout_top_container", "com.immomo.momo:id/message_iv_avatar_container", "com.immomo.momo:id/message_iv_userphoto", "com.immomo.momo:id/message_content_layout", "com.immomo.momo:id/message_layout_rightcontainer", "com.immomo.momo:id/message_layout_bubble_anim", "com.immomo.momo:id/message_layout_messagecontainer", "com.immomo.momo:id/rlRoot", "com.immomo.momo:id/root_relative_layout", "com.immomo.momo:id/mgs_show_web", "myCanvas", "com.immomo.momo:id/game_content", "com.immomo.momo:id/game_content", "com.immomo.momo:id/im_bg_cover_root", "com.immomo.momo:id/im_bg_cover", "com.immomo.momo:id/rl_show_default", "com.immomo.momo:id/im_header_icon", "com.immomo.momo:id/tv_tips", "com.immomo.momo:id/tv_desc", "com.immomo.momo:id/rd_tv_show", "com.immomo.momo:id/tv_loading_tips", "com.immomo.momo:id/tv_show", "com.immomo.momo:id/message_status_view", "com.immomo.momo:id/iv_message_status_svga", "com.immomo.momo:id/iv_message_status"),
            listOf("com.immomo.momo:id/message_tv_timestamp", "com.immomo.momo:id/message_tv_noticemessage", "com.immomo.momo:id/message_layout_messagecontainer", "com.immomo.momo:id/svga_like", "com.immomo.momo:id/layout_parent", "com.immomo.momo:id/view_content", "com.immomo.momo:id/feed_time_site_parent", "com.immomo.momo:id/feed_time_site", "com.immomo.momo:id/card_view", "com.immomo.momo:id/option_layout", "com.immomo.momo:id/layout_msg", "com.immomo.momo:id/layout_like"),
            listOf("com.immomo.momo:id/message_root", "com.immomo.momo:id/message_layout_top_container", "com.immomo.momo:id/message_iv_avatar_container", "com.immomo.momo:id/message_iv_userphoto", "com.immomo.momo:id/message_content_layout", "com.immomo.momo:id/message_layout_rightcontainer", "com.immomo.momo:id/message_layout_bubble_anim", "com.immomo.momo:id/message_layout_messagecontainer", "com.immomo.momo:id/rlRoot", "com.immomo.momo:id/root_relative_layout", "com.immomo.momo:id/mgs_show_web", "myCanvas", "com.immomo.momo:id/game_content", "com.immomo.momo:id/game_content", "com.immomo.momo:id/im_bg_cover_root", "com.immomo.momo:id/im_bg_cover", "com.immomo.momo:id/rl_show_default", "com.immomo.momo:id/im_header_icon", "com.immomo.momo:id/tv_tips", "com.immomo.momo:id/tv_desc", "com.immomo.momo:id/rd_tv_show", "com.immomo.momo:id/tv_loading_tips", "com.immomo.momo:id/tv_show", "com.immomo.momo:id/message_status_view", "com.immomo.momo:id/iv_message_status_svga", "com.immomo.momo:id/iv_message_status"),
            listOf("com.immomo.momo:id/message_tv_timestamp", "com.immomo.momo:id/message_tv_noticemessage", "com.immomo.momo:id/message_layout_messagecontainer", "com.immomo.momo:id/svga_like", "com.immomo.momo:id/layout_parent", "com.immomo.momo:id/view_content", "com.immomo.momo:id/feed_time_site_parent", "com.immomo.momo:id/feed_time_site", "com.immomo.momo:id/card_view", "com.immomo.momo:id/img_pics", "com.immomo.momo:id/bg_view", "com.immomo.momo:id/imageview", "com.immomo.momo:id/view_top_bg", "com.immomo.momo:id/view_bottom_bg", "com.immomo.momo:id/option_layout", "com.immomo.momo:id/layout_msg", "com.immomo.momo:id/layout_like")
        )
        assertEquals(5, rows.size)
        rows.forEach { row -> assertFalse(row.any(MomoChatRowCandidatePolicy::isFormalTextContainer)) }
    }
}
