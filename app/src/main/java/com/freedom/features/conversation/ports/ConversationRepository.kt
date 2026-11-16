package com.freedom.features.conversation.ports

import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.domain.message.MessageStoreResult
import com.freedom.features.conversation.domain.message.StoredConversationMessage

/** Local persistence boundary for contact identity and validated conversation observations. */
interface ConversationRepository {
    fun findContactsByIdentityKeyHash(identityKeyHash: String): List<ContactProfile>

    fun upsertContact(profile: ContactProfile)

    /** Batch variant lets a single inbox snapshot update contacts with one durable write. */
    fun upsertContacts(profiles: List<ContactProfile>) {
        profiles.forEach(::upsertContact)
    }

    fun persistMessages(
        contactId: String,
        conversationId: String,
        identityConfidence: String,
        capturedAtMillis: Long,
        messages: List<ConversationMessageInput>
    ): MessageStoreResult

    fun contactsSnapshot(): List<ContactProfile> = emptyList()

    fun messagesForContact(contactId: String): List<StoredConversationMessage> = emptyList()

    /** Returns one exact conversation in oldest-to-newest observation order. */
    fun messagesForConversation(
        contactId: String,
        conversationId: String
    ): List<StoredConversationMessage> = messagesForContact(contactId)
        .filter { it.conversationId == conversationId }
        .sortedWith(
            compareBy<StoredConversationMessage> { it.firstObservedAtMillis }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.bottom }
                .thenBy { it.recordId }
        )
}
