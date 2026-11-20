package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageNodeEvidence
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.SessionListNavigationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

class AiReplyPageStabilityTest {
    @Test
    fun transientUnknownKeepsConfirmedInboxOnlyWithSameFreshAnchorAndViewport() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L, viewportTop = 160)
        val incoming = snapshot(ChatPageKind.UNKNOWN, generation = 8L, viewportTop = 172)

        val actual = stabilizeConfirmedChatList(previous, incoming)

        assertEquals(ChatPageKind.CHAT_LIST, actual.pageKind)
        assertEquals(incoming.chatListViewport, actual.chatListViewport)
        assertNotSame(incoming, actual)
    }

    @Test
    fun newWindowGenerationDoesNotReuseConfirmedInboxClassification() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L)
        val incoming = snapshot(ChatPageKind.UNKNOWN, generation = 9L)

        assertSame(incoming, stabilizeConfirmedChatList(previous, incoming))
    }

    @Test
    fun missingCurrentNavigationAnchorEvidenceDoesNotPreserveClassification() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L)
        val incoming = snapshot(ChatPageKind.UNKNOWN, generation = 8L).copy(
            structureEvidence = emptyList()
        )

        assertSame(incoming, stabilizeConfirmedChatList(previous, incoming))
    }

    @Test
    fun hiddenInboxRecyclerDoesNotPreserveConfirmedClassification() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L)
        val incoming = snapshot(ChatPageKind.UNKNOWN, generation = 8L).copy(
            structureEvidence = snapshot(ChatPageKind.UNKNOWN, generation = 8L)
                .structureEvidence.map { node ->
                    if (node.viewId.endsWith("recyclerview")) node.copy(isVisibleToUser = false) else node
                }
        )

        assertSame(incoming, stabilizeConfirmedChatList(previous, incoming))
    }

    @Test
    fun deselectedMessageBubbleDoesNotPreserveConfirmedInboxClassification() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L)
        val incoming = snapshot(ChatPageKind.UNKNOWN, generation = 8L).copy(
            structureEvidence = snapshot(ChatPageKind.UNKNOWN, generation = 8L)
                .structureEvidence.map { node ->
                    if (node.viewId.endsWith("fl_bubble_container")) node.copy(isSelected = false) else node
                }
        )

        assertSame(incoming, stabilizeConfirmedChatList(previous, incoming))
    }

    @Test
    fun positiveNonChatClassificationIsNeverOverridden() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L)
        val incoming = snapshot(ChatPageKind.NON_CHAT, generation = 8L)

        assertSame(incoming, stabilizeConfirmedChatList(previous, incoming))
    }

    private fun snapshot(
        kind: ChatPageKind,
        generation: Long,
        viewportTop: Int = 160
    ) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "com.immomo.momo.maintab.MaintabActivity",
        pageKind = kind,
        scannedNodeCount = 30,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 1,
        hasChatTab = true,
        messageListTarget = null,
        inputFieldTarget = null,
        structureEvidence = listOf(
            ChatPageNodeEvidence(
                path = "0/2",
                className = "android.widget.FrameLayout",
                viewId = "com.immomo.momo:id/maintab_layout_chat",
                left = 288,
                top = 1486,
                right = 432,
                bottom = 1600,
                childCount = 1,
                isEditable = false,
                isScrollable = false,
                isClickable = true,
                hasText = false,
                insideListCandidate = false,
                isVisibleToUser = true,
                boundsValid = true,
                isSelected = true
            ),
            ChatPageNodeEvidence(
                path = "0/2/0/0",
                className = "android.widget.RelativeLayout",
                viewId = "com.immomo.momo:id/fl_bubble_container",
                left = 288,
                top = 1499,
                right = 432,
                bottom = 1600,
                childCount = 2,
                isEditable = false,
                isScrollable = false,
                isClickable = false,
                hasText = false,
                insideListCandidate = false,
                isVisibleToUser = true,
                boundsValid = true,
                isSelected = true
            ),
            ChatPageNodeEvidence(
                path = "0/1/0",
                className = "androidx.recyclerview.widget.RecyclerView",
                viewId = "com.immomo.momo:id/recyclerview",
                left = 0,
                top = viewportTop,
                right = 720,
                bottom = 1460,
                childCount = 5,
                isEditable = false,
                isScrollable = true,
                isClickable = false,
                hasText = false,
                insideListCandidate = true,
                isVisibleToUser = true,
                boundsValid = true
            )
        ),
        chatListViewport = ScreenRect(0, viewportTop, 720, 1460),
        screenBounds = ScreenRect(0, 0, 720, 1600),
        sessionListNavigationTarget = SessionListNavigationTarget(
            nodeTarget = NodeTarget(
                window = WindowRef("com.immomo.momo", "com.immomo.momo.maintab.MaintabActivity", generation, 41),
                path = NodePath(listOf(0, 2)),
                expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                expectedClassName = "android.widget.FrameLayout"
            ),
            bounds = ScreenRect(288, 1486, 432, 1600)
        ),
        windowGeneration = generation
    )
}
