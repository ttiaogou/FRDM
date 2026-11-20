package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionManagementPolicyTest {
    private fun candidate(
        rowIndex: Int,
        name: String,
        preview: String = "preview",
        fullyInside: Boolean = true,
        rowValid: Boolean = true,
        nameBoundsValid: Boolean = true,
        selectionClass: String = "PRIVATE_CHAT",
        selectionReason: String = "TEST",
        rowTop: Int = rowIndex * 100,
        unreadStatus: ChatUnreadStatus = ChatUnreadStatus.UNREAD
    ) = ChatListCandidateSnapshot(
        rowIndex = rowIndex,
        rowTarget = NodeTarget(WindowRef("com.immomo.momo", "Main", 1), NodePath(listOf(rowIndex))),
        rowBounds = ScreenRect(0, rowTop, 720, rowTop + 90),
        rowVisibleToUser = false,
        intersectsViewport = true,
        fullyInsideViewport = fullyInside,
        rowBoundsValid = rowValid,
        avatarBounds = null,
        avatarPath = null,
        nameText = name,
        nameSource = ChatNodeTextSource.NODE_TEXT,
        nameNodePath = NodePath(listOf(rowIndex, 0)),
        nameBounds = ScreenRect(100, rowTop, 300, rowTop + 30),
        nameNodeVisibleToUser = false,
        nameBoundsIntersectViewport = true,
        nameBoundsFullyInsideViewport = true,
        nameBoundsValid = nameBoundsValid,
        previewText = preview,
        previewSource = ChatNodeTextSource.NODE_TEXT,
        previewNodePath = null,
        previewBounds = ScreenRect(100, rowTop + 35, 600, rowTop + 70),
        previewNodeVisibleToUser = false,
        previewBoundsIntersectViewport = true,
        previewBoundsFullyInsideViewport = true,
        previewBoundsValid = true,
        timestampText = "10:20",
        timestampSource = ChatNodeTextSource.NODE_TEXT,
        timestampNodePath = null,
        timestampBounds = null,
        timestampNodeVisibleToUser = false,
        selectionClass = selectionClass,
        selectionReason = selectionReason,
        unreadStatus = unreadStatus
    )

    @Test
    fun selectsBottommostEligibleUnattemptedCandidate() {
        val bottom = candidate(1, "B")
        val top = candidate(0, "A")
        assertEquals(bottom, ChatSessionManagementPolicy.selectNext(listOf(top, bottom), emptySet()))
        assertEquals(top, ChatSessionManagementPolicy.selectNext(
            listOf(bottom, top), setOf(ChatSessionManagementPolicy.stableSessionKey(bottom))
        ))
    }

    @Test
    fun physicalScreenPositionWinsOverAccessibilityChildIndex() {
        val childIndexZeroAtBottom = candidate(0, "Bottom", rowTop = 900)
        val childIndexNineAtTop = candidate(9, "Top", rowTop = 100)
        assertEquals(
            listOf(childIndexZeroAtBottom, childIndexNineAtTop),
            ChatSessionManagementPolicy.orderBottomToTop(listOf(childIndexNineAtTop, childIndexZeroAtBottom))
        )
        assertEquals(
            childIndexZeroAtBottom,
            ChatSessionManagementPolicy.selectNext(listOf(childIndexNineAtTop, childIndexZeroAtBottom), emptySet())
        )
    }

    @Test
    fun onlyConfirmedUnreadRowsAreSelectedAndRemainBottomToTop() {
        val bottomUnknown = candidate(
            2, "Unknown bottom", rowTop = 900, unreadStatus = ChatUnreadStatus.UNKNOWN
        )
        val topUnread = candidate(1, "Unread top", rowTop = 100, unreadStatus = ChatUnreadStatus.UNREAD)
        val bottomUnread = candidate(3, "Unread bottom", rowTop = 700, unreadStatus = ChatUnreadStatus.UNREAD)
        val read = candidate(4, "Read", rowTop = 1100, unreadStatus = ChatUnreadStatus.READ)
        val candidates = listOf(read, bottomUnknown, topUnread, bottomUnread)

        assertEquals(
            bottomUnread,
            ChatSessionManagementPolicy.selectNext(candidates, emptySet())
        )
        assertEquals(
            topUnread,
            ChatSessionManagementPolicy.selectNext(
                candidates, setOf(ChatSessionManagementPolicy.stableSessionKey(bottomUnread))
            )
        )
        assertNull(ChatSessionManagementPolicy.selectNext(listOf(read, bottomUnknown), emptySet()))
    }

    @Test
    fun unknownUnreadStateNeverQualifiesAsClickPermission() {
        val unknownBottom = candidate(8, "Unknown bottom", rowTop = 1200, unreadStatus = ChatUnreadStatus.UNKNOWN)
        val confirmedUnreadTop = candidate(2, "Unread top", rowTop = 300, unreadStatus = ChatUnreadStatus.UNREAD)

        assertEquals(
            confirmedUnreadTop,
            ChatSessionManagementPolicy.selectNext(listOf(unknownBottom, confirmedUnreadTop), emptySet())
        )
        assertNull(ChatSessionManagementPolicy.selectNext(listOf(unknownBottom), emptySet()))
    }

    @Test
    fun partiallyVisiblePreviewBlocksClickEvenWhenRowAndNameAppearValid() {
        val clippedPreview = candidate(0, "Clipped preview").let {
            ChatListCandidateSnapshot(
                rowIndex = it.rowIndex,
                rowTarget = it.rowTarget,
                rowBounds = it.rowBounds,
                rowVisibleToUser = it.rowVisibleToUser,
                intersectsViewport = it.intersectsViewport,
                fullyInsideViewport = it.fullyInsideViewport,
                rowBoundsValid = it.rowBoundsValid,
                avatarBounds = it.avatarBounds,
                avatarPath = it.avatarPath,
                nameText = it.nameText,
                nameSource = it.nameSource,
                nameNodePath = it.nameNodePath,
                nameBounds = it.nameBounds,
                nameNodeVisibleToUser = it.nameNodeVisibleToUser,
                nameBoundsIntersectViewport = it.nameBoundsIntersectViewport,
                nameBoundsFullyInsideViewport = it.nameBoundsFullyInsideViewport,
                nameBoundsValid = it.nameBoundsValid,
                previewText = it.previewText,
                previewSource = it.previewSource,
                previewNodePath = it.previewNodePath,
                previewBounds = ScreenRect(100, 450, 600, 510),
                previewNodeVisibleToUser = it.previewNodeVisibleToUser,
                previewBoundsIntersectViewport = true,
                previewBoundsFullyInsideViewport = false,
                previewBoundsValid = true,
                timestampText = it.timestampText,
                timestampSource = it.timestampSource,
                timestampNodePath = it.timestampNodePath,
                timestampBounds = it.timestampBounds,
                timestampNodeVisibleToUser = it.timestampNodeVisibleToUser,
                unreadStatus = ChatUnreadStatus.UNREAD
            )
        }
        assertFalse(ChatSessionManagementPolicy.isEligible(clippedPreview))
        assertNull(ChatSessionManagementPolicy.selectNext(listOf(clippedPreview), emptySet()))
    }

    @Test
    fun rejectsPartialOrInvalidRows() {
        assertFalse(ChatSessionManagementPolicy.isEligible(candidate(0, "partial", fullyInside = false)))
        assertFalse(ChatSessionManagementPolicy.isEligible(candidate(0, "bad", rowValid = false)))
        assertFalse(ChatSessionManagementPolicy.isEligible(candidate(0, "bad-name", nameBoundsValid = false)))
        assertNull(ChatSessionManagementPolicy.selectNext(
            listOf(candidate(0, "partial", fullyInside = false)), emptySet()
        ))
    }

    @Test
    fun systemAndUnclassifiedRowsAreNotEligibleForNavigation() {
        val system = candidate(0, "订阅内容", selectionClass = "SYSTEM_ENTRY")
        val unknown = candidate(1, "收到的招呼", selectionClass = "UNCLASSIFIED")
        assertFalse(ChatSessionManagementPolicy.isEligible(system))
        assertFalse(ChatSessionManagementPolicy.isEligible(unknown))
        assertNull(ChatSessionManagementPolicy.selectNext(listOf(system, unknown), emptySet()))
    }

    @Test
    fun systemEntryUnreadAndUnknownBadgesNeverEnterPrivateChatEligibility() {
        val systemUnread = candidate(
            0, "谁看过我", selectionClass = "SYSTEM_ENTRY", unreadStatus = ChatUnreadStatus.UNREAD
        )
        val systemUnknown = candidate(
            1, "猜你喜欢", selectionClass = "SYSTEM_ENTRY", unreadStatus = ChatUnreadStatus.UNKNOWN
        )
        val otherSystem = candidate(
            2, "超级抢车位", selectionClass = "SYSTEM_ENTRY", unreadStatus = ChatUnreadStatus.UNREAD
        )

        listOf(systemUnread, systemUnknown, otherSystem).forEach {
            assertFalse(ChatSessionManagementPolicy.isEligible(it))
        }
        assertNull(ChatSessionManagementPolicy.selectNext(
            listOf(systemUnread, systemUnknown, otherSystem), emptySet()
        ))
    }

    @Test
    fun normalizesCompatibilityWhitespaceAndInvisibleFormattingWithoutDroppingVisibleSymbols() {
        assertEquals(
            "alice 😊",
            ChatSessionManagementPolicy.normalizeSessionTitle("Ａｌｉｃｅ\u200B　😊\uFE0F")
        )
        assertEquals(
            ChatSessionManagementPolicy.normalizeSessionTitle("Ａｌｉｃｅ\u200B　😊\uFE0F"),
            ChatSessionManagementPolicy.normalizeSessionTitle("Alice 😊")
        )
    }

    @Test
    fun sessionKeyUsesSameConservativeTitleNormalizationAsHeaderValidation() {
        assertEquals(
            ChatSessionManagementPolicy.stableSessionKey(candidate(0, "Ａｌｉｃｅ\u200B　😊\uFE0F")),
            ChatSessionManagementPolicy.stableSessionKey(candidate(0, "Alice 😊"))
        )
        assertFalse(
            ChatSessionManagementPolicy.normalizeSessionTitle("Alice 😊") ==
                ChatSessionManagementPolicy.normalizeSessionTitle("Alice 😃")
        )
    }

    @Test
    fun verifiedPlatformIdentityOverridesNicknameForCrossSnapshotSessionKeys() {
        val firstSnapshot = candidate(0, "张三").apply {
            contactIdentity = ContactRowIdentity(
                snapshotId = "snapshot-1", temporaryUserId = "snapshot-1-U001",
                contactId = "contact-platform-101",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
        }
        val samePersonLater = candidate(2, "张三").apply {
            contactIdentity = ContactRowIdentity(
                snapshotId = "snapshot-2", temporaryUserId = "snapshot-2-U003",
                contactId = "contact-platform-101",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
        }
        val otherPersonSameNickname = candidate(3, "张三").apply {
            contactIdentity = ContactRowIdentity(
                snapshotId = "snapshot-2", temporaryUserId = "snapshot-2-U004",
                contactId = "contact-platform-202",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
        }

        assertEquals(
            ChatSessionManagementPolicy.stableSessionKey(firstSnapshot),
            ChatSessionManagementPolicy.stableSessionKey(samePersonLater)
        )
        assertNotEquals(
            ChatSessionManagementPolicy.stableSessionKey(firstSnapshot),
            ChatSessionManagementPolicy.stableSessionKey(otherPersonSameNickname)
        )
        assertEquals(
            ChatSessionManagementPolicy.stableSessionKey(firstSnapshot),
            ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact("contact-platform-101")
        )
        assertTrue(ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(" ").isEmpty())
    }

    @Test
    fun titleHashKeyMatchesCandidateKeyWithoutExposingTheDisplayName() {
        val candidateKey = ChatSessionManagementPolicy.stableSessionKey(candidate(0, "Ａｌｉｃｅ\u200B 😊"))
        val titleKey = ChatSessionManagementPolicy.stableSessionKeyForTitle("Alice 😊")
        assertEquals(candidateKey, titleKey)
        assertTrue(titleKey.isNotBlank())
        assertTrue(ChatSessionManagementPolicy.stableSessionKeyForTitle(" ").isEmpty())
        assertFalse(titleKey.contains("alice", ignoreCase = true))
    }

    @Test
    fun titleProfileCountsCodePointsAndNormalizationWithoutExposingText() {
        val profile = ChatSessionManagementPolicy.profileSessionTitle("  Ａlice\u200B 😊  ")
        assertEquals(13, profile.rawUtf16Length)
        assertEquals("alice 😊", ChatSessionManagementPolicy.normalizeSessionTitle("  Ａlice\u200B 😊  "))
        assertEquals(1, profile.formatCodePointCount)
        assertEquals(2, profile.leadingWhitespaceCodePointCount)
        assertEquals(2, profile.trailingWhitespaceCodePointCount)
        assertEquals(1, profile.symbolCodePointCount)
        assertTrue(profile.normalizedChanged)
        assertTrue(profile.unicodeScriptCounts.containsKey("LATIN"))
    }

    @Test
    fun matchesExactMomoConversationTitleTemplateWithoutFuzzyMatching() {
        assertEquals(
            ChatHeaderMatchMode.EXACT_NAME,
            ChatSessionManagementPolicy.matchChatHeader("Alice", "alice")
        )
        assertEquals(
            ChatHeaderMatchMode.WRAPPED_CONVERSATION_TITLE,
            ChatSessionManagementPolicy.matchChatHeader("权", "与权对话")
        )
        assertEquals(
            ChatHeaderMatchMode.WRAPPED_CONVERSATION_TITLE,
            ChatSessionManagementPolicy.matchChatHeader("半颗糖💗", "与半颗糖💗对话")
        )

        assertTrue(ChatSessionManagementPolicy.matchChatHeader("权", "与权聊天") == null)
        assertTrue(ChatSessionManagementPolicy.matchChatHeader("权", "与权对话2") == null)
        assertTrue(ChatSessionManagementPolicy.matchChatHeader("权", "与权儿对话") == null)
        assertTrue(ChatSessionManagementPolicy.matchChatHeader("", "与权对话") == null)
        assertTrue(ChatSessionManagementPolicy.matchChatHeader("权", "") == null)
    }

    @Test
    fun titleComparisonReportsShapeOfSuffixMismatchWithoutReturningText() {
        val comparison = ChatSessionManagementPolicy.compareSessionTitles("Alice", "Alice123")
        assertFalse(comparison.normalizedEqual)
        assertEquals(5, comparison.commonPrefixCodePointCount)
        assertEquals(0, comparison.commonSuffixCodePointCount)
        assertEquals(0, comparison.expectedUnmatchedCodePointCount)
        assertEquals(3, comparison.actualUnmatchedCodePointCount)
    }

    @Test
    fun sameNormalizedDisplayNameRowsAreBlockedAsAmbiguousIdentity() {
        val firstAlice = candidate(0, "Ａlice")
        val secondAlice = candidate(1, "alice")
        val bob = candidate(2, "Bob")

        val ambiguousKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(
            listOf(firstAlice, secondAlice, bob)
        )
        assertEquals(setOf(ChatSessionManagementPolicy.stableSessionKey(firstAlice)), ambiguousKeys)
        assertEquals(
            bob,
            ChatSessionManagementPolicy.selectNext(
                listOf(firstAlice, secondAlice, bob), emptySet()
            )
        )
        assertNull(
            ChatSessionManagementPolicy.selectNext(
                listOf(firstAlice, secondAlice), emptySet()
            )
        )
    }

    @Test
    fun allThreeSameNameContactsAreBlockedRatherThanSelectingTheFirst() {
        val first = candidate(0, "乐乐")
        val second = candidate(1, "乐乐")
        val third = candidate(2, "乐乐")
        val candidates = listOf(first, second, third)

        val blockedKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(candidates)

        assertEquals(setOf(ChatSessionManagementPolicy.stableSessionKey(first)), blockedKeys)
        assertNull(
            ChatSessionManagementPolicy.selectNext(
                candidates, attemptedKeys = emptySet(), blockedIdentityKeys = blockedKeys
            )
        )
    }

    @Test
    fun blockedIdentityRemainsExcludedEvenWhenOnlyOneMatchingRowIsVisibleLater() {
        val firstAlice = candidate(0, "Alice")
        val laterAlice = candidate(4, "ＡＬＩＣＥ")
        assertNull(
            ChatSessionManagementPolicy.selectNext(
                listOf(laterAlice),
                attemptedKeys = emptySet(),
                blockedIdentityKeys = setOf(ChatSessionManagementPolicy.stableSessionKey(firstAlice))
            )
        )
    }

    @Test
    fun previewChangesDoNotCauseSameContactToBeSelectedAgain() {
        val first = candidate(0, "Alice", preview = "new message")
        val updated = candidate(0, "Alice", preview = "another preview")
        assertEquals(
            ChatSessionManagementPolicy.stableSessionKey(first),
            ChatSessionManagementPolicy.stableSessionKey(updated)
        )
        assertNull(ChatSessionManagementPolicy.selectNext(
            listOf(updated), setOf(ChatSessionManagementPolicy.stableSessionKey(first))
        )) // The candidate is already in the attempted set.
    }
}
