package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPageSnapshotFingerprintTest {
    private fun detail(
        activity: String,
        windowId: Int = 21,
        generation: Long = 7L,
        title: String = "与某人对话",
        nodeCount: Int = 80,
        messageCount: Int = 0
    ): ChatPageSnapshot {
        val window = WindowRef("com.immomo.momo", activity, generation, windowId)
        return ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = activity,
            pageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE,
            scannedNodeCount = nodeCount,
            editableInputCount = 1,
            messageListCount = messageCount,
            sendActionCount = 0,
            messageRelatedViewIdCount = messageCount,
            hasChatTab = true,
            messageListTarget = NodeTarget(window, NodePath(listOf(1, 2))),
            inputFieldTarget = NodeTarget(window, NodePath(listOf(1, 3))),
            chatTitleText = title,
            windowGeneration = generation
        )
    }

    @Test fun chatDetailStructuralPageKeyIgnoresActivityAndVolatileConversationPayload() {
        val first = detail("com.immomo.momo.message.activity.ChatActivity")
        val changed = detail(
            activity = "com.immomo.momo.luaview.LuaViewActivity",
            title = "与某人对话",
            nodeCount = 211,
            messageCount = 7
        )
        assertEquals(first.structuralPageKey(), changed.structuralPageKey())
        assertEquals(first.stableKey(), changed.stableKey())
        assertTrue(first.hasSameWindowIdentity(changed))
    }

    @Test fun differentConversationTitleChangesSessionKeyButNotStructuralPageIdentity() {
        val first = detail("A", title = "与某人对话")
        val second = detail("A", title = "与另一个人对话")
        assertEquals(first.structuralPageKey(), second.structuralPageKey())
        assertNotEquals(first.conversationIdentityKey(), second.conversationIdentityKey())
        assertNotEquals(first.stableKey(), second.stableKey())
    }

    @Test fun conversationIdentityRequiresTheObservedChatHeaderPattern() {
        assertEquals(null, detail("A", title = "陌陌首页").conversationIdentityKey())
    }

    @Test fun chatDetailCannotProduceWindowIdentityWithoutCurrentWindowId() {
        assertEquals(null, detail("A", windowId = -1).windowIdentityKey())
    }

    @Test fun chatDetailStableKeyChangesWhenWindowIdentityChanges() {
        val first = detail("A")
        assertNotEquals(first.stableKey(), detail("B", windowId = 22).stableKey())
        assertNotEquals(first.stableKey(), detail("B", generation = 8L).stableKey())
    }

    private fun inbox(activity: String, windowId: Int = 21, generation: Long = 7L, rowCount: Int = 2): ChatPageSnapshot {
        val window = WindowRef("com.immomo.momo", activity, generation, windowId)
        val target = NodeTarget(window, NodePath(listOf(0, 1, 2)))
        return ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = activity,
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 100 + rowCount,
            editableInputCount = 0,
            messageListCount = rowCount,
            sendActionCount = 0,
            messageRelatedViewIdCount = rowCount,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 180, 720, 1450),
            screenBounds = ScreenRect(0, 0, 720, 1600),
            sessionListNavigationTarget = SessionListNavigationTarget(target, ScreenRect(600, 1380, 700, 1490)),
            windowGeneration = generation
        )
    }

    @Test fun inboxCanDeriveWindowIdentityFromFreshRowWhenPageAnchorsAreUnavailable() {
        val rowWindow = WindowRef("com.immomo.momo", "A", 7L, 21)
        val row = ChatListCandidateSnapshot(
            rowIndex = 0,
            rowTarget = NodeTarget(rowWindow, NodePath(listOf(0, 1))),
            rowBounds = ScreenRect(0, 180, 720, 320),
            rowVisibleToUser = true,
            intersectsViewport = true,
            fullyInsideViewport = true,
            rowBoundsValid = true,
            avatarBounds = null,
            avatarPath = null,
            nameText = "Contact",
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = null,
            nameBounds = null,
            nameNodeVisibleToUser = true,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = null,
            previewSource = ChatNodeTextSource.NONE,
            previewNodePath = null,
            previewBounds = null,
            previewNodeVisibleToUser = false,
            previewBoundsIntersectViewport = false,
            previewBoundsFullyInsideViewport = false,
            previewBoundsValid = false,
            timestampText = null,
            timestampSource = ChatNodeTextSource.NONE,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false
        )
        val snapshot = inbox("A").copy(
            messageListTarget = null,
            sessionListNavigationTarget = null,
            chatListCandidates = listOf(row)
        )
        assertEquals("com.immomo.momo|windowId=21|generation=7", snapshot.windowIdentityKey())
        assertEquals(null, snapshot.copy(chatListCandidates = emptyList()).windowIdentityKey())
        assertEquals(null, snapshot.copy(chatListCandidates = listOf(row)).copy(
            packageName = "other.package"
        ).windowIdentityKey())
        assertEquals(null, snapshot.copy(windowGeneration = 8L).windowIdentityKey())
    }

    @Test fun inboxStableKeyIgnoresActivityAndVolatileSessionRows() {
        assertEquals(inbox("A").stableKey(), inbox("DifferentActivity", rowCount = 5).stableKey())
    }

    @Test fun inboxStableKeyStillBindsWindowGenerationAndViewport() {
        val first = inbox("A")
        assertNotEquals(first.stableKey(), inbox("A", windowId = 22).stableKey())
        assertNotEquals(first.stableKey(), inbox("A", generation = 8L).stableKey())
        val changedViewport = first.copy(chatListViewport = ScreenRect(0, 200, 720, 1450))
        assertNotEquals(first.stableKey(), changedViewport.stableKey())
    }
}
