package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.*
import org.junit.Assert.*
import org.junit.Test

class VisibleDraftContextTest {
    private fun message(top: Int = 10) = ChatMessage("m$top", ChatMessageDirection.INCOMING,
        ChatMessageType.TEXT, "你好", ScreenRect(0, top, 100, top + 10),
        DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.UNVERIFIED)
    private fun batch(vararg messages: ChatMessage) = ChatMessageBatch(
        MessageReadStatus.SUCCESS, "window-chat", messages.toList(), messages.size, messages.size, false, 1L)

    @Test fun readableOcrNeedsNoPreapprovalAndRetainsQuality() {
        val message = message()
        val context = VisibleDraftContext.build(batch(message))!!
        assertTrue(context.currentWindowDraft)
        assertFalse(context.hasVerifiedIncoming)
        assertFalse(message.isAiInputEligible)
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

    @Test fun latestUnsupportedMessageMustNotExposeOlderIncomingForReply() {
        val latest = message(100)
        val variants = listOf(
            latest.copy(direction = ChatMessageDirection.OUTGOING),
            latest.copy(direction = ChatMessageDirection.UNKNOWN),
            latest.copy(type = ChatMessageType.IMAGE),
            latest.copy(contentQuality = MessageContentQuality.AMBIGUOUS),
            latest.copy(contentQuality = MessageContentQuality.FAILED),
            latest.copy(directionEvidence = DirectionEvidence.AMBIGUOUS))
        variants.forEach { assertNull(VisibleDraftContext.build(batch(message(), it))) }
    }

    @Test fun partialAndStaleBatchesAreRejected() {
        val batch = batch(message())
        assertNull(VisibleDraftContext.build(batch.copy(status = MessageReadStatus.OCR_PARTIAL)))
        assertNull(VisibleDraftContext.build(batch.copy(status = MessageReadStatus.WINDOW_STALE)))
        assertNull(VisibleDraftContext.build(batch.copy(truncated = true)))
        assertNull(VisibleDraftContext.build(batch.copy(windowSessionKey = null)))
    }

    @Test fun contextIsBoundedAndChronologicalWithoutHistoryLookup() {
        val context = VisibleDraftContext.build(batch(*(1..40).reversed().map { message(it * 20) }.toTypedArray()))!!
        assertEquals(30, context.messages.size)
        assertEquals("window-chat", context.conversationId)
        assertEquals("window-chat", context.contactId)
    }
}
