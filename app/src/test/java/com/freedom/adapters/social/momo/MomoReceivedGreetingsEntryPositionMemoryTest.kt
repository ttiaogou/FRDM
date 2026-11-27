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
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatUnreadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun weakStationarySubsetHoldsBaselineButCannotAuthorizeDirection() {
        memory.observe(page("A", ENTRY, "B", "C"))
        val known = page("B", "C", "D", "E")
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(known))
        // A clipped or omitted name leaves only one unchanged fully visible anchor.
        val weak = known.copy(chatListCandidates = known.chatListCandidates.take(1))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(weak))
        assertEquals(MomoReceivedGreetingsPositionReason.WEAK_STATIONARY_OVERLAP, memory.reason)
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(weak))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(known))
        assertEquals(MomoReceivedGreetingsPositionReason.CONTINUOUS_VIEWPORT, memory.reason)
    }

    @Test
    fun independentUnchangedCollectionRangeRevalidatesPartialAnchorSet() {
        memory.observe(page("A", ENTRY, "B", "C").copy(
            chatListCollectionRowCount = 20, chatListVisibleCollectionRows = listOf(0, 1, 2, 3)))
        val known = page("B", "C", "D", "E").copy(
            chatListCollectionRowCount = 20, chatListVisibleCollectionRows = listOf(2, 3, 4, 5))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(known))
        val partial = known.copy(chatListCandidates = known.chatListCandidates.take(2))
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(partial))
        assertEquals(MomoReceivedGreetingsPositionReason.PARTIAL_ANCHOR_SET_REVALIDATED, memory.reason)
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE, memory.observe(known))
    }

    @Test
    fun contradictoryCompleteObservationRetiresHeldBaseline() {
        memory.observe(page("A", ENTRY, "B", "C"))
        val known = page("B", "C", "D", "E")
        memory.observe(known)
        memory.observe(known.copy(chatListCandidates = known.chatListCandidates.take(1)))
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(page("W", "X", "Y", "Z")))
        assertEquals(MomoReceivedGreetingsPositionReason.DISJOINT_ANCHORS, memory.reason)
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(known))
        assertEquals(MomoReceivedGreetingsPositionReason.NO_CONFIRMED_BASELINE, memory.reason)
    }

    @Test
    fun partialAnchorSetCannotEstablishCrossingOrBridgeDifferentWindow() {
        val visible = page("A", ENTRY, "B", "C")
        memory.observe(visible)
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(visible.copy(chatListCandidates = visible.chatListCandidates.filter { it.nameText != ENTRY })))
        assertEquals(MomoReceivedGreetingsPositionReason.ENTRY_CROSSING_UNPROVEN, memory.reason)

        memory.observe(visible)
        val known = page("B", "C", "D", "E")
        memory.observe(known)
        val differentWindow = page("B", "C", "D", "E", generation = 2L)
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN,
            memory.observe(differentWindow.copy(chatListCandidates = differentWindow.chatListCandidates.take(1))))
        assertEquals(MomoReceivedGreetingsPositionReason.MOVEMENT_UNPROVEN, memory.reason)
        assertEquals(MomoReceivedGreetingsEntryPosition.UNKNOWN, memory.observe(known))
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

    @Test
    fun l09KnownAboveHandoffRejectsThenRecoversSameSweepAndRevalidatesNextSlice() {
        val search = MomoReceivedGreetingsEntrySearchController()
        memory.observe(page("A", ENTRY, "B", "C"))
        memory.observe(page("B", "C", "D", "E"))
        memory.observe(page("B", "C", "D", "E").copy(pageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE))
        search.reset()
        val fresh = memory.observe(page("B", "C", "D", "E", generation = 2L))
        assertTrue(search.start(fresh))
        assertEquals(VerticalSwipeDirection.DOWN, search.direction())
        val generation = search.captureSearchGeneration()
        assertTrue(search.tryBeginGesture(generation, "first"))
        assertTrue(search.finishGesture("first", null, 0L))
        assertFalse(search.tryBeginGesture(generation, "immediate"))
        assertFalse(search.mayDispatch("ready", 500L))
        assertFalse(search.mayDispatch(null, 1000L))
        assertFalse(search.mayDispatch("ready", 1500L))
        assertTrue(search.mayDispatch("ready", 2000L))
        assertTrue(search.tryBeginGesture(generation, "retry"))
        assertTrue(search.finishGesture("retry", "lower", 2500L))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            search.observeAfterGesture("upper", false, 3000L))
        val visible = memory.observe(page("A", ENTRY, "B", "C", generation = 2L))
        assertEquals(MomoReceivedGreetingsEntryPosition.VISIBLE, visible)
        assertFalse(search.start(visible))
        assertFalse(search.tryBeginGesture(generation, "past-entry"))

        // Dedicated greetings pages do not erase the ordinary-inbox observation.
        memory.observe(page("A", ENTRY, "B", "C").copy(pageKind = ChatPageKind.NON_CHAT))
        search.reset()
        assertEquals(MomoReceivedGreetingsEntryPosition.ABOVE,
            memory.observe(page("B", "C", "D", "E", generation = 3L)))
        assertTrue(search.start(memory.position))
        assertEquals(VerticalSwipeDirection.DOWN, search.direction())
        assertEquals(0, search.retryAttempt())
    }

    @Test
    fun l09UnknownHandoffSweepsToPhysicalBottomThenTopWithoutUsingPrivateReadBoundary() {
        val search = MomoReceivedGreetingsEntrySearchController()
        val middle = physicalPage(4, 12, true, true, "A", "B", "C", "D")
        assertTrue(search.start(memory.observe(middle)))
        assertEquals(VerticalSwipeDirection.UP, search.direction())
        fun observe(page: ChatPageSnapshot, key: String, time: Long) =
            search.observeAfterGesture(key, ChatSessionManagementPolicy.greetingSearchBoundaryEvidence(
                page, toTop = search.direction() == VerticalSwipeDirection.DOWN
            ), time)
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(middle, "middle", 500L))
        val bottom = physicalPage(8, 12, false, true, "E", "F", "G", "H")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(bottom, "bottom", 1000L))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.REVERSED,
            observe(bottom, "bottom", 1500L))
        assertEquals(VerticalSwipeDirection.DOWN, search.direction())
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE,
            observe(middle, "middle", 2000L))
        val top = physicalPage(0, 12, true, false, "I", "J", "K", "L")
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION,
            observe(top, "top", 2500L))
        assertEquals(MomoReceivedGreetingsEntrySearchController.Observation.COMPLETE,
            observe(top, "top", 3000L))
        assertTrue(search.isComplete())
    }

    private fun physicalPage(
        first: Int, count: Int, forward: Boolean, backward: Boolean, vararg names: String
    ): ChatPageSnapshot = page(*names).copy(
        chatListCollectionRowCount = count,
        chatListVisibleCollectionRows = (first until first + names.size).toList(),
        chatListCanScrollForward = forward,
        chatListCanScrollBackward = backward
    )

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
            previewText = "Preview",
            previewSource = ChatNodeTextSource.NODE_TEXT,
            previewNodePath = NodePath(listOf(0, index, 2)),
            previewBounds = ScreenRect(150, top + 50, 600, top + 90),
            previewNodeVisibleToUser = true,
            previewBoundsIntersectViewport = true,
            previewBoundsFullyInsideViewport = true,
            previewBoundsValid = true,
            timestampText = null,
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            selectionClass = if (name == ENTRY) "SYSTEM_ENTRY" else "PRIVATE_CHAT",
            unreadStatus = ChatUnreadStatus.READ
        )
    }

    private companion object {
        const val ENTRY = "收到的招呼"
    }
}
