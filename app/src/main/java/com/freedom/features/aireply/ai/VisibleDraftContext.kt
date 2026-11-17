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
        val latest = batch.messagesBottomToTop.firstOrNull() ?: return retry("LATEST_MESSAGE_UNAVAILABLE")
        // A truncated viewport cannot prove that the latest visible row is the
        // actual conversation tail, so it remains ineligible for automatic
        // drafting. An unrelated dynamic/system card or a partially read row
        // does not prove that the active input field is covered or that the
        // chat changed. The writer re-inspects the live conversation and
        // editable input immediately before and after SET_TEXT.
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
