package com.freedom.features.aireply.ai

import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.ports.ConversationRepository

/** 为已解析的单一联系人构建有数量限制且仅包含已验证消息的上下文。 */
class ConversationContextManager(
    private val repository: ConversationRepository,
    private val maxHistoryMessages: Int = 30,
    private val maxMessageChars: Int = 2_000
) {
    init {
        require(maxHistoryMessages in 1..200)
        require(maxMessageChars in 1..10_000)
    }

    fun recordAndLoad(
        contactId: String,
        conversationId: String,
        identityConfidence: String,
        batch: ChatMessageBatch
    ): ConversationContext {
        requireVerifiedIdentity(contactId, conversationId, identityConfidence)
        // Keep the original evidence and fingerprint in storage; do not persist partial
        // P2 projections as if they were fully VERIFIED OCR messages.
        val eligible = batch.messages.filter {
            it.isAiInputEligible && AiSafeConversationText.legacy(it.content) != null
        }
        val inputs = eligible.map { message ->
            ConversationMessageInput(
                messageFingerprint = message.stableLogicalMessageId,
                direction = message.direction.name,
                type = message.type.name,
                content = message.content?.take(maxMessageChars),
                contentQuality = message.contentQuality.name,
                directionEvidence = message.directionEvidence.name,
                bounds = message.bounds,
                contentRevision = message.effectiveContentRevision,
                observationBatchId = batch.effectiveObservationBatchId,
                observationRowIndex = message.observationRowIndex
            )
        }
        // 陌陌适配器在保存只读聊天样本时，可能已经持久化了同一批经过验证的消息。
        // 这里只补写实际缺失的观察记录，避免重复计入同一次界面观察，
        // 同时保留对之前写入失败的恢复能力。
        val existing = repository.messagesForConversation(contactId, conversationId)
        val missingInputs = inputs.filter { input ->
            existing.none { record ->
                record.identityConfidence == identityConfidence &&
                    record.messageFingerprint == input.messageFingerprint &&
                    record.direction == input.direction && record.type == input.type &&
                    record.content == input.content &&
                    record.contentQuality == input.contentQuality &&
                    record.directionEvidence == input.directionEvidence &&
                    record.contentRevision == input.contentRevision &&
                    record.observationBatchId == input.observationBatchId &&
                    record.observationRowIndex == input.observationRowIndex
            }
        }
        val stored = if (missingInputs.isEmpty()) {
            com.freedom.features.conversation.domain.message.MessageStoreResult()
        } else {
            repository.persistMessages(
                contactId, conversationId, identityConfidence, batch.capturedAtMillis, missingInputs
            )
        }
        return loadForConversation(contactId, conversationId, identityConfidence).copy(
            insertedCount = stored.insertedCount,
            duplicateCount = stored.duplicateObservationCount,
            rejectedCount = stored.rejectedCount
        )
    }

    /** 仅加载与已验证联系人及会话组合精确匹配的持久化历史。 */
    fun loadForConversation(
        contactId: String,
        conversationId: String,
        identityConfidence: String
    ): ConversationContext {
        requireVerifiedIdentity(contactId, conversationId, identityConfidence)
        val history = repository.messagesForConversation(contactId, conversationId)
            .asSequence()
            .filter {
                it.contactId == contactId && it.conversationId == conversationId &&
                    it.identityConfidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED.name &&
                    it.contentQuality == "VERIFIED" && it.type == "TEXT" &&
                    it.directionEvidence != "AMBIGUOUS" &&
                    AiSafeConversationText.legacy(it.content) != null
            }
            .sortedWith(
                compareBy<com.freedom.features.conversation.domain.message.StoredConversationMessage> {
                    it.firstObservedAtMillis
                }.thenBy { it.observationBatchId }
                    .thenBy { it.observationRowIndex ?: Int.MAX_VALUE }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.bottom }
                    .thenBy { it.recordId }
            )
            .toList()
            .takeLast(maxHistoryMessages)
            .mapNotNull { record ->
                val role = when (record.direction) {
                    ChatMessageDirection.INCOMING.name -> AiModelMessage.Role.USER
                    ChatMessageDirection.OUTGOING.name -> AiModelMessage.Role.ASSISTANT
                    else -> null
                } ?: return@mapNotNull null
                AiSafeConversationText.legacy(record.content)?.let { safe ->
                    AiModelMessage(role, safe.take(maxMessageChars))
                }
            }
        return ConversationContext(
            contactId = contactId,
            conversationId = conversationId,
            messages = history,
            insertedCount = 0,
            duplicateCount = 0,
            rejectedCount = 0,
            hasVerifiedIncoming = history.any { it.role == AiModelMessage.Role.USER }
        )
    }

    private fun requireVerifiedIdentity(
        contactId: String,
        conversationId: String,
        identityConfidence: String
    ) {
        require(contactId.isNotBlank() && conversationId.isNotBlank())
        require(identityConfidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED.name) {
            "Conversation AI context requires a platform-verified contact identity"
        }
    }
}

data class ConversationContext(
    val contactId: String,
    val conversationId: String,
    val messages: List<AiModelMessage>,
    val insertedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val hasVerifiedIncoming: Boolean,
    val currentWindowDraft: Boolean = false
)
