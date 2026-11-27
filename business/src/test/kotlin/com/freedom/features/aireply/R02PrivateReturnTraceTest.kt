package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.UnfinishedPrivateSessionDisposition
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test


/** Uses the existing traversal fixture shape; real executor, no Android operations. */
class R02PrivateReturnTraceTest {
    @Test
    fun restoredBottomToTopCursorRetainsProcessedRowAndNextCandidate() {
        val done = candidate(0, "Already sent", ChatUnreadStatus.READ)
        val next = candidate(1, "Pending unread", ChatUnreadStatus.UNREAD)
        val current = snapshot(done, next)
        val original = PrivateMessagesTraversalTaskExecutor().apply {
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
            lockedUnreadSetFinalized = true
            initialUnreadGateSwipeCompleted = true
            initialInboxPositionCheckCompleted = false
            lockedUnreadSessionKeys += setOf(key(done), key(next))
            attemptedSessionKeys += key(done)
            checkpointLastSuccessfulTaskId = "prior-confirmation"
            recordVerifiedViewport(current)
        }
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(original.checkpoint()) }
        val trace = mutableListOf<String>()
        trace += "compatible:${restored.isRestoredViewportCompatible(current)}:${restored.hasRestoredViewportContinuity(current)}"
        val result = restored.evaluateListSnapshot(current, false, 2)
        trace += "admission:${result.unreadAdmissionDecision}"
        trace += "next:${result.nextCandidate?.nameText}"
        trace += "phase:${restored.phase}"
        trace += "processed:${restored.attemptedSessionKeys == setOf(key(done))}"
        trace += "receipt:${restored.checkpointLastSuccessfulTaskId}"
        trace += "recover:${restored.scanContinuityRecoveryPending}"
        assertEquals(listOf("compatible:true:true", "admission:null", "next:Pending unread",
            "phase:SCANNING_BOTTOM_TO_TOP", "processed:true", "receipt:prior-confirmation",
            "recover:false"), trace)
    }

    private fun key(candidate: ChatListCandidateSnapshot): String =
        com.freedom.features.aireply.ports.ChatSessionManagementPolicy.stableSessionKey(candidate)

    private fun snapshot(vararg candidates: ChatListCandidateSnapshot) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "MaintabActivity",
        pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_LIST,
        scannedNodeCount = candidates.size,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = true,
        messageListTarget = null,
        inputFieldTarget = null,
        chatListViewport = ScreenRect(0, 0, 720, 1600),
        chatListCandidates = candidates.toList()
    )

    private fun candidate(
        rowIndex: Int,
        name: String,
        unread: ChatUnreadStatus,
        top: Int = 100 + rowIndex * 150,
        previewBoundsValid: Boolean = true,
        badgePresent: Boolean = false,
        rowHeight: Int = 140,
        clippedAtEdge: Boolean = false,
        selectionClass: String = "PRIVATE_CHAT",
        previewBounds: ScreenRect = ScreenRect(150, top + 50, 620, top + 90),
        previewBoundsIntersectViewport: Boolean = true,
        previewBoundsFullyInsideViewport: Boolean = true,
        rowVisible: Boolean = true,
        inViewport: Boolean = true,
        windowGeneration: Long = 1L,
        nameNodeVisible: Boolean = true,
        previewNodeVisible: Boolean = true
    ): ChatListCandidateSnapshot {
        val row = ScreenRect(0, top, 720, top + rowHeight)
        val nameBounds = ScreenRect(150, top + 10, 380, top + 40)
        return ChatListCandidateSnapshot(
            rowIndex = rowIndex,
            rowTarget = NodeTarget(
                WindowRef("com.immomo.momo", "MaintabActivity", windowGeneration, 1),
                NodePath(listOf(0, rowIndex)),
                expectedClassName = "android.widget.RelativeLayout"
            ),
            rowBounds = row,
            rowVisibleToUser = rowVisible,
            intersectsViewport = inViewport,
            fullyInsideViewport = inViewport,
            rowBoundsValid = true,
            avatarBounds = ScreenRect(20, top + 10, 110, top + 100),
            avatarPath = NodePath(listOf(0, rowIndex, 0)),
            nameText = name,
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, rowIndex, 1)),
            nameBounds = nameBounds,
            nameNodeVisibleToUser = nameNodeVisible,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = "Preview $rowIndex",
            previewSource = ChatNodeTextSource.NODE_TEXT,
            previewNodePath = NodePath(listOf(0, rowIndex, 2)),
            previewBounds = previewBounds,
            previewNodeVisibleToUser = previewNodeVisible,
            previewBoundsIntersectViewport = previewBoundsIntersectViewport,
            previewBoundsFullyInsideViewport = previewBoundsFullyInsideViewport,
            previewBoundsValid = previewBoundsValid,
            timestampText = "10:20",
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            statusNewNodePresent = badgePresent,
            unreadStatus = unread,
            selectionClass = selectionClass,
            rowClippedAtViewportEdge = clippedAtEdge
        )
    } 
}
