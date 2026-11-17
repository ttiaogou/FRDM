package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.*
import org.junit.Assert.*
import org.junit.Test

class VisibleDraftContextTest {
    private fun message(top: Int = 10, quality: MessageContentQuality = MessageContentQuality.VERIFIED) = ChatMessage("m$top", ChatMessageDirection.INCOMING,
        ChatMessageType.TEXT, "你好", ScreenRect(0, top, 100, top + 10),
        DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, quality)
    private fun batch(vararg messages: ChatMessage) = ChatMessageBatch(
        MessageReadStatus.SUCCESS, "window-chat", messages.toList(), messages.size, messages.size, false, 1L)

    @Test fun independentlyVerifiedTextNeedsNoPreapproval() {
        val message = message()
        val context = VisibleDraftContext.build(batch(message).copy(completeTailRowBounds = message.bounds))!!
        assertTrue(context.currentWindowDraft)
        assertTrue(context.hasVerifiedIncoming)
        assertTrue(message.isAiInputEligible)
        assertEquals("你好", context.messages.single().content)
        var calls = 0
        val engine = AiReplyDecisionEngine(object : AiModelProvider {
            override fun generate(request: AiModelRequest): AiModelResult {
                calls++
                return AiModelResult("""{"action":"REPLY","reason":"OK","draft":"你好呀"}""", "fake", "fake")
            }
        }, AiModelCloudConsent { true })
        val decision = engine.decide(AiReplyDecisionInput(context, false, true, false, "request"))
        assertEquals(1, calls)
        assertEquals(AiReplyAction.REPLY, decision.action)
    }

    @Test fun structurallyAdmittedOcrTextEntersDraftContext() {
        val message = message(quality = MessageContentQuality.VERIFIED)
        val result = VisibleDraftContext.assess(batch(message).copy(completeTailRowBounds = message.bounds))
        assertTrue(result.accepted)
        assertFalse(result.retryable)
        assertNotNull(VisibleDraftContext.build(batch(message).copy(completeTailRowBounds = message.bounds)))
    }

    @Test fun latestUnsupportedMessageMustNotExposeOlderIncomingForReply() {
        val latest = message(100)
        val variants = listOf(
            latest.copy(direction = ChatMessageDirection.OUTGOING),
            latest.copy(direction = ChatMessageDirection.UNKNOWN),
            latest.copy(type = ChatMessageType.IMAGE),
            latest.copy(contentQuality = MessageContentQuality.AMBIGUOUS),
            latest.copy(contentQuality = MessageContentQuality.FAILED),
            latest.copy(directionEvidence = DirectionEvidence.AMBIGUOUS))
        variants.forEach { assertNull(VisibleDraftContext.build(batch(message()).copy(messages = listOf(message(), it), completeTailRowBounds = it.bounds))) }
    }

    @Test fun partialAndStaleBatchesAreRejected() {
        val batch = batch(message()).copy(completeTailRowBounds = message().bounds)
        val partial = batch.copy(status = MessageReadStatus.OCR_PARTIAL, ocrFailedTargetCount = 1, unrecognizedRowCount = 1)
        assertNotNull(VisibleDraftContext.build(partial))
        assertNull(VisibleDraftContext.build(batch.copy(status = MessageReadStatus.WINDOW_STALE)))
        assertNull(VisibleDraftContext.build(batch.copy(truncated = true)))
        assertNull(VisibleDraftContext.build(batch.copy(windowSessionKey = null)))
    }

    @Test fun unrecognizedDynamicCardDoesNotBlockVerifiedLatestIncoming() {
        val latest = message(100)
        val partial = batch(latest).copy(
            status = MessageReadStatus.OCR_PARTIAL,
            ocrFailedTargetCount = 1,
            unrecognizedRowCount = 1,
            completeTailRowBounds = null
        )

        val admission = VisibleDraftContext.assess(partial)

        assertTrue(admission.accepted)
        assertEquals("LATEST_INCOMING_VERIFIED", admission.reason)
    }

    @Test fun contextIsBoundedAndChronologicalWithoutHistoryLookup() {
        val latest = message(800)
        val context = VisibleDraftContext.build(batch(*(1..40).reversed().map { message(it * 20) }.toTypedArray()).copy(completeTailRowBounds = latest.bounds))!!
        assertEquals(30, context.messages.size)
        assertEquals("window-chat", context.conversationId)
        assertEquals("window-chat", context.contactId)
    }
}
