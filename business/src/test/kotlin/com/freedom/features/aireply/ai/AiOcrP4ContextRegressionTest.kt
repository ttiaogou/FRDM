package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import org.junit.Assert.*
import org.junit.Test

/** P4 contract tests across current OCR, stored history, and actual model-provider request. */
class AiOcrP4ContextRegressionTest {
    private val first = OcrContentRect(0, 0, 160, 27)
    private val second = OcrContentRect(0, 45, 160, 73)

    private class CapturingProvider : AiModelProvider {
        var calls = 0
        var lastRequest: AiModelRequest? = null
        override fun generate(request: AiModelRequest): AiModelResult {
            calls++
            lastRequest = request
            return AiModelResult(
                """{"action":"REPLY","reason":"OK","reasonDetail":"安全文本","draft":"好呀"}""",
                "test-provider", "test-model"
            )
        }
    }

    private fun rawMessage(raw: String, row: Int = 3,
        quality: MessageContentQuality = MessageContentQuality.AMBIGUOUS
    ) = ChatMessage(
        id = "same-logical-id", direction = ChatMessageDirection.INCOMING,
        type = ChatMessageType.TEXT, content = raw,
        bounds = ScreenRect(0, 110, 720, 170),
        directionEvidence = DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
        contentQuality = quality,
        logicalMessageId = "same-logical-id", observationRowIndex = row
    )

