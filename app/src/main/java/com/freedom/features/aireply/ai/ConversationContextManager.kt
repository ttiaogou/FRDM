package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.ports.ConversationRepository

/** Builds bounded, verified-only context for one resolved contact. */
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
        require(contactId.isNotBlank() && conversationId.isNotBlank())
        require(identityConfidence.isNotBlank())
        val eligible = batch.messages.filter(ChatMessage::isAiInputEligible)
        val inputs = eligible.map { message ->
            ConversationMessageInput(
                messageFingerprint = message.id,
                direction = message.direction.name,
                type = message.type.name,
                content = message.content?.take(maxMessageChars),
                contentQuality = message.contentQuality.name,
                directionEvidence = message.directionEvidence.name,
                bounds = message.bounds
            )
        }
        val stored = repository.persistMessages(
            contactId, conversationId, identityConfidence, batch.capturedAtMillis, inputs
        )
        val history = repository.messagesForContact(contactId)
            .asSequence()
            .filter { it.contentQuality == "VERIFIED" && it.type == "TEXT" && !it.content.isNullOrBlank() }
            .toList()
            .takeLast(maxHistoryMessages)
            .asSequence()
            .mapNotNull { record ->
                val role = when (record.direction) {
                    ChatMessageDirection.INCOMING.name -> AiModelMessage.Role.USER
                    ChatMessageDirection.OUTGOING.name -> AiModelMessage.Role.ASSISTANT
                    else -> null
                } ?: return@mapNotNull null
                AiModelMessage(role, record.content!!.take(maxMessageChars))
            }.toList()
        return ConversationContext(
            contactId = contactId,
            conversationId = conversationId,
            messages = history,
            insertedCount = stored.insertedCount,
            duplicateCount = stored.duplicateObservationCount,
            rejectedCount = stored.rejectedCount,
            hasVerifiedIncoming = history.any { it.role == AiModelMessage.Role.USER }
        )
    }
}

data class ConversationContext(
    val contactId: String,
    val conversationId: String,
    val messages: List<AiModelMessage>,
    val insertedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val hasVerifiedIncoming: Boolean
)
