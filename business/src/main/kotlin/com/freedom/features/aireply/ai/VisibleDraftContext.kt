package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.*

/** 草稿仅使用已验证文字；写入安全性由当前会话和输入框的实时校验确认。 */
internal object VisibleDraftContext {
    data class Admission(val accepted: Boolean, val retryable: Boolean, val reason: String)

    fun eligible(message: ChatMessage): Boolean = message.isAiInputEligible &&
        message.directionEvidence != DirectionEvidence.AMBIGUOUS

    fun assess(batch: ChatMessageBatch): Admission {
        fun retry(reason: String) = Admission(false, true, reason)
        if (batch.windowSessionKey.isNullOrBlank() || !batch.coordinateSpaceReliable) return retry("WINDOW_OR_GEOMETRY_UNCONFIRMED")
        if (batch.status !in setOf(MessageReadStatus.SUCCESS, MessageReadStatus.OCR_PARTIAL,
                MessageReadStatus.DIRECTION_UNKNOWN, MessageReadStatus.MESSAGE_TEXT_NOT_EXPOSED)) return retry("MESSAGE_READ_INCOMPLETE")
        val latest = batch.messagesBottomToTop.firstOrNull()
            ?: return retry(
                if (batch.ocrTargets.isNotEmpty() && !batch.ocrAttempted) {
                    "MESSAGE_TEXT_PENDING_OCR"
                } else {
                    "LATEST_MESSAGE_UNAVAILABLE"
                }
            )
        // 被截断的视口无法证明最后一条可见消息就是
        // 会话的真正末尾，因此不能直接用于自动
        // 起草。无关的动态或系统卡片以及未完整读取的消息行，
        // 并不能证明当前输入框被遮挡，也不能证明
        // 会话发生变化。写入器会在 SET_TEXT 前后重新检查
        // 当前会话及可编辑输入框。
        if (batch.truncated) return retry("MESSAGE_LIST_TRUNCATED")
        if (latest.type != ChatMessageType.TEXT) return Admission(false, false, "LATEST_MESSAGE_UNSUPPORTED")
        if (latest.direction == ChatMessageDirection.OUTGOING) return Admission(false, false, "LATEST_MESSAGE_OUTGOING")
        if (!eligible(latest)) return retry("LATEST_TEXT_OR_DIRECTION_UNVERIFIED")
        if (latest.content!!.length > 2_000) return Admission(false, false, "LATEST_MESSAGE_EXCEEDS_CONTEXT_LIMIT")
        return Admission(true, false, "LATEST_INCOMING_VERIFIED")
    }

    fun build(batch: ChatMessageBatch, visitToken: String = batch.windowSessionKey.orEmpty()): ConversationContext? {
        if (!assess(batch).accepted || visitToken.isBlank()) return null
        val key = batch.windowSessionKey.orEmpty()
        val messages = batch.messagesBottomToTop.asReversed().filter(::eligible).takeLast(30).map {
            AiModelMessage(if (it.direction == ChatMessageDirection.INCOMING) AiModelMessage.Role.USER else AiModelMessage.Role.ASSISTANT,
                it.content!!.take(2_000))
        }
        return ConversationContext(key, key, messages, 0, 0, 0, true, currentWindowDraft = true)
    }
}
