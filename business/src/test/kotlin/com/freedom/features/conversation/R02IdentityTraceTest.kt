package com.freedom.features.conversation

import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactIdentityManager
import com.freedom.features.conversation.domain.contact.ContactIdentityObservation
import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.domain.message.MessageStoreResult
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import com.freedom.features.conversation.ports.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class R02IdentityTraceTest {
    @Test
    fun provisionalAndDuplicateNamesStayTransientUntilPlatformEvidence() {
        val repository = FakeRepository()
        var id = 0
        val manager = ContactIdentityManager(repository) { "${++id}" }
        val trace = mutableListOf<String>()
        val provisional = manager.resolveSnapshot("s1", listOf(ContactIdentityObservation("fixture", true)), 100L).single()
        trace += "${provisional.confidence}:${provisional.contactId == null}:${repository.contacts.size}"
        val duplicate = manager.resolveSnapshot("s2", List(2) { ContactIdentityObservation("fixture", true) }, 200L)
        trace += "${duplicate.map { it.confidence }.distinct().single()}:${duplicate.all { it.contactId == null }}:${repository.contacts.size}"
        val verified = manager.resolveSnapshot("s3", listOf(
            ContactIdentityObservation("fixture", true, platformUserId = "fixture-1"),
            ContactIdentityObservation("fixture", true, platformUserId = "fixture-2")), 300L)
        trace += "${verified.map { it.confidence }.distinct().single()}:${verified[0].contactId != verified[1].contactId}:${repository.contacts.size}"
        val reordered = manager.resolveSnapshot("s4", listOf(
            ContactIdentityObservation("fixture", true, platformUserId = "fixture-2"),
            ContactIdentityObservation("fixture", true, platformUserId = "fixture-1")), 400L)
        trace += "reordered:${verified[0].contactId == reordered[1].contactId}:${verified[1].contactId == reordered[0].contactId}"
        assertEquals(listOf("DISPLAY_NAME_PROVISIONAL:true:0", "AMBIGUOUS_DUPLICATE_NAME:true:0",
            "PLATFORM_ID_VERIFIED:true:2", "reordered:true:true"), trace)
    }

    private class FakeRepository : ConversationRepository {
        val contacts = LinkedHashMap<String, ContactProfile>()
        override fun findContactsByIdentityKeyHash(identityKeyHash: String) =
            contacts.values.filter { it.identityKeyHash == identityKeyHash }

        override fun upsertContact(profile: ContactProfile) {
            contacts[profile.contactId] = profile
        }

        override fun persistMessages(
            contactId: String,
            conversationId: String,
            identityConfidence: String,
            capturedAtMillis: Long,
            messages: List<ConversationMessageInput>
        ) = MessageStoreResult()

        override fun contactsSnapshot() = contacts.values.toList()
        override fun messagesForContact(contactId: String): List<StoredConversationMessage> = emptyList()
    }
}
