package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.conversation.domain.message.StoredConversationMessage

/** 仅由能够确认当前登录账号的仓库实现返回；旧版无账号历史不得包装成此类型。 */
data class VerifiedDraftHistory(
    val accountKey: String,
    val contactId: String,
    val conversationId: String,
    val messages: List<StoredConversationMessage>
)

internal object DraftHistoryMerger {
    fun merge(visible: ConversationContext, batch: ChatMessageBatch, history: VerifiedDraftHistory?): ConversationContext {
        if (history == null || history.accountKey.isBlank() || history.contactId.isBlank() ||
            history.conversationId != "momo-direct-${history.contactId}") return visible
        val current = batch.messages.filter(VisibleDraftContext::eligible)
        val ids = current.map { it.stableLogicalMessageId }.toSet()
        val records = history.messages.filter {
            it.contactId == history.contactId && it.conversationId == history.conversationId &&
                it.identityConfidence == "PLATFORM_ID_VERIFIED" && it.type == "TEXT" &&
                it.contentQuality == "VERIFIED" && it.directionEvidence != "AMBIGUOUS" &&
                !it.content.isNullOrBlank() && it.direction in setOf("INCOMING", "OUTGOING")
        }.sortedWith(
            compareBy<StoredConversationMessage> { it.firstObservedAtMillis }
                .thenBy { it.observationBatchId }
                .thenBy { it.observationRowIndex ?: Int.MAX_VALUE }
                .thenBy { it.bounds.top }
                .thenBy { it.recordId }
        )
        // Anchor against any current visible row, not only the first row. A newly scrolled
        // viewport can contain messages that were not persisted in the previous run, while a
        // later visible row still provides a valid conversation boundary.
        val logicalAnchor = current.asSequence().mapNotNull { message ->
            records.indexOfFirst { record ->
                record.messageFingerprint == message.stableLogicalMessageId &&
                    record.direction == message.direction.name
            }.takeIf { it >= 0 }
        }.firstOrNull()
        val legacyAnchor = if (logicalAnchor == null) current.asSequence().mapNotNull { message ->
            records.indexOfFirst { record ->
                // Legacy records may still carry a text-derived fingerprint.
                record.content == message.content && record.direction == message.direction.name
            }.takeIf { it >= 0 }
        }.firstOrNull() else null
        val anchor = logicalAnchor ?: legacyAnchor ?: return visible
        val prefix = records.take(anchor).filter { it.messageFingerprint !in ids }
            .distinctBy { it.messageFingerprint }.takeLast((30 - visible.messages.size).coerceAtLeast(0))
            .map { AiModelMessage(if (it.direction == "INCOMING") AiModelMessage.Role.USER else AiModelMessage.Role.ASSISTANT, it.content!!.take(2_000)) }
        return visible.copy(messages = prefix + visible.messages)
    }
}
