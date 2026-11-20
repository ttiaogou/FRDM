package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.conversation.domain.message.StoredConversationMessage

/** 历史来源显式区分账号验证与本地已采集平台联系人；不伪造账号验证结果。 */
data class VerifiedDraftHistory(
    val accountKey: String,
    val contactId: String,
    val conversationId: String,
    val messages: List<StoredConversationMessage>,
    val lookupSource: String = "VERIFIED_ACCOUNT"
)

internal object DraftHistoryMerger {
    fun merge(visible: ConversationContext, batch: ChatMessageBatch, history: VerifiedDraftHistory?): ConversationContext {
        if (history == null ||
            (history.lookupSource != "LOCAL_PLATFORM_CONTACT" && history.accountKey.isBlank()) || history.contactId.isBlank() ||
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
        val anchor = logicalAnchor ?: legacyAnchor
        // 已通过已采集联系人ID解析的本地历史不要求当前视口与旧记录重叠。
        // 仍只取本批采集时间之前的历史，当前消息始终由本次可见消息覆盖。
        val earlier = if (anchor != null) records.take(anchor)
            else if (history.lookupSource == "LOCAL_PLATFORM_CONTACT") {
                records.filter { it.firstObservedAtMillis < batch.capturedAtMillis }
            } else return visible
        val prefix = earlier.filter { it.messageFingerprint !in ids }
            .distinctBy { it.messageFingerprint }.takeLast((30 - visible.messages.size).coerceAtLeast(0))
            .map { AiModelMessage(if (it.direction == "INCOMING") AiModelMessage.Role.USER else AiModelMessage.Role.ASSISTANT, it.content!!.take(2_000)) }
        return visible.copy(messages = prefix + visible.messages)
    }
}
