package com.freedom.features.conversation.domain.contact

import com.freedom.features.conversation.ports.ConversationRepository
import com.freedom.features.conversation.domain.message.*
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.*
import org.junit.Test

class ContactHistoryResolverTest {
    private class Repository : ConversationRepository {
        val profiles = mutableListOf<ContactProfile>()
        val messages = mutableListOf<StoredConversationMessage>()
        override fun findContactsByIdentityKeyHash(identityKeyHash: String) = profiles.filter { it.identityKeyHash == identityKeyHash }
        override fun upsertContact(profile: ContactProfile) { profiles.add(profile) }
        override fun contactsSnapshot() = profiles.toList()
        override fun messagesForContact(contactId: String) = messages.filter { it.contactId == contactId }
        override fun persistMessages(contactId: String, conversationId: String, identityConfidence: String,
            capturedAtMillis: Long, messages: List<ConversationMessageInput>) = MessageStoreResult()
    }
    private fun profile(id: String, name: String = "小明", verified: Boolean = true) = ContactProfile(
        id, "hash-$id", name, "PLATFORM_USER_ID", if (verified) ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        else ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL, 1, 2)
    private fun identity(id: String) = ContactRowIdentity("s", "t", id, ContactIdentityConfidence.PLATFORM_ID_VERIFIED)

    @Test fun previouslyCapturedIdLoadsHistoryWithoutAccountGateOrTitleMatch() {
        val repository = Repository()
        repository.messages.add(StoredConversationMessage("record", "c1", "momo-direct-c1", "m1", "INCOMING", "TEXT", "旧消息",
            "VERIFIED", "BUBBLE_GEOMETRY", ScreenRect(0, 1, 10, 2), "PLATFORM_ID_VERIFIED", 1, 1, 1))
        val history = ContactHistoryResolver(repository).loadDraftHistory(identity("c1"), "changed title")!!
        assertEquals("c1", history.contactId)
        assertEquals("旧消息", history.messages.single().content)
        assertEquals("LOCAL_PLATFORM_CONTACT", history.lookupSource)
        assertEquals("", history.accountKey)
    }

    @Test fun nicknameFindsPreviouslyCollectedPlatformContact() {
        val repository = Repository(); repository.profiles.add(profile("c1"))
        val resolver = ContactHistoryResolver(repository)
        assertEquals("c1", resolver.resolve(null, "与小明对话")?.contactId)
        assertEquals("c1", resolver.resolve(null, "小明")?.contactId)
    }

    @Test fun knownIdTakesPriorityOverNickname() {
        val repository = Repository(); repository.profiles.add(profile("other"))
        assertEquals("c1", ContactHistoryResolver(repository).resolve(identity("c1"), "小明")?.contactId)
    }

    @Test fun ambiguousNamesAndUncollectedIdentitiesAreNotGuessed() {
        val repository = Repository(); repository.profiles.add(profile("c1")); repository.profiles.add(profile("c2"))
        val resolver = ContactHistoryResolver(repository)
        assertNull(resolver.resolve(null, "小明"))
        assertNull(resolver.resolve(null, "小"))
        repository.profiles.clear(); repository.profiles.add(profile("provisional", verified = false))
        assertNull(resolver.resolve(null, "小明"))
    }
}
