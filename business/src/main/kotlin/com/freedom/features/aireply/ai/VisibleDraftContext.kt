package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.*

/** 草稿仅使用已验证文字；写入安全性由当前会话和输入框的实时校验确认。 */
internal object VisibleDraftContext {
    data class Admission(val accepted: Boolean, val retryable: Boolean, val reason: String,
        val replyTarget: ChatMessage? = null)

    /** Only isolated, ordered non-text rows with known incoming direction may be crossed.
     * An unclassified text failure, unknown sender, or any own outgoing row is a barrier. */
    private fun isolatedIncomingVisual(message: ChatMessage): Boolean =
        message.direction == ChatMessageDirection.INCOMING &&
            message.directionEvidence != DirectionEvidence.AMBIGUOUS &&
            message.content == null &&
            message.contentQuality != MessageContentQuality.VERIFIED &&
            message.type in setOf(ChatMessageType.EMOJI, ChatMessageType.IMAGE,
                ChatMessageType.VOICE, ChatMessageType.VIDEO, ChatMessageType.UNKNOWN)

    fun replyTarget(batch: ChatMessageBatch): ChatMessage? = assess(batch).replyTarget

    // Legacy callers without a batch cannot upgrade AMBIGUOUS OCR evidence.
    fun eligible(message: ChatMessage): Boolean = message.isAiInputEligible &&
        message.directionEvidence != DirectionEvidence.AMBIGUOUS &&
        AiSafeConversationText.legacy(message.content) != null

    fun eligible(batch: ChatMessageBatch, message: ChatMessage): Boolean =
        AiSafeConversationText.visible(batch, message) != null

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
        if (latest.type == ChatMessageType.UNKNOWN && !isolatedIncomingVisual(latest))
            return retry("CLASSIFIED_VISUAL_PENDING_CONTEXT")
        if (latest.direction == ChatMessageDirection.OUTGOING)
            return Admission(false, false, "LATEST_MESSAGE_OUTGOING")
        var target = latest
        if (isolatedIncomingVisual(latest)) {
            // Crossing a visual tail is a NEW admission path: require complete current
            // tail geometry and consecutive row indices, not arbitrary historical text.
            if (batch.completeTailRowBounds != latest.bounds || latest.bounds.isEmpty ||
                batch.ocrAdmissionDeferred || latest.observationRowIndex == null
            ) return retry("CLASSIFIED_VISUAL_PENDING_CONTEXT")
            var newer = latest
            var selected: ChatMessage? = null
            for (row in batch.messagesBottomToTop.drop(1)) {
                val newerIndex = newer.observationRowIndex ?: break
                if (row.observationRowIndex != newerIndex - 1 || row.bounds.isEmpty ||
                    row.bounds.bottom > newer.bounds.top ||
                    row.direction != ChatMessageDirection.INCOMING ||
                    row.directionEvidence == DirectionEvidence.AMBIGUOUS
                ) break
                if (isolatedIncomingVisual(row)) {
                    newer = row
                    continue
                }
                if (row.type == ChatMessageType.TEXT && eligible(batch, row)) selected = row
                break
            }
            target = selected ?: return retry("CLASSIFIED_VISUAL_PENDING_CONTEXT")
        }
        if (target.type != ChatMessageType.TEXT) {
            // Retain the previous fail-closed gate for legacy/non-classified payloads.
            if (target.type in setOf(ChatMessageType.EMOJI, ChatMessageType.IMAGE,
                    ChatMessageType.VOICE, ChatMessageType.VIDEO) &&
                batch.messagesBottomToTop.drop(1).takeWhile { it.direction == ChatMessageDirection.INCOMING }
                    .any { it.type == ChatMessageType.TEXT }
            ) return retry("CLASSIFIED_VISUAL_PENDING_CONTEXT")
            return Admission(false, false, "LATEST_MESSAGE_UNSUPPORTED")
        }
        if (!eligible(batch, target)) return retry("LATEST_TEXT_OR_DIRECTION_UNVERIFIED")
        if (target.content!!.length > 2_000) return Admission(false, false, "LATEST_MESSAGE_EXCEEDS_CONTEXT_LIMIT")
        return Admission(true, false,
            if (target == latest) "LATEST_INCOMING_VERIFIED" else "INCOMING_TEXT_BEFORE_ISOLATED_VISUAL", target)
    }

    fun build(batch: ChatMessageBatch, visitToken: String = batch.windowSessionKey.orEmpty()): ConversationContext? {
        if (!assess(batch).accepted || visitToken.isBlank()) return null
        val key = batch.windowSessionKey.orEmpty()
        val messages = batch.messagesBottomToTop.asReversed().mapNotNull { message ->
            AiSafeConversationText.visible(batch, message)?.let { safeText ->
                AiModelMessage(
                    if (message.direction == ChatMessageDirection.INCOMING) AiModelMessage.Role.USER else AiModelMessage.Role.ASSISTANT,
                    safeText.take(2_000)
                )
            }
        }.takeLast(30)
        return ConversationContext(key, key, messages, 0, 0, 0, true, currentWindowDraft = true)
    }
}