    private fun partialBatch(): Pair<ChatMessageBatch, ChatMessage> {
        val marker = OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY
        val raw = "今晚有空吗\n${marker} 在吗a"
        val tokens = listOf(
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.RECOGNIZED, "今晚有空吗",
                OcrTokenSource.OCR_LINE, 0, first),
            OcrContentToken(OcrContentKind.PLACEHOLDER, OcrTokenStatus.UNVERIFIED, marker,
                OcrTokenSource.VISUAL_GUARD, 1, second),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.AMBIGUOUS, " 在吗a",
                OcrTokenSource.OCR_LINE, 1, second)
        )
        val projection = OcrTextProjection("今晚有空吗", OcrTextProjectionStatus.PARTIAL,
            listOf(0), listOf(1))
        val message = rawMessage(raw)
        return ChatMessageBatch(
            status = MessageReadStatus.SUCCESS, windowSessionKey = "p4-window",
            messages = listOf(message), scannedRowCount = 1, candidateRowCount = 1,
            truncated = false, capturedAtMillis = 100L,
            ocrTokensByRowIndex = mapOf(3 to tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        ) to message
    }

    private fun oldRecord(id: String, content: String, time: Long, direction: String = "INCOMING") =
        StoredConversationMessage(
            recordId = id, contactId = "contact-1",
            conversationId = "momo-direct-contact-1", messageFingerprint = id,
            direction = direction, type = "TEXT", content = content, contentQuality = "VERIFIED",
            directionEvidence = "BUBBLE_GEOMETRY",
            bounds = ScreenRect(0, time.toInt(), 100, time.toInt() + 15),
            identityConfidence = "PLATFORM_ID_VERIFIED",
            firstObservedAtMillis = time, lastObservedAtMillis = time, observationCount = 1
        )

    @Test fun partialOcrCurrentMessageAndLegacyHistoryAreBothCleanAtFinalProviderBoundary() {
        val (batch, latest) = partialBatch()
        val visible = VisibleDraftContext.build(batch)!!
        assertEquals("今晚有空吗", visible.messages.single().content)
        val history = VerifiedDraftHistory(
            accountKey = "", contactId = "contact-1",
            conversationId = "momo-direct-contact-1",
            lookupSource = "LOCAL_PLATFORM_CONTACT",
            messages = listOf(
                oldRecord("old-1", "昨天你好😊", 1L),
                oldRecord("old-bad", "历史[表情/图形未识别]", 2L),
                oldRecord("old-2", "晚点再聊", 3L, "OUTGOING")
            )
        )
        val merged = DraftHistoryMerger.merge(visible, batch, history)
        assertEquals(listOf("昨天你好", "晚点再聊", "今晚有空吗"),
            merged.messages.map { it.content })
        assertEquals(listOf(AiModelMessage.Role.USER, AiModelMessage.Role.ASSISTANT,
            AiModelMessage.Role.USER), merged.messages.map { it.role })

        val provider = CapturingProvider()
        val result = AiReplyDecisionEngine(provider, AiModelCloudConsent { true }).decide(
            AiReplyDecisionInput(merged, false, requestId = "p4-integrated")
        )
        assertTrue(result is AiReplyEvaluation.Model)
        assertEquals(1, provider.calls)
        val sent = provider.lastRequest!!.conversation
        assertEquals(listOf("昨天你好", "晚点再聊", "今晚有空吗"), sent.map { it.content })
        assertFalse(sent.any { it.content.contains("[表情/图形未识别]") ||
            it.content.contains("😊") || it.content.contains("在吗a") })
        assertEquals("same-logical-id", latest.stableLogicalMessageId)
        assertEquals(batch.messages.single().effectiveContentRevision, latest.effectiveContentRevision)
        assertTrue(latest.content!!.contains(OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY))
        assertEquals("历史[表情/图形未识别]", history.messages[1].content)
    }

    @Test fun unusableLatestEmojiDoesNotTriggerReplyToOlderVerifiedIncoming() {
        val (batch, _) = partialBatch()
        val earlier = rawMessage("之前问候", row = 1,
            quality = MessageContentQuality.VERIFIED).copy(
            id = "older", logicalMessageId = "older",
            bounds = ScreenRect(0, 0, 720, 50)
        )
        val latest = rawMessage("😊", row = 4,
            quality = MessageContentQuality.AMBIGUOUS).copy(
            id = "latest", logicalMessageId = "latest",
            bounds = ScreenRect(0, 210, 720, 260)
        )
        val state = batch.copy(messages = listOf(earlier, latest))
        assertFalse(VisibleDraftContext.assess(state).accepted)
        assertNull(VisibleDraftContext.build(state))
    }

    @Test fun invalidOcrBatchEvidenceCannotBecomeVerifiedDraftThroughLegacyFallback() {
        val (batch, message) = partialBatch()
        assertNotNull(VisibleDraftContext.build(batch))
        assertNull(VisibleDraftContext.build(batch.copy(
            ocrTextProjectionsByRowIndex = emptyMap()
        )))
        assertNull(VisibleDraftContext.build(batch.copy(
            messages = listOf(message.copy(observationRowIndex = 88))
        )))
        assertNull(VisibleDraftContext.build(batch.copy(
            messages = listOf(message.copy(content = "伪造新内容"))
        )))
    }

    @Test fun outboundRejectsLatestVisualNoiseEvenWhenOlderIncomingWasVerified() {
        val provider = CapturingProvider()
        val current = ConversationContext(
            contactId = "contact-1", conversationId = "momo-direct-contact-1",
            messages = listOf(
                AiModelMessage(AiModelMessage.Role.USER, "昨天好"),
                AiModelMessage(AiModelMessage.Role.USER,
                    "在吗${OcrUncertaintyMarkers.UNKNOWN_VISUAL_SYMBOL}")
            ),
            insertedCount = 0, duplicateCount = 0, rejectedCount = 0,
            hasVerifiedIncoming = true, currentWindowDraft = true
        )
        val outcome = AiReplyDecisionEngine(provider, AiModelCloudConsent { true }).decide(
            AiReplyDecisionInput(current, false, requestId = "p4-stale-last")
        )
        assertTrue(outcome is AiReplyEvaluation.Execution)
        assertEquals(AiReplyExecutionStatus.MESSAGE_EVIDENCE_UNAVAILABLE,
            (outcome as AiReplyEvaluation.Execution).status)
        assertEquals(0, provider.calls)
    }

    @Test fun outgoingLatestCannotTriggerNewReplyEvenIfOlderIncomingIsValid() {
        val provider = CapturingProvider()
        val current = ConversationContext(
            contactId = "contact-1", conversationId = "momo-direct-contact-1",
            messages = listOf(
                AiModelMessage(AiModelMessage.Role.USER, "你好"),
                AiModelMessage(AiModelMessage.Role.ASSISTANT, "好的")
            ),
            insertedCount = 0, duplicateCount = 0, rejectedCount = 0,
            hasVerifiedIncoming = true, currentWindowDraft = true
        )
        val outcome = AiReplyDecisionEngine(provider, AiModelCloudConsent { true }).decide(
            AiReplyDecisionInput(current, false, requestId = "p4-no-own-reply")
        )
        assertTrue(outcome is AiReplyEvaluation.Execution)
        assertEquals(AiReplyExecutionStatus.MESSAGE_EVIDENCE_UNAVAILABLE,
            (outcome as AiReplyEvaluation.Execution).status)
        assertEquals(0, provider.calls)
    }

    @Test fun truncatedOrUncertainDirectionBatchesCannotGenerateModelContext() {
        val (batch, current) = partialBatch()
        assertNull(VisibleDraftContext.build(batch.copy(truncated = true)))
        assertNull(VisibleDraftContext.build(batch.copy(windowSessionKey = null)))
        assertNull(VisibleDraftContext.build(batch.copy(coordinateSpaceReliable = false)))
        assertNull(VisibleDraftContext.build(batch.copy(
            messages = listOf(current.copy(direction = ChatMessageDirection.UNKNOWN))
        )))
        assertNull(VisibleDraftContext.build(batch.copy(
            messages = listOf(current.copy(directionEvidence = DirectionEvidence.AMBIGUOUS))
        )))
    }
}
