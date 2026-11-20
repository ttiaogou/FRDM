package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import org.junit.Assert.*
import org.junit.Test

class DraftHistoryMergerTest {
    private val visible = ConversationContext(
        "window|visit=v", "window|visit=v",
        listOf(AiModelMessage(AiModelMessage.Role.USER, "最新")), 0, 0, 0, true, true)

    private fun stored(id: String, text: String, direction: String, time: Long) = StoredConversationMessage(
        recordId = id, contactId = "c1", conversationId = "momo-direct-c1", messageFingerprint = id,
        direction = direction, type = "TEXT", content = text, contentQuality = "VERIFIED",
        directionEvidence = "BUBBLE_GEOMETRY", bounds = ScreenRect(0, time.toInt(), 10, time.toInt() + 1),
        identityConfidence = "PLATFORM_ID_VERIFIED", firstObservedAtMillis = time,
        lastObservedAtMillis = time, observationCount = 1)

    @Test fun missingAccountEvidenceDoesNotAddHistory() {
        val result = DraftHistoryMerger.merge(visible, emptyBatch(), null)
        assertEquals(visible.messages, result.messages)
    }

    @Test fun historyIsAddedOnlyBeforeCurrentVisibleAnchor() {
        val batch = emptyBatch().copy(messages = listOf(
            ChatMessage("new", ChatMessageDirection.INCOMING, ChatMessageType.TEXT, "最新",
                ScreenRect(0, 20, 10, 21), DirectionEvidence.BUBBLE_GEOMETRY, MessageContentQuality.VERIFIED)))
        val history = VerifiedDraftHistory("account-1", "c1", "momo-direct-c1", listOf(
            stored("old", "旧消息", "INCOMING", 1), stored("new", "最新", "INCOMING", 2)))
        val result = DraftHistoryMerger.merge(visible, batch, history)
        assertEquals(listOf("旧消息", "最新"), result.messages.map { it.content })
    }

    @Test fun unanchoredHistoryIsRejectedToAvoidCrossSessionMerge() {
        val history = VerifiedDraftHistory("account-1", "c1", "momo-direct-c1", listOf(stored("other", "别的会话", "INCOMING", 1)))
        val result = DraftHistoryMerger.merge(visible, emptyBatch(), history)
        assertEquals(visible.messages, result.messages)
    }

    @Test fun laterVisibleRowCanAnchorWhenTheFirstVisibleRowIsNew() {
        val batch = emptyBatch().copy(messages = listOf(
            ChatMessage("new-row", ChatMessageDirection.INCOMING, ChatMessageType.TEXT, "刚出现",
                ScreenRect(0, 10, 10, 11), DirectionEvidence.BUBBLE_GEOMETRY, MessageContentQuality.VERIFIED),
            ChatMessage("anchor-row", ChatMessageDirection.INCOMING, ChatMessageType.TEXT, "已有锚点",
                ScreenRect(0, 20, 10, 21), DirectionEvidence.BUBBLE_GEOMETRY, MessageContentQuality.VERIFIED)
        ))
        val history = VerifiedDraftHistory("account-1", "c1", "momo-direct-c1", listOf(
            stored("old", "更早消息", "INCOMING", 1),
            stored("anchor-row", "已有锚点", "INCOMING", 2)
        ))

        val result = DraftHistoryMerger.merge(visible, batch, history)

        assertEquals(listOf("更早消息", "最新"), result.messages.map { it.content })
    }

    @Test fun ocrCorrectionKeepsTheSameLogicalHistoryAnchor() {
        val corrected = ChatMessage(
            id = "logical-1",
            direction = ChatMessageDirection.INCOMING,
            type = ChatMessageType.TEXT,
            content = "修正后的文字",
            bounds = ScreenRect(0, 20, 10, 21),
            directionEvidence = DirectionEvidence.BUBBLE_GEOMETRY,
            contentQuality = MessageContentQuality.VERIFIED,
            logicalMessageId = "logical-1"
        )
        val history = VerifiedDraftHistory("account-1", "c1", "momo-direct-c1", listOf(
            stored("old", "更早消息", "INCOMING", 1),
            stored("logical-1", "初次 OCR 文字", "INCOMING", 2)
        ))

        val result = DraftHistoryMerger.merge(visible, emptyBatch().copy(messages = listOf(corrected)), history)

        assertEquals(listOf("更早消息", "最新"), result.messages.map { it.content })
    }

    @Test fun resolvedLocalContactHistoryLoadsWithoutVisibleOverlap() {
        val history = VerifiedDraftHistory("", "c1", "momo-direct-c1", listOf(
            stored("old", "更早消息", "INCOMING", 1),
            stored("future", "稍后消息", "INCOMING", 20)), lookupSource = "LOCAL_PLATFORM_CONTACT")
        val result = DraftHistoryMerger.merge(visible, emptyBatch().copy(capturedAtMillis = 10), history)
        assertEquals(listOf("更早消息", "最新"), result.messages.map { it.content })
    }

    @Test fun resolvedHistoryDoesNotImportOtherContactsOrUnverifiedMessages() {
        val wrongContact = stored("other", "其他联系人", "INCOMING", 1).copy(contactId = "c2")
        val unverified = stored("uncertain", "不可信文字", "INCOMING", 2).copy(contentQuality = "UNVERIFIED")
        val history = VerifiedDraftHistory("", "c1", "momo-direct-c1", listOf(wrongContact, unverified),
            lookupSource = "LOCAL_PLATFORM_CONTACT")
        assertEquals(visible.messages, DraftHistoryMerger.merge(visible, emptyBatch().copy(capturedAtMillis = 10), history).messages)
    }

    private fun emptyBatch() = ChatMessageBatch(
        MessageReadStatus.SUCCESS, "window", emptyList(), 0, 0, false, 1L)
}
