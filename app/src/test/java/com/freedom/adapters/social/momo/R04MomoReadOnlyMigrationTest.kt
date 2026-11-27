package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import org.junit.Assert.*
import org.junit.Test

/** R04: fixed original policy outcomes, including rejected evidence and opaque keys. */
class R04MomoReadOnlyMigrationTest {
    @Test fun titleNormalizationAndAnchorHashKeepOriginalExactSemantics() {
        val policy = MomoSayHiIdentityFingerprintPolicy
        assertEquals("", policy.normalizeIdentityDisplayName(null))
        assertEquals("é A", policy.normalizeIdentityDisplayName("  e\u0301\t A  "))
        assertEquals("é A", policy.extractChatPeerName(" 与e\u0301\t A对话 "))
        assertEquals("", policy.extractChatPeerName("é A"))
        assertEquals("", policy.extractChatPeerName("与对话"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            policy.sha256IdentityAnchor("abc"))
        assertNotEquals(policy.sha256IdentityAnchor("abc"), policy.sha256IdentityAnchor(" abc "))
    }

    @Test fun windowGenerationAndTreeWindowIdKeepOriginalIdentityRules() {
        val policy = MomoWindowPageFingerprint
        val window = WindowRef("fixture", "A", 7L, 21)
        assertTrue(policy.sameMomoWindowGeneration(window, window.copy(activityClass = "B")))
        assertFalse(policy.sameMomoWindowGeneration(window, window.copy(generation = 8L)))
        assertFalse(policy.sameMomoWindowGeneration(window, window.copy(windowId = 22)))
        assertFalse(policy.sameMomoWindowGeneration(window, window.copy(packageName = "other")))
        assertEquals("fixture|21|7", policy.sayHiWindowStabilityKey(window))
        val tree = AccessibilityTreeSnapshot(window, emptyList(), 0, false, windowId = 22)
        assertEquals("fixture|22|7", policy.sayHiWindowStabilityKey(tree))
    }

    @Test fun placeholderSnapshotsKeepOriginalKindAndEmptyEvidence() {
        val greetings = MomoChatPageContract.receivedGreetingsListChatPagePlaceholder("fixture")
        assertEquals(ChatPageSnapshot("fixture", MomoSayHiPageContract.LIST_ACTIVITY,
            ChatPageKind.NON_CHAT, 0, 0, 0, 0, 0, false, null, null), greetings)
        val unknown = MomoChatPageContract.unknownChatPageSnapshot("OtherActivity")
        assertEquals(ChatPageSnapshot("", "OtherActivity", ChatPageKind.UNKNOWN,
            0, 0, 0, 0, 0, false, null, null), unknown)
    }

    @Test fun viewportEvidenceKeepsGeometryAndRejectsIncompleteOrForeignSnapshots() {
        val window = WindowRef("fixture", "A", 7L, 21)
        val bounds = ScreenRect(0, 100, 720, 240)
        val row = ChatListCandidateSnapshot(
            rowIndex = 0, rowTarget = NodeTarget(window, NodePath(listOf(0))),
            rowBounds = bounds, rowVisibleToUser = true, intersectsViewport = true,
            fullyInsideViewport = true, rowBoundsValid = true,
            avatarBounds = null, avatarPath = null,
            nameText = "Fixture", nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, 1)), nameBounds = bounds,
            nameNodeVisibleToUser = true, nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true, nameBoundsValid = true,
            previewText = null, previewSource = ChatNodeTextSource.NONE, previewNodePath = null,
            previewBounds = null, previewNodeVisibleToUser = false,
            previewBoundsIntersectViewport = false, previewBoundsFullyInsideViewport = false,
            previewBoundsValid = false, timestampText = null, timestampSource = ChatNodeTextSource.NONE,
            timestampNodePath = null, timestampBounds = null, timestampNodeVisibleToUser = false)
        val viewport = ScreenRect(0, 0, 720, 1600)
        val snapshot = ChatPageSnapshot("fixture", "A", ChatPageKind.CHAT_LIST,
            1, 0, 0, 0, 0, true, null, null,
            chatListViewport = viewport, chatListCandidates = listOf(row),
            windowGeneration = 7L, chatListCollectionRowCount = 3,
            chatListVisibleCollectionRows = listOf(1), chatListCanScrollForward = true,
            chatListCanScrollBackward = true)
        val key = ChatSessionManagementPolicy.stableSessionKey(row)
        assertTrue(key.isNotBlank())
        val policy = MomoChatListSessionPolicy
        assertEquals("fixture|windowId=21|generation=7|$viewport|3:1:1:true:true|$key@0,100,720,240",
            policy.receivedGreetingsEntrySearchViewportFingerprint(snapshot))
        for (invalid in listOf(snapshot.copy(chatListExtractionTruncated = true),
            snapshot.copy(chatListInvalidRowBoundsCount = 1),
            snapshot.copy(chatListViewport = null), snapshot.copy(windowGeneration = 8L),
            snapshot.copy(packageName = "other"), snapshot.copy(chatListCandidates = emptyList()))) {
            assertEquals("", policy.receivedGreetingsEntrySearchViewportFingerprint(invalid))
        }
    }
}
