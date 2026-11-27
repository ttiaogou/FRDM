package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.conversation.domain.contact.ContactIdentityManager
import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.domain.message.MessageStoreResult
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import com.freedom.features.conversation.ports.ConversationRepository
import org.junit.Assert.*
import org.junit.Test

class R05InboxIdentityStateTest {
    private class Repository : ConversationRepository {
        val contacts = LinkedHashMap<String, ContactProfile>()
        override fun findContactsByIdentityKeyHash(identityKeyHash: String) =
            contacts.values.filter { it.identityKeyHash == identityKeyHash }
        override fun upsertContact(profile: ContactProfile) { contacts[profile.contactId] = profile }
        override fun persistMessages(contactId: String, conversationId: String,
            identityConfidence: String, capturedAtMillis: Long, messages: List<ConversationMessageInput>) =
            MessageStoreResult()
        override fun contactsSnapshot() = contacts.values.toList()
        override fun messagesForContact(contactId: String): List<StoredConversationMessage> = emptyList()
    }

    @Test fun rowIdentityAndObservedDetailHaveSeparateCacheScopeAndLateCompletionIsIgnored() {
        val repository = Repository()
        var next = 0
        val state = MomoInboxIdentityCaptureState(ContactIdentityManager(repository) { "fixture-${++next}" })
        val logs = mutableListOf<String>()
        val row = InboxIdentityRowAnchor(1, listOf(0, 1), "Fixture", ScreenRect(0, 0, 100, 100))
        val first = PendingInboxIdentityCapture(row, InboxIdentityCaptureStage.WAITING_LIST_RETURN, 100L)
        first.platformUserId = "fixture-platform-1"
        state.pendingInboxIdentityCapture = first
        state.completePendingInboxIdentityBinding(first, true, logs::add)
        assertNull(state.pendingInboxIdentityCapture)
        assertEquals("fixture-platform-1", state.verifiedInboxIdentityByAnchor[row.opaqueKey()])
        assertNotNull(state.completedInboxIdentityOutcomes[row.opaqueKey()]?.verifiedIdentity)
        val observed = ObservedDetailIdentityAnchor("Fixture", WindowRef("fixture", "Chat", 7L, 21), 8L, "visit")
        val second = PendingInboxIdentityCapture(observed, InboxIdentityCaptureStage.WAITING_LIST_RETURN, 200L)
        second.platformUserId = "fixture-platform-2"
        state.pendingInboxIdentityCapture = second
        state.completePendingInboxIdentityBinding(first, true, logs::add)
        assertSame(second, state.pendingInboxIdentityCapture)
        assertEquals(1, logs.size)
        state.completePendingInboxIdentityBinding(second, true, logs::add)
        assertNotNull(state.completedInboxIdentityOutcomes[observed.opaqueKey()]?.verifiedIdentity)
        assertEquals(setOf(row.opaqueKey()), state.verifiedInboxIdentityByAnchor.keys)
        assertEquals(2, repository.contacts.size)
        assertTrue(logs.none { it.contains("fixture-platform-") })
        state.resetForRun()
        assertTrue(state.verifiedInboxIdentityByAnchor.isEmpty())
        assertTrue(state.completedInboxIdentityOutcomes.isEmpty())
        assertEquals(2, repository.contacts.size)
    }

    @Test fun missingIdRemainsRetryableAndRunResetClearsOnlyTransientState() {
        val repo = Repository()
        val state = MomoInboxIdentityCaptureState(ContactIdentityManager(repo) { "fixture" })
        val row = InboxIdentityRowAnchor(1, listOf(0, 1), "Fixture", ScreenRect(0, 0, 100, 100))
        val key = row.opaqueKey()
        val pending = PendingInboxIdentityCapture(row, InboxIdentityCaptureStage.WAITING_PROFILE, 100L)
        state.pendingInboxIdentityCapture = pending
        state.completePendingInboxIdentityBinding(pending, false) {}
        assertNull(state.pendingInboxIdentityCapture)
        assertFalse(key in state.failedInboxIdentityCaptureKeys)
        assertFalse(key in state.completedInboxIdentityOutcomes)
        assertFalse(key in state.verifiedInboxIdentityByAnchor)
        assertEquals(0, repo.contacts.size)
        state.identityReturnReinspectionPending = true
        assertTrue(state.consumeIdentityReturnReinspectionRequest())
        assertFalse(state.consumeIdentityReturnReinspectionRequest())
        state.identityReturnReinspectionPending = true
        state.observedDetailIdentityReturnKey = key
        state.pendingInboxIdentityCapture = pending
        state.loggedInboxIdentityFailureKeys.add("fixture")
        state.resetForRun()
        assertFalse(state.consumeIdentityReturnReinspectionRequest())
        assertNull(state.observedDetailIdentityReturnKey)
        assertNull(state.pendingInboxIdentityCapture)
        assertTrue(state.loggedInboxIdentityFailureKeys.isEmpty())
        state.completePendingInboxIdentityBinding(pending, true) { error("late result delivered") }
        assertTrue(state.completedInboxIdentityOutcomes.isEmpty())
    }
}
