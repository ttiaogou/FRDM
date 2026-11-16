package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.ChatMessageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomoChatMessageDirectionPolicyTest {
    @Test
    fun observedIncomingAvatarContainerWinsAfterSameMessageRootValidation() {
        val direction = MomoChatMessageDirectionPolicy.fromObservedAvatarContainerLayout(
            bodyPath = listOf(1, 0),
            bodyViewIdTrail = listOf(
                "com.immomo.momo:id/message_root",
                "com.immomo.momo:id/message_content_layout",
                "com.immomo.momo:id/message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "com.immomo.momo:id/message_iv_avatar_container",
                    path = listOf(0),
                    trail = listOf(
                        "com.immomo.momo:id/message_root",
                        "com.immomo.momo:id/message_iv_avatar_container"
                    )
                )
            )
        )

        assertEquals(ChatMessageDirection.INCOMING, direction)
    }

    @Test
    fun observedOutgoingAvatarContainerWinsAfterSameMessageRootValidation() {
        val direction = MomoChatMessageDirectionPolicy.fromObservedAvatarContainerLayout(
            bodyPath = listOf(0, 0),
            bodyViewIdTrail = listOf(
                "com.immomo.momo:id/message_root",
                "com.immomo.momo:id/message_content_layout",
                "com.immomo.momo:id/message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "com.immomo.momo:id/message_layout_avatarcontainer",
                    path = listOf(1),
                    trail = listOf(
                        "com.immomo.momo:id/message_root",
                        "com.immomo.momo:id/message_layout_avatarcontainer"
                    )
                )
            )
        )

        assertEquals(ChatMessageDirection.OUTGOING, direction)
    }

    @Test
    fun avatarContainerIdOnUnexpectedNodeClassIsNotAccepted() {
        val direction = MomoChatMessageDirectionPolicy.fromObservedAvatarContainerLayout(
            bodyPath = listOf(1, 0),
            bodyViewIdTrail = listOf(
                "message_root", "message_content_layout", "message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "message_iv_avatar_container", listOf(0),
                    listOf("message_root", "message_iv_avatar_container"),
                    className = "TextView"
                )
            )
        )

        assertNull(direction)
    }

    @Test
    fun avatarMarkerFromDifferentMessageRootIsRejected() {
        val direction = MomoChatMessageDirectionPolicy.fromObservedAvatarContainerLayout(
            bodyPath = listOf(1, 0),
            bodyViewIdTrail = listOf(
                "wrapper",
                "com.immomo.momo:id/message_root",
                "com.immomo.momo:id/message_content_layout",
                "com.immomo.momo:id/message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "com.immomo.momo:id/message_iv_avatar_container",
                    path = listOf(0, 0),
                    trail = listOf(
                        "wrapper",
                        "com.immomo.momo:id/message_root",
                        "com.immomo.momo:id/message_iv_avatar_container"
                    )
                )
            )
        )

        assertNull(direction)
        assertEquals(
            false,
            MomoChatMessageDirectionPolicy.hasConflictingAvatarContainerMarkers(
                listOf(
                    layoutNode(
                        "message_iv_avatar_container", listOf(0, 0),
                        listOf("conversation_row", "message_root", "message_iv_avatar_container")
                    ),
                    layoutNode(
                        "message_layout_avatarcontainer", listOf(1, 0),
                        listOf("conversation_row", "message_root", "message_layout_avatarcontainer")
                    )
                )
            )
        )
    }

    @Test
    fun avatarMarkerWithoutMessageContentLayoutIsRejected() {
        val direction = MomoChatMessageDirectionPolicy.fromObservedAvatarContainerLayout(
            bodyPath = listOf(1),
            bodyViewIdTrail = listOf(
                "com.immomo.momo:id/message_root",
                "com.immomo.momo:id/message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "com.immomo.momo:id/message_iv_avatar_container",
                    path = listOf(0),
                    trail = listOf(
                        "com.immomo.momo:id/message_root",
                        "com.immomo.momo:id/message_iv_avatar_container"
                    )
                )
            )
        )

        assertNull(direction)
    }

    @Test
    fun conflictingAvatarContainerIdsAreRejected() {
        val direction = MomoChatMessageDirectionPolicy.fromObservedAvatarContainerLayout(
            bodyPath = listOf(1, 0),
            bodyViewIdTrail = listOf(
                "message_root", "message_content_layout", "message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "message_iv_avatar_container", listOf(0),
                    listOf("message_root", "message_iv_avatar_container")
                ),
                layoutNode(
                    "message_layout_avatarcontainer", listOf(2),
                    listOf("message_root", "message_layout_avatarcontainer")
                )
            )
        )

        assertNull(direction)
    }


    @Test
    fun markerFromAdjacentMessageRootDoesNotContaminateThisBody() {
        val result = MomoChatMessageDirectionPolicy.resolveForBody(
            bodyPath = listOf(0, 1, 0),
            bodyViewIdTrail = listOf(
                "conversation_row", "message_root", "message_content_layout", "message_tv_layouttextview"
            ),
            rowNodes = listOf(
                layoutNode(
                    "message_layout_avatarcontainer", listOf(0, 0),
                    listOf("conversation_row", "message_root", "message_layout_avatarcontainer")
                ),
                layoutNode(
                    "message_iv_avatar_container", listOf(1, 0),
                    listOf("conversation_row", "message_root", "message_iv_avatar_container")
                ),
                layoutNode(
                    "message_root", listOf(0), listOf("conversation_row", "message_root")
                ),
                layoutNode(
                    "message_root", listOf(1), listOf("conversation_row", "message_root")
                )
            )
        )
        assertEquals(ChatMessageDirection.OUTGOING, result.direction)
        assertEquals("SAME_MESSAGE_ROOT_AVATAR_CONTAINER", result.reason)
    }

    @Test
    fun ancestorDirectionOutsideCurrentMessageRootIsIgnored() {
        val result = MomoChatMessageDirectionPolicy.resolveForBody(
            bodyPath = listOf(0, 0, 0),
            bodyViewIdTrail = listOf(
                "message_layout_rightcontainer", "message_root", "message_content_layout", "message_tv_layouttextview"
            ),
            rowNodes = emptyList()
        )
        assertNull(result.direction)
        assertEquals("NO_DIRECTION_LAYOUT_EVIDENCE", result.reason)
    }


    @Test
    fun conflictingAncestorHintsInsideMessageRootRemainHardUnknown() {
        val result = MomoChatMessageDirectionPolicy.resolveForBody(
            bodyPath = listOf(0, 0, 0, 0),
            bodyViewIdTrail = listOf(
                "conversation_row", "message_root", "message_content_layout",
                "message_layout_leftcontainer", "message_layout_rightcontainer"
            ),
            rowNodes = emptyList()
        )
        assertEquals(ChatMessageDirection.UNKNOWN, result.direction)
        assertEquals("CONFLICTING_ANCESTOR_DIRECTION_HINTS", result.reason)
    }

    @Test
    fun observedLeftContainerIsExplicitOutgoingEvidence() {
        assertEquals(
            ChatMessageDirection.OUTGOING,
            MomoChatMessageDirectionPolicy.fromAncestorViewIds(
                listOf("com.immomo.momo:id/message_root",
                    "com.immomo.momo:id/message_layout_leftcontainer")
            )
        )
    }

    @Test
    fun observedRightContainerIsExplicitIncomingEvidence() {
        assertEquals(
            ChatMessageDirection.INCOMING,
            MomoChatMessageDirectionPolicy.fromAncestorViewIds(
                listOf("com.immomo.momo:id/message_root",
                    "com.immomo.momo:id/message_layout_rightcontainer")
            )
        )
    }

    @Test
    fun conflictingLeftAndRightAncestorsRemainUnresolved() {
        assertNull(
            MomoChatMessageDirectionPolicy.fromAncestorViewIds(
                listOf("message_layout_leftcontainer", "message_layout_rightcontainer")
            )
        )
    }

    @Test
    fun unrelatedOrMissingLayoutIdsDoNotGuessDirection() {
        assertNull(MomoChatMessageDirectionPolicy.fromAncestorViewIds(emptyList()))
        assertNull(
            MomoChatMessageDirectionPolicy.fromAncestorViewIds(
                listOf("message_root", "message_content_layout")
            )
        )
    }

    private fun layoutNode(
        id: String,
        path: List<Int>,
        trail: List<String>,
        className: String = "RelativeLayout"
    ) = MomoChatMessageLayoutNodeEvidence(
        viewId = id,
        className = className,
        path = path,
        viewIdTrail = trail
    )
}
