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

class ContactIdentityManagerTest {
    @Test
    fun uniqueDisplayNameRemainsSnapshotScopedAndIsNeverPersistedAsContactIdentity() {
        val repository = FakeRepository()
        var id = 0
        val manager = ContactIdentityManager(repository) { "${++id}" }
        val first = manager.resolveSnapshot(
            "snapshot-1", listOf(ContactIdentityObservation("张三", true)), 100L
        ).single()
        val second = manager.resolveSnapshot(
            "snapshot-2", listOf(ContactIdentityObservation(" 张三 ", true)), 200L
        ).single()

        assertNotEquals(first.temporaryUserId, second.temporaryUserId)
        assertNull(first.contactId)
        assertNull(second.contactId)
        assertEquals(ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL, first.confidence)
        assertEquals(ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL, second.confidence)
        assertEquals(0, repository.contacts.size)
    }

    @Test
    fun stablePlatformIdsKeepSameNamedUsersSeparateAndDeduplicateAcrossSnapshots() {
        val repository = FakeRepository()
        var id = 0
        val manager = ContactIdentityManager(repository) { "${++id}" }
        val first = manager.resolveSnapshot(
            "snapshot-1",
            listOf(
                ContactIdentityObservation("张三", true, platformUserId = "momo-101"),
                ContactIdentityObservation("张三", true, platformUserId = "momo-202")
            ),
            100L
        )
        assertNotEquals(first[0].contactId, first[1].contactId)
        assertEquals(ContactIdentityConfidence.PLATFORM_ID_VERIFIED, first[0].confidence)
        assertEquals(ContactIdentityConfidence.PLATFORM_ID_VERIFIED, first[1].confidence)

        val second = manager.resolveSnapshot(
            "snapshot-2",
            listOf(
                ContactIdentityObservation("张三", true, platformUserId = "momo-202"),
                ContactIdentityObservation("张三", true, platformUserId = "momo-101")
            ),
            200L
        )
        assertEquals(first[1].contactId, second[0].contactId)
        assertEquals(first[0].contactId, second[1].contactId)
        assertEquals(2, repository.contacts.size)
    }

    @Test
    fun duplicateNamesAreAmbiguousWithinSnapshotButDoNotCreateDurableNameContacts() {
        val repository = FakeRepository()
        var id = 0
        val manager = ContactIdentityManager(repository) { "${++id}" }
        val first = manager.resolveSnapshot(
            "snapshot-1", listOf(ContactIdentityObservation("张三", true)), 100L
        ).single()
        assertEquals(ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL, first.confidence)
        assertNull(first.contactId)

        val duplicates = manager.resolveSnapshot(
            "snapshot-2",
            listOf(ContactIdentityObservation("张三", true), ContactIdentityObservation("张三", true)),
            200L
        )
        assertEquals(2, duplicates.size)
        assertTrue(duplicates.all { it.contactId == null })
        assertTrue(duplicates.all { it.confidence == ContactIdentityConfidence.AMBIGUOUS_DUPLICATE_NAME })
        assertEquals(0, repository.contacts.size)

        val later = manager.resolveSnapshot(
            "snapshot-3", listOf(ContactIdentityObservation("张三", true)), 300L
        ).single()
        assertNull(later.contactId)
        assertEquals(ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL, later.confidence)
    }

    @Test
    fun duplicateNamesInSameSnapshotAreNotMergedAndSystemEntriesHaveNoContact() {
        val repository = FakeRepository()
        val manager = ContactIdentityManager(repository) { "id" }
        val resolved = manager.resolveSnapshot(
            "snapshot-1",
            listOf(
                ContactIdentityObservation("张三", true),
                ContactIdentityObservation("张三", true),
                ContactIdentityObservation("收到的招呼", false)
            ),
            100L
        )

        assertEquals(3, resolved.map { it.temporaryUserId }.distinct().size)
        assertEquals(ContactIdentityConfidence.AMBIGUOUS_DUPLICATE_NAME, resolved[0].confidence)
        assertEquals(ContactIdentityConfidence.AMBIGUOUS_DUPLICATE_NAME, resolved[1].confidence)
        assertEquals(ContactIdentityConfidence.NOT_A_PRIVATE_CHAT, resolved[2].confidence)
        assertNull(resolved[0].contactId)
        assertNull(resolved[1].contactId)
        assertNull(resolved[2].contactId)
        assertEquals(0, repository.contacts.size)
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
