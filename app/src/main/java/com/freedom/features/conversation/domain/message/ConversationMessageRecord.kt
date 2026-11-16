package com.freedom.features.conversation.domain.message

import com.freedom.core.accessibility.api.ScreenRect

/** Message data passed from a validated provider adapter to the conversation store. */
data class ConversationMessageInput(
    val messageFingerprint: String,
    val direction: String,
    val type: String,
    val content: String?,
    val contentQuality: String,
    val directionEvidence: String,
    val bounds: ScreenRect
)

data class StoredConversationMessage(
    val recordId: String,
    val contactId: String,
    val conversationId: String,
    val messageFingerprint: String,
    val direction: String,
    val type: String,
    val content: String?,
    val contentQuality: String,
    val directionEvidence: String,
    val bounds: ScreenRect,
    val identityConfidence: String,
    val firstObservedAtMillis: Long,
    val lastObservedAtMillis: Long,
    val observationCount: Int
)

data class MessageStoreResult(
    val insertedCount: Int = 0,
    val duplicateObservationCount: Int = 0,
    val rejectedCount: Int = 0
)
