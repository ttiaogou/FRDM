package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatListUnreadNodeEvidence
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatUnreadEvidenceSource
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoChatListSessionPolicyTest {
    @Test
    fun nodeTextTakesPrecedenceOverContentDescription() {
        val result = MomoChatListSessionPolicy.selectText("Alice", "Alice online")
        assertEquals("Alice", result.value)
        assertEquals(MomoChatListTextSource.NODE_TEXT, result.source)
    }

    @Test
    fun contentDescriptionIsFallbackWhenNodeTextIsBlank() {
        val result = MomoChatListSessionPolicy.selectText("  ", "Preview content")
        assertEquals("Preview content", result.value)
        assertEquals(MomoChatListTextSource.CONTENT_DESCRIPTION, result.source)
    }

    @Test
    fun missingTextIsExplicitlyUnavailable() {
        val result = MomoChatListSessionPolicy.selectText(null, "  ")
        assertNull(result.value)
        assertEquals(MomoChatListTextSource.NONE, result.source)
    }

    @Test
    fun partiallyIntersectingRowIsGeometricallyWithinViewport() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val row = ScreenRect(0, 1400, 720, 1510)
        assertTrue(MomoChatListSessionPolicy.intersectsViewport(row, viewport))
    }

    @Test
    fun nonIntersectingRowIsNotInViewport() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val row = ScreenRect(0, 1486, 720, 1600)
        assertFalse(MomoChatListSessionPolicy.intersectsViewport(row, viewport))
    }

    @Test
    fun emptyRowCannotBeCandidateWithoutAvatarOrText() {
        val empty = MomoChatListTextValue(null, MomoChatListTextSource.NONE)
        assertFalse(MomoChatListSessionPolicy.isSessionCandidate(false, empty, empty))
        assertTrue(MomoChatListSessionPolicy.isSessionCandidate(true, empty, empty))
        assertTrue(
            MomoChatListSessionPolicy.isSessionCandidate(
                false,
                MomoChatListTextValue("Name", MomoChatListTextSource.NODE_TEXT),
                empty
            )
        )
    }


    @Test
    fun mainInboxSwipeBandExcludesNestedActiveCarousels() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val safeBand = MomoChatListSessionPolicy.largestVerticalBandExcluding(
            bounds = viewport,
            excludedBounds = listOf(
                ScreenRect(0, 156, 720, 336),
                ScreenRect(0, 575, 720, 832)
            ),
            minimumWidth = 240,
            minimumHeight = 320
        )

        val resolvedSafeBand = requireNotNull(safeBand)
        assertEquals(ScreenRect(0, 832, 720, 1486), resolvedSafeBand)
        assertTrue(resolvedSafeBand.top >= 832)
        assertFalse(resolvedSafeBand.top < 832 && resolvedSafeBand.bottom > 575)
    }

    @Test
    fun avatarHashDifferenceOnlySignalsPotentialNameCollision() {
        assertFalse(MomoChatListSessionPolicy.isAvatarFingerprintConflictCandidate(null, 8))
        assertFalse(MomoChatListSessionPolicy.isAvatarFingerprintConflictCandidate(8, 8))
        assertTrue(MomoChatListSessionPolicy.isAvatarFingerprintConflictCandidate(9, 8))
        assertFalse(MomoChatListSessionPolicy.isAvatarFingerprintConflictCandidate(9, -1))
        assertFalse(
            MomoChatListSessionPolicy.isAvatarFingerprintConflictCandidate(
                distance = 24, similarityThreshold = 8, platformIdVerified = true
            )
        )
    }

    @Test
    fun nestedCarouselExclusionsThatLeaveNoSafeBandRejectMainListSwipe() {
        assertNull(
            MomoChatListSessionPolicy.largestVerticalBandExcluding(
                bounds = ScreenRect(0, 156, 720, 800),
                excludedBounds = listOf(ScreenRect(0, 156, 720, 800)),
                minimumWidth = 240,
                minimumHeight = 320
            )
        )
    }

    @Test
    fun dynamicActiveCardsAndNoticeHoldersAreRecognizedByExactIds() {
        assertTrue(MomoChatListSessionPolicy.isAuxiliaryRecyclerView("com.immomo.momo:id/active_rv"))
        assertTrue(MomoChatListSessionPolicy.isKnownNonSessionHolder("com.immomo.momo:id/notice_layout_content_root"))
        assertFalse(MomoChatListSessionPolicy.isAuxiliaryRecyclerView("com.immomo.momo:id/recyclerview"))
        assertFalse(MomoChatListSessionPolicy.isKnownNonSessionHolder("com.immomo.momo:id/item_layout"))
    }

    @Test
    fun aiFinderFloatingContainerIsMatchedByExactResourceIdOnly() {
        assertTrue(MomoChatListSessionPolicy.isAiFinderFloatingView("com.immomo.momo:id/mf_view"))
        assertTrue(MomoChatListSessionPolicy.isAiFinderFloatingView("mf_view"))
        assertFalse(MomoChatListSessionPolicy.isAiFinderFloatingView("com.immomo.momo:id/iv_icon"))
        assertFalse(MomoChatListSessionPolicy.isAiFinderFloatingView("com.immomo.momo:id/view_title"))
        assertFalse(MomoChatListSessionPolicy.isAiFinderFloatingView("com.immomo.momo:id/mf_view_extra"))
    }

    @Test
    fun safeRegionAvoidsTheLiveFloatingControlWithoutChangingTheListViewport() {
        val viewport = ScreenRect(0, 200, 720, 1480)
        val floatingControl = ScreenRect(380, 1120, 700, 1380)

        val safe = MomoChatListSessionPolicy.largestSafeSubRect(
            bounds = viewport,
            excludedBounds = floatingControl,
            minimumWidth = 240,
            minimumHeight = 240
        )

        assertTrue(safe != null)
        assertTrue(safe!!.left >= viewport.left && safe.top >= viewport.top)
        assertTrue(safe.right <= viewport.right && safe.bottom <= viewport.bottom)
        assertFalse(
            safe.left < floatingControl.right && safe.right > floatingControl.left &&
                safe.top < floatingControl.bottom && safe.bottom > floatingControl.top
        )
        // The source viewport remains the same object/value used by boundary confirmation.
        assertEquals(ScreenRect(0, 200, 720, 1480), viewport)
    }

    @Test
    fun safeRegionRejectsAnOverlayThatLeavesNoUsableGestureRectangle() {
        val viewport = ScreenRect(0, 0, 300, 300)
        val floatingControl = ScreenRect(100, 0, 300, 300)

        assertNull(
            MomoChatListSessionPolicy.largestSafeSubRect(
                bounds = viewport,
                excludedBounds = floatingControl,
                minimumWidth = 240,
                minimumHeight = 240
            )
        )
    }

    @Test
    fun nonOverlappingFloatingControlDoesNotShrinkTheRequestedRegion() {
        val viewport = ScreenRect(0, 200, 720, 1480)
        val floatingControl = ScreenRect(720, 1120, 900, 1380)

        assertEquals(
            viewport,
            MomoChatListSessionPolicy.largestSafeSubRect(
                bounds = viewport,
                excludedBounds = floatingControl,
                minimumWidth = 240,
                minimumHeight = 240
            )
        )
    }

    @Test
    fun clippedRowPreviewCannotContributeToBoundaryConfirmation() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val candidate = boundaryCandidate(
            row = ScreenRect(0, 1420, 720, 1486),
            name = ScreenRect(157, 1440, 327, 1480),
            preview = ScreenRect(157, 1501, 570, 1510),
            previewFullyInside = false
        )

        assertFalse(ChatSessionManagementPolicy.isCompleteBoundaryEvidence(candidate, viewport))
    }

    @Test
    fun clippedRowAndNameCannotContributeToBoundaryConfirmation() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val clippedRow = boundaryCandidate(
            row = ScreenRect(0, 1450, 720, 1510),
            name = ScreenRect(157, 1455, 327, 1470),
            preview = ScreenRect(157, 1475, 570, 1485),
            previewFullyInside = true
        )
        val clippedName = boundaryCandidate(
            row = ScreenRect(0, 1400, 720, 1486),
            name = ScreenRect(157, 1460, 327, 1490),
            preview = ScreenRect(157, 1430, 570, 1450),
            previewFullyInside = true
        )

        assertFalse(ChatSessionManagementPolicy.isCompleteBoundaryEvidence(clippedRow, viewport))
        assertFalse(ChatSessionManagementPolicy.isCompleteBoundaryEvidence(clippedName, viewport))
    }

    @Test
    fun nonVisibleRowsCannotContributeToBoundaryConfirmation() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val candidate = boundaryCandidate(rowVisible = false)
        assertFalse(ChatSessionManagementPolicy.isCompleteBoundaryEvidence(candidate, viewport))
    }

    @Test
    fun completeSystemRowsCanProveViewportStabilityWithoutBecomingClickEligible() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val candidate = boundaryCandidate(selectionClass = "SYSTEM_ENTRY")

        assertTrue(ChatSessionManagementPolicy.isCompleteBoundaryEvidence(candidate, viewport))
        assertFalse(ChatSessionManagementPolicy.isEligible(candidate))
    }

    private fun boundaryCandidate(
        row: ScreenRect = ScreenRect(0, 400, 720, 540),
        name: ScreenRect = ScreenRect(157, 420, 300, 455),
        preview: ScreenRect = ScreenRect(157, 460, 600, 500),
        previewFullyInside: Boolean = true,
        selectionClass: String = "PRIVATE_CHAT",
        rowVisible: Boolean = true
    ) = ChatListCandidateSnapshot(
        rowIndex = 0,
        rowTarget = NodeTarget(WindowRef("com.immomo.momo", "MainActivity", 1), NodePath(listOf(1))),
        rowBounds = row,
        rowVisibleToUser = rowVisible,
        intersectsViewport = true,
        fullyInsideViewport = row.top >= 156 && row.bottom <= 1486,
        rowBoundsValid = row.right > row.left && row.bottom > row.top,
        avatarBounds = null,
        avatarPath = null,
        nameText = "Contact",
        nameSource = ChatNodeTextSource.NODE_TEXT,
        nameNodePath = NodePath(listOf(1, 0)),
        nameBounds = name,
        nameNodeVisibleToUser = true,
        nameBoundsIntersectViewport = true,
        nameBoundsFullyInsideViewport = name.top >= 156 && name.bottom <= 1486,
        nameBoundsValid = name.right > name.left && name.bottom > name.top,
        previewText = "Preview",
        previewSource = ChatNodeTextSource.NODE_TEXT,
        previewNodePath = NodePath(listOf(1, 1)),
        previewBounds = preview,
        previewNodeVisibleToUser = true,
        previewBoundsIntersectViewport = true,
        previewBoundsFullyInsideViewport = previewFullyInside,
        previewBoundsValid = preview.right > preview.left && preview.bottom > preview.top,
        timestampText = null,
        timestampSource = ChatNodeTextSource.NONE,
        timestampNodePath = null,
        timestampBounds = null,
        timestampNodeVisibleToUser = false,
        selectionClass = selectionClass
    )

    @Test
    fun candidateToStringAndStructuralKeyDoNotExposeRawSessionText() {
        val window = WindowRef("com.immomo.momo", "MainActivity", 4)
        val candidate = ChatListCandidateSnapshot(
            rowIndex = 2,
            rowTarget = NodeTarget(window, NodePath(listOf(0, 2)), expectedClassName = "LinearLayout"),
            rowBounds = ScreenRect(0, 400, 720, 540),
            rowVisibleToUser = false,
            intersectsViewport = true,
            fullyInsideViewport = true,
            rowBoundsValid = true,
            avatarBounds = ScreenRect(20, 420, 100, 500),
            avatarPath = NodePath(listOf(0, 2, 0)),
            nameText = "Private Contact Name",
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, 2, 1, 0)),
            nameBounds = ScreenRect(120, 420, 300, 455),
            nameNodeVisibleToUser = false,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = "Private message preview",
            previewSource = ChatNodeTextSource.CONTENT_DESCRIPTION,
            previewNodePath = NodePath(listOf(0, 2, 1, 1)),
            previewBounds = ScreenRect(120, 460, 600, 500),
            previewNodeVisibleToUser = false,
            previewBoundsIntersectViewport = true,
            previewBoundsFullyInsideViewport = true,
            previewBoundsValid = true,
            timestampText = "10:30",
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false
        )

        assertFalse(candidate.toString().contains("Private Contact Name"))
        assertFalse(candidate.toString().contains("Private message preview"))
        assertFalse(candidate.structuralKey().contains("Private Contact Name"))
        assertFalse(candidate.structuralKey().contains("Private message preview"))
        assertTrue(candidate.hasReadableName)
        assertTrue(candidate.hasReadablePreview)
    }
    @Test
    fun invisiblePlatformFlagDoesNotEraseGeometryEvidence() {
        // Some real-device nodes expose isVisibleToUser=false while their bounds still
        // intersect the live RecyclerView. Preserve both signals instead of hard-filtering.
        val row = ScreenRect(0, 475, 720, 585)
        val viewport = ScreenRect(0, 156, 720, 1486)
        val platformVisibleToUser = false

        assertFalse(platformVisibleToUser)
        assertTrue(MomoChatListSessionPolicy.intersectsViewport(row, viewport))
    }

    @Test
    fun missingStatusNewNodeIsRecordedAsAbsenceAndDoesNotImplyRead() {
        val candidate = createCandidate("Contact", "Preview")
        assertFalse(candidate.statusNewNodePresent)
        assertEquals(0, candidate.statusNewNodeObservationCount)
        assertEquals(ChatUnreadStatus.UNKNOWN, candidate.unreadStatus)
    }

    @Test
    fun statusNewNodePresenceParticipatesInStructuralKey() {
        val absent = createCandidate("Contact", "Preview")
        val present = createCandidate("Contact", "Preview", statusNewNodePresent = true)
        assertFalse(absent.structuralKey() == present.structuralKey())
    }


    @Test
    fun relativeMinuteTimestampObservedInMomoInboxIsAccepted() {
        val chat = MomoChatListSessionPolicy.classifyRow(
            "喵喵喵", "75.13km·？", "13分钟前", timestampBoundsValid = true,
            timestampBoundsWithinRow = true
        )
        assertEquals(MomoChatListRowClassification.PRIVATE_CHAT, chat.selectionClass)
        assertEquals("CHAT_ROW_MINIMUM_STRUCTURE_MATCHED", chat.reason)
        assertTrue(chat.selectable)
    }

    @Test
    fun relativeTimestampStillRequiresValidGeometryInsideItsRow() {
        val outside = MomoChatListSessionPolicy.classifyRow(
            "喵喵喵", "75.13km·？", "13分钟前", timestampBoundsValid = true,
            timestampBoundsWithinRow = false
        )
        val invalid = MomoChatListSessionPolicy.classifyRow(
            "喵喵喵", "75.13km·？", "13分钟前", timestampBoundsValid = false,
            timestampBoundsWithinRow = false
        )
        assertEquals(MomoChatListRowClassification.UNCLASSIFIED, outside.selectionClass)
        assertEquals("TIMESTAMP_BOUNDS_OUTSIDE_ROW", outside.reason)
        assertEquals(MomoChatListRowClassification.UNCLASSIFIED, invalid.selectionClass)
    }

    @Test
    fun statusPointDiagnosticsDoNotChangeUnreadState() {
        val plain = createCandidate("Contact", "Preview")
        val withPoint = createCandidate("Contact", "Preview").let {
            ChatListCandidateSnapshot(
                rowIndex = it.rowIndex, rowTarget = it.rowTarget, rowBounds = it.rowBounds,
                rowVisibleToUser = it.rowVisibleToUser, intersectsViewport = it.intersectsViewport,
                fullyInsideViewport = it.fullyInsideViewport, rowBoundsValid = it.rowBoundsValid,
                avatarBounds = it.avatarBounds, avatarPath = it.avatarPath,
                nameText = it.nameText, nameSource = it.nameSource, nameNodePath = it.nameNodePath,
                nameBounds = it.nameBounds, nameNodeVisibleToUser = it.nameNodeVisibleToUser,
                nameBoundsIntersectViewport = it.nameBoundsIntersectViewport,
                nameBoundsFullyInsideViewport = it.nameBoundsFullyInsideViewport,
                nameBoundsValid = it.nameBoundsValid, previewText = it.previewText,
                previewSource = it.previewSource, previewNodePath = it.previewNodePath,
                previewBounds = it.previewBounds, previewNodeVisibleToUser = it.previewNodeVisibleToUser,
                previewBoundsIntersectViewport = it.previewBoundsIntersectViewport,
                previewBoundsFullyInsideViewport = it.previewBoundsFullyInsideViewport,
                previewBoundsValid = it.previewBoundsValid, timestampText = it.timestampText,
                timestampSource = it.timestampSource, timestampNodePath = it.timestampNodePath,
                timestampBounds = it.timestampBounds, timestampNodeVisibleToUser = it.timestampNodeVisibleToUser,
                unreadStatus = ChatUnreadStatus.UNKNOWN, statusPointNodeObservationCount = 1,
                statusPointVisibleNodeCount = 0, statusPointValidBoundsNodeCount = 1,
                statusPointContainedInRowNodeCount = 1
            )
        }
        assertEquals(ChatUnreadStatus.UNKNOWN, plain.unreadStatus)
        assertEquals(ChatUnreadStatus.UNKNOWN, withPoint.unreadStatus)
        assertFalse(plain.structuralKey() == withPoint.structuralKey())
    }

    @Test
    fun candidateStructuralKeyChangesWhenNodeTextChangesWithoutExposingText() {
        val first = createCandidate("Contact A", "Preview A")
        val second = createCandidate("Contact B", "Preview B")

        assertFalse(first.structuralKey() == second.structuralKey())
        assertFalse(first.structuralKey().contains("Contact A"))
        assertFalse(second.structuralKey().contains("Preview B"))
    }


    @Test
    fun invertedBoundsAreRejectedInsteadOfCountedAsVisible() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val inverted = ScreenRect(157, 1508, 670, 1486)
        assertFalse(MomoChatListSessionPolicy.hasValidBounds(inverted))
        assertFalse(MomoChatListSessionPolicy.intersectsViewport(inverted, viewport))
        assertFalse(MomoChatListSessionPolicy.fullyInsideViewport(inverted, viewport))
    }

    @Test
    fun partialIntersectionIsNotFullViewportContainment() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val partial = ScreenRect(0, 1440, 720, 1510)
        assertTrue(MomoChatListSessionPolicy.intersectsViewport(partial, viewport))
        assertFalse(MomoChatListSessionPolicy.fullyInsideViewport(partial, viewport))
    }

    @Test
    fun fullyContainedBoundsAreDistinguishedFromIntersection() {
        val viewport = ScreenRect(0, 156, 720, 1486)
        val contained = ScreenRect(20, 200, 700, 1400)
        assertTrue(MomoChatListSessionPolicy.intersectsViewport(contained, viewport))
        assertTrue(MomoChatListSessionPolicy.fullyInsideViewport(contained, viewport))
    }


    @Test
    fun momoStatusNewInvalidOrCrossRowGeometryNeverProducesReadOrUnread() {
        val invalidBounds = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "0", contentDescription = null,
            boundsValid = false, intersectsRow = true, containedInRow = true
        )
        val crossesRow = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "2", contentDescription = null,
            boundsValid = true, intersectsRow = true, containedInRow = false
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, invalidBounds.status)
        assertEquals(ChatUnreadStatus.UNKNOWN, crossesRow.status)
        assertEquals(MomoStatusNewDisposition.UNKNOWN_INVALID_BOUNDS, invalidBounds.statusNewDisposition)
        assertEquals(MomoStatusNewDisposition.UNKNOWN_OUTSIDE_ROW, crossesRow.statusNewDisposition)
    }

    private fun createCandidate(
        name: String,
        preview: String,
        statusNewNodePresent: Boolean = false
    ) = ChatListCandidateSnapshot(
        rowIndex = 1,
        rowTarget = NodeTarget(
            WindowRef("com.immomo.momo", "MainActivity", 1),
            NodePath(listOf(0, 1)),
            expectedClassName = "LinearLayout"
        ),
        rowBounds = ScreenRect(0, 400, 720, 540),
        rowVisibleToUser = false,
        intersectsViewport = true,
        fullyInsideViewport = true,
        rowBoundsValid = true,
        avatarBounds = null,
        avatarPath = null,
        nameText = name,
        nameSource = ChatNodeTextSource.NODE_TEXT,
        nameNodePath = NodePath(listOf(0, 1, 0)),
        nameBounds = ScreenRect(120, 420, 280, 455),
        nameNodeVisibleToUser = false,
        nameBoundsIntersectViewport = true,
        nameBoundsFullyInsideViewport = true,
        nameBoundsValid = true,
        previewText = preview,
        previewSource = ChatNodeTextSource.NODE_TEXT,
        previewNodePath = NodePath(listOf(0, 1, 1)),
        previewBounds = ScreenRect(120, 460, 600, 500),
        previewNodeVisibleToUser = false,
        previewBoundsIntersectViewport = true,
        previewBoundsFullyInsideViewport = true,
        previewBoundsValid = true,
        timestampText = null,
        timestampSource = ChatNodeTextSource.NONE,
        timestampNodePath = null,
        timestampBounds = null,
        timestampNodeVisibleToUser = false,
        statusNewNodePresent = statusNewNodePresent,
        statusNewNodeObservationCount = if (statusNewNodePresent) 1 else 0
    )

    @Test
    fun chatPageStableKeyTracksRowsWithoutEmbeddingRawNamesOrPreviews() {
        val candidate = createCandidate("Sensitive Name", "Sensitive Preview")
        val snapshot = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 380,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 156, 720, 1486),
            chatListCandidates = listOf(candidate)
        )

        val stableKey = snapshot.stableKey()
        assertFalse(stableKey.contains("Sensitive Name"))
        assertFalse(stableKey.contains("Sensitive Preview"))
        assertTrue(stableKey.contains("NODE_TEXT"))
        assertNotEquals(
            "A changed row must produce a new observation key without exposing its text",
            stableKey,
            snapshot.copy(
                chatListCandidates = listOf(createCandidate("Another Name", "Another Preview"))
            ).stableKey()
        )
    }

    @Test
    fun knownSubscriptionAndGreetingEntriesAreNotPrivateChatTargets() {
        val subscription = MomoChatListSessionPolicy.classifyRow(
            "订阅内容", "动态预览内容", "10:35", timestampBoundsValid = true
        )
        val greetings = MomoChatListSessionPolicy.classifyRow(
            "收到的招呼", "有未读招呼", null, timestampBoundsValid = false
        )
        assertEquals(MomoChatListRowClassification.SYSTEM_ENTRY, subscription.selectionClass)
        assertEquals("KNOWN_SYSTEM_ENTRY_NAME", subscription.reason)
        assertEquals(MomoChatListRowClassification.SYSTEM_ENTRY, greetings.selectionClass)
        assertFalse(subscription.selectable)
        assertFalse(greetings.selectable)
    }

    @Test
    fun privateChatRequiresReadableNamePreviewAndValidTimestampEvidence() {
        val chat = MomoChatListSessionPolicy.classifyRow(
            "半颗糖", "2.82km·在想什么啊", "昨天 21:33", timestampBoundsValid = true
        )
        val uncertain = MomoChatListSessionPolicy.classifyRow(
            "收到的招呼2", "有新消息", null, timestampBoundsValid = false
        )
        assertEquals(MomoChatListRowClassification.PRIVATE_CHAT, chat.selectionClass)
        assertTrue(chat.selectable)
        assertEquals(MomoChatListRowClassification.UNCLASSIFIED, uncertain.selectionClass)
        assertFalse(uncertain.selectable)
    }


    @Test
    fun achievementNotificationPreviewIsNotClassifiedAsPrivateChat() {
        val achievement = MomoChatListSessionPolicy.classifyRow(
            "陌陌", "新成就：第一条招呼", "03:52", timestampBoundsValid = true
        )
        assertEquals(MomoChatListRowClassification.SYSTEM_ENTRY, achievement.selectionClass)
        assertEquals("KNOWN_SYSTEM_NOTIFICATION_PREVIEW", achievement.reason)
        assertFalse(achievement.selectable)
    }


    @Test
    fun readsExplicitUnreadCountFromAccessibilityLabel() {
        val evidence = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/tv_new_message_hint",
            text = null,
            contentDescription = "3条未读消息",
            boundsValid = true,
            intersectsRow = true
        )
        assertEquals(ChatUnreadStatus.UNREAD, evidence.status)
        assertEquals(3, evidence.count)
        assertEquals(ChatUnreadEvidenceSource.ACCESSIBILITY_LABEL, evidence.source)
    }

    @Test
    fun greetingEntryUnreadSummaryIsRecognizedAndPlusCountsRemainInexact() {
        val greetingSummary = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_content",
            text = "有未读招呼",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            isKnownContentField = false
        )
        val cappedCount = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/tv_unread_count",
            text = "99+",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true
        )
        assertEquals(ChatUnreadStatus.UNREAD, greetingSummary.status)
        assertEquals(ChatUnreadStatus.UNREAD, cappedCount.status)
        assertNull(cappedCount.count)
    }

    @Test
    fun semanticBadgeSupportsUnreadDotAndExactNumericCount() {
        val dot = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/iv_unread_badge",
            text = null,
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true
        )
        assertEquals(ChatUnreadStatus.UNREAD, dot.status)
        assertNull(dot.count)
        assertEquals(ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID, dot.source)

        val count = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/tv_unread_count",
            text = "7",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true
        )
        assertEquals(ChatUnreadStatus.UNREAD, count.status)
        assertEquals(7, count.count)
    }

    @Test
    fun zeroCountAndExplicitNoUnreadAreRead() {
        val zero = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/tv_unread_count",
            text = "0",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true
        )
        val label = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "",
            text = null,
            contentDescription = "没有未读消息",
            boundsValid = true,
            intersectsRow = true
        )
        assertEquals(ChatUnreadStatus.READ, zero.status)
        assertEquals(ChatUnreadStatus.READ, label.status)
    }

    @Test
    fun unavailableBadgeEvidenceRemainsUnknownAndDoesNotTreatPreviewAsUnread() {
        val noMarker = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_container",
            text = null,
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true
        )
        val preview = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_content",
            text = "我还没看这条消息",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            isKnownContentField = true
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, noMarker.status)
        assertEquals(ChatUnreadStatus.UNKNOWN, preview.status)
        assertEquals(ChatUnreadEvidenceSource.NONE, noMarker.source)
    }

    @Test
    fun invalidOrOffRowBadgeGeometryIsIgnored() {
        val evidence = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/iv_unread_badge",
            text = null,
            contentDescription = null,
            boundsValid = true,
            intersectsRow = false
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, evidence.status)
    }

    @Test
    fun contradictoryUnreadEvidenceBecomesUnknown() {
        val unread = MomoChatUnreadEvidence(
            ChatUnreadStatus.UNREAD, 2, ChatUnreadEvidenceSource.ACCESSIBILITY_LABEL
        )
        val read = MomoChatUnreadEvidence(
            ChatUnreadStatus.READ, 0, ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID
        )
        val merged = MomoChatListSessionPolicy.mergeUnreadEvidence(unread, read)
        assertEquals(ChatUnreadStatus.UNKNOWN, merged.status)
        assertEquals(ChatUnreadEvidenceSource.CONFLICTING, merged.source)
    }

    @Test
    fun ordinaryPreviewWithGenericBrandNameIsNotAutomaticallyExcluded() {
        val ordinary = MomoChatListSessionPolicy.classifyRow(
            "陌陌", "你好，在吗？", "03:52", timestampBoundsValid = true
        )
        assertEquals(MomoChatListRowClassification.PRIVATE_CHAT, ordinary.selectionClass)
        assertTrue(ordinary.selectable)
    }


    @Test
    fun momoStatusNewBadgeParsesPositiveCountsAndCappedCount() {
        listOf("1" to 1, "2" to 2).forEach { (value, expectedCount) ->
            val evidence = MomoChatListSessionPolicy.inspectUnreadEvidence(
                viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
                text = value,
                contentDescription = null,
                boundsValid = true,
                intersectsRow = true,
                containedInRow = true
            )
            assertEquals(ChatUnreadStatus.UNREAD, evidence.status)
            assertEquals(expectedCount, evidence.count)
            assertEquals(ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID, evidence.source)
        }

        val capped = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "99+",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )
        assertEquals(ChatUnreadStatus.UNREAD, capped.status)
        assertNull(capped.count)
    }

    @Test
    fun momoStatusNewBadgeZeroAndEmptyTextAreReadButUnknownValueRemainsUnknown() {
        val zero = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "0",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )
        val empty = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "",
            contentDescription = "43分钟前",
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true,
            visibleToUser = true
        )
        val twoUnread = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "2",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )
        val unexpected = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "new",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )
        val hiddenEmpty = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true,
            visibleToUser = false
        )
        val invalidEmpty = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "",
            contentDescription = null,
            boundsValid = false,
            intersectsRow = false,
            containedInRow = false,
            visibleToUser = false
        )
        assertEquals(ChatUnreadStatus.READ, zero.status)
        assertEquals(ChatUnreadStatus.READ, empty.status)
        assertEquals(ChatUnreadStatus.UNREAD, twoUnread.status)
        assertEquals(2, twoUnread.count)
        assertEquals(ChatUnreadStatus.UNKNOWN, unexpected.status)
        assertEquals(ChatUnreadStatus.UNKNOWN, hiddenEmpty.status)
        assertEquals(ChatUnreadStatus.UNKNOWN, invalidEmpty.status)
        assertEquals(MomoStatusNewDisposition.UNKNOWN_NOT_VISIBLE, hiddenEmpty.statusNewDisposition)
        assertEquals(MomoStatusNewDisposition.UNKNOWN_INVALID_BOUNDS, invalidEmpty.statusNewDisposition)
        assertEquals(MomoStatusNewDisposition.READ, zero.statusNewDisposition)
        assertEquals(MomoStatusNewDisposition.READ_EMPTY_VALUE, empty.statusNewDisposition)
        assertEquals(ChatUnreadEvidenceSource.STATUS_BADGE_EMPTY, empty.source)
        assertEquals(MomoStatusNewDisposition.UNKNOWN_NON_NUMERIC_VALUE, unexpected.statusNewDisposition)
    }

    @Test
    fun ordinaryPreviewTextCannotBecomeUnreadEvidenceButGreetingSummaryCan() {
        val ordinaryPreview = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_content",
            text = "你有未读消息吗？",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            isKnownContentField = true,
            containedInRow = true,
            visibleToUser = true
        )
        val greetingSummary = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_content",
            text = "有1个新招呼",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            isKnownContentField = false,
            containedInRow = true,
            visibleToUser = true
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, ordinaryPreview.status)
        assertEquals(ChatUnreadStatus.UNREAD, greetingSummary.status)
        assertEquals(1, greetingSummary.count)
    }

    @Test
    fun hiddenSemanticUnreadNodeCannotMarkAChatUnread() {
        val hiddenBadge = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/unread_badge",
            text = "1",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true,
            visibleToUser = false
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, hiddenBadge.status)
    }

    @Test
    fun ordinaryPrivateChatPreviewIsNotAnIndependentUnreadMarker() {
        assertFalse(
            MomoChatListSessionPolicy.isOtherUnreadMarker(
                MomoChatListRowClassification.PRIVATE_CHAT,
                "com.immomo.momo:id/chatlist_item_tv_content"
            )
        )
        assertTrue(
            MomoChatListSessionPolicy.isOtherUnreadMarker(
                MomoChatListRowClassification.SYSTEM_ENTRY,
                "com.immomo.momo:id/chatlist_item_tv_content"
            )
        )
        assertFalse(
            MomoChatListSessionPolicy.isOtherUnreadMarker(
                MomoChatListRowClassification.PRIVATE_CHAT,
                "com.immomo.momo:id/chatlist_item_tv_status_new"
            )
        )
    }

    @Test
    fun absentStatusNewMeansReadOnlyOnlyForFullyVisibleCompletePrivateRow() {
        val complete = mapOf(
            "rowVisibleToUser" to true,
            "rowFullyInsideViewport" to true,
            "nameFullyVisible" to true,
            "previewFullyVisible" to true,
            "timestampFullyVisible" to true
        )
        assertTrue(
            MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = MomoChatListRowClassification.PRIVATE_CHAT,
                statusNewNodeCount = 0,
                scanTruncated = false,
                otherUnreadMarkerObserved = false,
                rowVisibleToUser = complete.getValue("rowVisibleToUser"),
                rowFullyInsideViewport = complete.getValue("rowFullyInsideViewport"),
                nameFullyVisible = complete.getValue("nameFullyVisible"),
                previewFullyVisible = complete.getValue("previewFullyVisible"),
                timestampFullyVisible = complete.getValue("timestampFullyVisible")
            )
        )
        assertFalse(
            MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = MomoChatListRowClassification.PRIVATE_CHAT,
                statusNewNodeCount = 0,
                scanTruncated = false,
                otherUnreadMarkerObserved = false,
                rowVisibleToUser = true,
                rowFullyInsideViewport = false,
                nameFullyVisible = true,
                previewFullyVisible = true,
                timestampFullyVisible = true
            )
        )
        assertFalse(
            MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = MomoChatListRowClassification.PRIVATE_CHAT,
                statusNewNodeCount = 0,
                scanTruncated = false,
                otherUnreadMarkerObserved = false,
                rowVisibleToUser = true,
                rowFullyInsideViewport = true,
                nameFullyVisible = true,
                previewFullyVisible = false,
                timestampFullyVisible = true
            )
        )
        assertFalse(
            MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = MomoChatListRowClassification.PRIVATE_CHAT,
                statusNewNodeCount = 0,
                scanTruncated = false,
                otherUnreadMarkerObserved = false,
                rowVisibleToUser = true,
                rowFullyInsideViewport = true,
                nameFullyVisible = true,
                previewFullyVisible = true,
                timestampFullyVisible = false
            )
        )
        assertFalse(
            MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = MomoChatListRowClassification.PRIVATE_CHAT,
                statusNewNodeCount = 0,
                scanTruncated = true,
                otherUnreadMarkerObserved = false,
                rowVisibleToUser = true,
                rowFullyInsideViewport = true,
                nameFullyVisible = true,
                previewFullyVisible = true,
                timestampFullyVisible = true
            )
        )
        assertFalse(
            MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = MomoChatListRowClassification.SYSTEM_ENTRY,
                statusNewNodeCount = 0,
                scanTruncated = false,
                otherUnreadMarkerObserved = false,
                rowVisibleToUser = true,
                rowFullyInsideViewport = true,
                nameFullyVisible = true,
                previewFullyVisible = true,
                timestampFullyVisible = true
            )
        )
    }

    @Test
    fun systemStatusPointOnlyMarksTheVerifiedSubscriptionEntryAndNeverPrivateChats() {
        val confirmed = MomoChatListSessionPolicy.isConfirmedSubscriptionEntryStatusPointUnread(
            selectionClass = MomoChatListRowClassification.SYSTEM_ENTRY,
            normalizedRowName = "订阅内容",
            rowFullyVisible = true,
            statusPointNodeCount = 1,
            statusPointBoundsValid = true,
            statusPointContainedInVisibleContainer = true,
            statusPointContainerVisible = true
        )
        val privateRow = MomoChatListSessionPolicy.isConfirmedSubscriptionEntryStatusPointUnread(
            selectionClass = MomoChatListRowClassification.PRIVATE_CHAT,
            normalizedRowName = "订阅内容",
            rowFullyVisible = true,
            statusPointNodeCount = 1,
            statusPointBoundsValid = true,
            statusPointContainedInVisibleContainer = true,
            statusPointContainerVisible = true
        )
        val hiddenHolder = MomoChatListSessionPolicy.isConfirmedSubscriptionEntryStatusPointUnread(
            selectionClass = MomoChatListRowClassification.SYSTEM_ENTRY,
            normalizedRowName = "订阅内容",
            rowFullyVisible = true,
            statusPointNodeCount = 1,
            statusPointBoundsValid = true,
            statusPointContainedInVisibleContainer = true,
            statusPointContainerVisible = false
        )
        assertTrue(confirmed)
        assertFalse(privateRow)
        assertFalse(hiddenHolder)
        val pointOutsideVisibleParent = MomoChatListSessionPolicy.isConfirmedSubscriptionEntryStatusPointUnread(
            selectionClass = MomoChatListRowClassification.SYSTEM_ENTRY,
            normalizedRowName = "订阅内容",
            rowFullyVisible = true,
            statusPointNodeCount = 1,
            statusPointBoundsValid = true,
            statusPointContainedInVisibleContainer = false,
            statusPointContainerVisible = true
        )
        assertFalse(pointOutsideVisibleParent)
    }

    @Test
    fun hiddenOrCrossRowUnreadTextCannotMarkTheCurrentRowUnread() {
        val hiddenText = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/unknown_child",
            text = "有1条未读消息",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true,
            visibleToUser = false
        )
        val crossRowBadge = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/unread_badge",
            text = "1",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = false,
            visibleToUser = true
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, hiddenText.status)
        assertEquals(ChatUnreadStatus.UNKNOWN, crossRowBadge.status)
    }

    @Test
    fun receivedGreetingsPreviewSummaryWithCountIsRecognizedOnlyByExplicitGreetingPath() {
        val summary = "有1个新招呼"
        val greetingEvidence = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_content",
            text = summary,
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            isKnownContentField = false,
            containedInRow = true,
            visibleToUser = true
        )
        val ordinaryPreview = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_content",
            text = summary,
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            isKnownContentField = true,
            containedInRow = true,
            visibleToUser = true
        )

        assertEquals(ChatUnreadStatus.UNREAD, greetingEvidence.status)
        assertEquals(1, greetingEvidence.count)
        assertEquals(ChatUnreadStatus.UNKNOWN, ordinaryPreview.status)
    }

    @Test
    fun momoStatusNewPrefersNodeTextCountOverNonNumericContentDescription() {
        val evidence = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "1",
            contentDescription = "43分钟前",
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )

        assertEquals(ChatUnreadStatus.UNREAD, evidence.status)
        assertEquals(1, evidence.count)
        assertEquals(MomoStatusNewDisposition.UNREAD, evidence.statusNewDisposition)
    }


    @Test
    fun momoStatusNewNodeTextWinsOverContradictoryContentDescription() {
        val unread = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "1",
            contentDescription = "已读",
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )
        val read = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "0",
            contentDescription = "未读",
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true
        )

        assertEquals(ChatUnreadStatus.UNREAD, unread.status)
        assertEquals(ChatUnreadStatus.READ, read.status)
    }

    @Test
    fun momoStatusNewTimeLikeTextRemainsUnknownRatherThanUnread() {
        listOf("43分钟前", "17:31", "在线", "new").forEach { value ->
            val evidence = MomoChatListSessionPolicy.inspectUnreadEvidence(
                viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
                text = value,
                contentDescription = null,
                boundsValid = true,
                intersectsRow = true,
                containedInRow = true
            )
            assertEquals(ChatUnreadStatus.UNKNOWN, evidence.status)
            assertEquals(MomoStatusNewDisposition.UNKNOWN_NON_NUMERIC_VALUE, evidence.statusNewDisposition)
        }
    }

    @Test
    fun momoStatusNewBadgeMustBeFullyContainedByItsOwningRow() {
        val crossingRow = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "1",
            contentDescription = null,
            boundsValid = true,
            intersectsRow = true,
            containedInRow = false
        )
        val invalidBounds = MomoChatListSessionPolicy.inspectUnreadEvidence(
            viewId = "com.immomo.momo:id/chatlist_item_tv_status_new",
            text = "1",
            contentDescription = null,
            boundsValid = false,
            intersectsRow = true,
            containedInRow = true
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, crossingRow.status)
        assertEquals(ChatUnreadStatus.UNKNOWN, invalidBounds.status)
    }


    @Test
    fun knownMomoFeatureRowsAreSystemEntriesNotPrivateChats() {
        listOf("谁看过我", "超级抢车位", "订阅内容", "猜你喜欢", "收到的招呼").forEach { name ->
            val result = MomoChatListSessionPolicy.classifyRow(
                name, "动态内容", "03:52", timestampBoundsValid = true
            )
            assertEquals(MomoChatListRowClassification.SYSTEM_ENTRY, result.selectionClass)
            assertFalse(result.selectable)
        }
    }

    @Test
    fun arbitraryTextInTimestampSlotDoesNotQualifyAsPrivateChat() {
        val result = MomoChatListSessionPolicy.classifyRow(
            "普通联系人", "你好", "2.82km·在想什么啊", timestampBoundsValid = true
        )
        assertEquals(MomoChatListRowClassification.UNCLASSIFIED, result.selectionClass)
        assertEquals("TIMESTAMP_FORMAT_NOT_RECOGNIZED", result.reason)
        assertFalse(result.selectable)
    }

    @Test
    fun observedMomoTimestampFormatsRemainEligible() {
        listOf("03:52", "昨天 21:02", "前天21:32", "11-09", "11月9日", "周一 12:30").forEach { time ->
            val result = MomoChatListSessionPolicy.classifyRow(
                "普通联系人", "你好", time, timestampBoundsValid = true
            )
            assertEquals("timestamp=$time", MomoChatListRowClassification.PRIVATE_CHAT, result.selectionClass)
        }
    }

    @Test
    fun avatarHashDistanceIsOnlyAVisualSimilarityMetric() {
        assertEquals(0, MomoChatListSessionPolicy.avatarHashHammingDistance(
            "260bca8e8cab6126", "260bca8e8cab6126"
        ))
        assertEquals(1, MomoChatListSessionPolicy.avatarHashHammingDistance(
            "0000000000000000", "0000000000000001"
        ))
        assertNull(MomoChatListSessionPolicy.avatarHashHammingDistance("not-a-hash", "0000000000000000"))
    }

    @Test
    fun timestampNodeMustBelongToItsSessionRow() {
        val result = MomoChatListSessionPolicy.classifyRow(
            "普通联系人", "你好", "03:52",
            timestampBoundsValid = true,
            timestampBoundsWithinRow = false
        )
        assertEquals(MomoChatListRowClassification.UNCLASSIFIED, result.selectionClass)
        assertEquals("TIMESTAMP_BOUNDS_OUTSIDE_ROW", result.reason)
        assertFalse(result.selectable)
    }

    @Test
    fun readEvidenceCanBeAuditedWithoutPersistingRawBadgeText() {
        val evidence = ChatListUnreadNodeEvidence(
            viewIdResourceName = "com.immomo.momo:id/chatlist_item_tv_status_new",
            status = ChatUnreadStatus.READ,
            count = 0,
            source = ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID,
            bounds = ScreenRect(610, 420, 640, 450),
            boundsValid = true,
            intersectsRow = true,
            containedInRow = true,
            visibleToUser = true,
            statusNewDisposition = "READ"
        )

        assertEquals(ChatUnreadStatus.READ, evidence.status)
        assertEquals(0, evidence.count)
        assertTrue(evidence.containedInRow)
        assertTrue(evidence.boundsValid)
        assertFalse(evidence.toString().contains("已读"))
        assertFalse(evidence.toString().contains("未读"))
    }

}
