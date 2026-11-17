package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.*

/** 当前窗口草稿专用：允许可读 OCR，保留原可信度，不写入已验证历史。 */
internal object VisibleDraftContext {
    fun eligible(message: ChatMessage): Boolean = message.isTextMessage &&
        message.direction != ChatMessageDirection.UNKNOWN &&
        message.directionEvidence != DirectionEvidence.AMBIGUOUS &&
        message.contentQuality in setOf(MessageContentQuality.VERIFIED, MessageContentQuality.UNVERIFIED)

    fun build(batch: ChatMessageBatch): ConversationContext? {
        if (batch.status != MessageReadStatus.SUCCESS || batch.truncated ||
            !batch.coordinateSpaceReliable || batch.unrecognizedRowCount > 0 ||
            batch.ocrFailedTargetCount > 0) return null
        val ordered = batch.messagesBottomToTop.asReversed()
        // 不能跳过最新的媒体、失败、方向不明消息，误回复更早的文字。
        val latest = ordered.lastOrNull() ?: return null
        if (!eligible(latest) || latest.direction != ChatMessageDirection.INCOMING) return null
        val key = batch.windowSessionKey?.takeIf(String::isNotBlank) ?: return null
        val messages = ordered.filter(::eligible).takeLast(30).map {
            AiModelMessage(
                if (it.direction == ChatMessageDirection.INCOMING) AiModelMessage.Role.USER else AiModelMessage.Role.ASSISTANT,
                it.content!!.take(2_000)
            )
        }
        return ConversationContext(key, key, messages, 0, 0, 0,
            hasVerifiedIncoming = ordered.any { it.isAiInputEligible && it.direction == ChatMessageDirection.INCOMING },
            currentWindowDraft = true)
    }
}
