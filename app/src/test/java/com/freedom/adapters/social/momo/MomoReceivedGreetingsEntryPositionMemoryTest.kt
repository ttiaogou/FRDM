package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoReceivedGreetingsEntryPositionMemoryTest {
    private val memory = MomoReceivedGreetingsEntryPositionMemory().apply { beginRun() }

    @Test
    fun descendingPastEntryRemembersAboveAcrossDetailAndReturn() {
        assertEquals(MomoReceivedGreetingsEntryPosition.VISIBLE, memory.observe(page("A", ENTRY, "B", "C")))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(page("B", "C", "D", "E")))
        val detail = page("B", "C", "D", "E").copy(pageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE)
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(detail))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(page("B", "C", "D", "E", generation = 2L)))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(page("D", "E", "F", "G", generation = 2L)))
    }

    @Test
    fun privateTraversalHintSurvivesSearchResetAndSelectsEarlierRowsAfterFreshReturn() {
        val search = MomoReceivedGreetingsEntrySearchController()
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.observe(page("B", "C", "D", "E"))
        memory.observe(page("B", "C", "D", "E").copy(pageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE))
        // The existing cross-task handoff resets the navigation controller, not the run memory.
        search.reset()
        val fresh = memory.observe(page("B", "C", "D", "E", generation = 2L))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, fresh)
        assertEquals(true, search.start(fresh))
        assertEquals(VerticalSwipeDirection.DOWN, search.direction())
    }

    @Test
    fun noEntryObservedBeforeHandoffStartsTowardLaterRowsAndInvalidatedHintAlsoFallsBack() {
        val search = MomoReceivedGreetingsEntrySearchController()
        memory.observe(page("A", "B", "C", "D"))
        val unknown = memory.observe(page("C", "D", "E", "F"))
        assertEquals(true, search.start(unknown))
        assertEquals(VerticalSwipeDirection.UP, search.direction())
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.observe(page("B", "C", "D", "E"))
        search.reset()
        val disjoint = memory.observe(page("W", "X", "Y", "Z"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, disjoint)
        assertEquals(true, search.start(disjoint))
        assertEquals(VerticalSwipeDirection.UP, search.direction())
    }

    @Test
    fun ascendingPastEntryRemembersBelowAndReappearanceReplacesHint() {
        assertEquals(MomoReceivedGreetingsEntryPosition.VISIBLE, memory.observe(page("A", "B", ENTRY, "C")))
        assertEquals(MomoReceivedGreetingsEntryPosition.BELOW, memory.observe(page("X", "Y", "A", "B")))
        assertEquals(MomoReceivedGreetingsEntryPosition.VISIBLE, memory.observe(page("A", "B", ENTRY, "C")))
    }

    @Test
    fun unseenEntryAndUnchangedOrDisjointEvidenceNeverInventPosition() {
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(page("A", "B", "C", "D")))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(page("C", "D", "E", "F")))
        memory.observe(page("A", ENTRY, "B", "C"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page("A", "Other", "B", "C")))
        memory.observe(page("A", ENTRY, "B", "C"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page("D", "E", "F", "G")))
    }

    @Test
    fun temporaryIncompleteSnapshotRequiresFreshRevalidation() {
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.observe(page("B", "C", "D", "E"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page("B", "C", "D", "E").copy(chatListExtractionTruncated = true)))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE,
            memory.observe(page("B", "C", "D", "E")))
    }

    @Test
    fun contradictoryOrderAndDuplicateLabelsRetireMemory() {
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.observe(page("B", "C", "D", "E"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page("C", "B", "D", "E")))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page(ENTRY, ENTRY, "B", "C")))
    }

    @Test
    fun singleAnchorNeedsCollectionMovementAndMatchingWindow() {
        memory.observe(page("A", "B", ENTRY, "C").copy(
            chatListCollectionRowCount = 20, chatListVisibleCollectionRows = listOf(0, 1, 2, 3)))
        val moved = page("C", "D", "E", "F").copy(
            chatListCollectionRowCount = 20, chatListVisibleCollectionRows = listOf(3, 4, 5, 6))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(moved))
        memory.beginRun()
        memory.observe(page("A", "B", ENTRY, "C"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(page("C", "D", "E", "F")))
    }

    @Test
    fun runLifecycleClearsAndInactiveReadsCannotRepopulate() {
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.endRun()
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(page("A", ENTRY, "B", "C")))
        memory.beginRun()
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(page("B", "C", "D", "E")))
    }

    @Test
    fun reversingWithoutCrossingKeepsKnownSide() {
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.observe(page("B", "C", "D", "E"))
        memory.observe(page("D", "E", "F", "G"))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE,
            memory.observe(page("B", "C", "D", "E")))
    }

    @Test
    fun duplicateTitleCannotCorroborateCrossing() {
        memory.observe(page(ENTRY, "B", "B", "C"))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page("B", "C", "D", "E")))
    }

    @Test
    fun changedCollectionSizeInvalidatesAbsentEntryHint() {
        memory.observe(page("A", ENTRY, "B", "C").copy(chatListCollectionRowCount = 20))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(page("B", "C", "D", "E").copy(chatListCollectionRowCount = 21)))
    }

    private fun page(vararg names: String, generation: Long = 1L): ChatPageSnapshot {
        val window = WindowRef("com.immomo.momo", "MaintabActivity", generation, 1)
        return ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = names.size,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 0, 720, 600),
            chatListCandidates = names.mapIndexed { index, name -> row(index, name, window) },
            windowGeneration = generation
        )
    }

    private fun row(index: Int, name: String, window: WindowRef): ChatListCandidateSnapshot {
        val top = index * 150
        val rowBounds = ScreenRect(0, top, 720, top + 140)
        return ChatListCandidateSnapshot(
            rowIndex = index,
            rowTarget = NodeTarget(window, NodePath(listOf(0, index))),
            rowBounds = rowBounds,
            rowVisibleToUser = true,
            intersectsViewport = true,
            fullyInsideViewport = true,
            rowBoundsValid = true,
            avatarBounds = null,
            avatarPath = null,
            nameText = name,
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, index, 1)),
            nameBounds = ScreenRect(150, top + 10, 380, top + 40),
            nameNodeVisibleToUser = true,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = null,
            previewSource = ChatNodeTextSource.NODE_TEXT,
            previewNodePath = null,
            previewBounds = null,
            previewNodeVisibleToUser = false,
            previewBoundsIntersectViewport = false,
            previewBoundsFullyInsideViewport = false,
            previewBoundsValid = false,
            timestampText = null,
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            selectionClass = if (name == ENTRY) "SYSTEM_ENTRY" else "PRIVATE_CHAT"
        )
    }

    private companion object {
        const val ENTRY = "收到的招呼"
    }
}
