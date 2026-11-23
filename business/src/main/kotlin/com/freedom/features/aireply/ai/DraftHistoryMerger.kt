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
        // 允许使用当前任一可见消息行作为锚点，不仅限于第一行；新滚动到的
        // 视口可能包含上次运行未持久化的消息，而后续仍可见的消息行
        // 依然能够提供有效的会话边界。
        val logicalAnchor = current.asSequence().mapNotNull { message ->
            records.indexOfFirst { record ->
                record.messageFingerprint == message.stableLogicalMessageId &&
                    record.direction == message.direction.name
            }.takeIf { it >= 0 }
        }.firstOrNull()
        val legacyAnchor = if (logicalAnchor == null) current.asSequence().mapNotNull { message ->
            records.indexOfFirst { record ->
                // 旧版记录可能仍保存根据文本生成的指纹。
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
