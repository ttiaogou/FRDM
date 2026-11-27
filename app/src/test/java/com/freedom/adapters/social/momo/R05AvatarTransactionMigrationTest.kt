package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.features.aireply.ports.ContactIdentityCaptureRequestResult
import com.freedom.features.conversation.domain.contact.ContactIdentityManager
import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.domain.message.MessageStoreResult
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import com.freedom.features.conversation.ports.ConversationRepository
import org.junit.Assert.*
import org.junit.Test

class R05AvatarTransactionMigrationTest {
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

    @Test fun callbackIsNotProfileProofAndLateResultsCannotReleaseNewCapture() {
        val repo = Repository()
        val state = MomoInboxIdentityCaptureState(ContactIdentityManager(repo) { "fixture" })
        val row = InboxIdentityRowAnchor(1, listOf(0, 1), "Fixture", ScreenRect(0, 0, 100, 100))
        val key = row.opaqueKey()
        val log: (String) -> Unit = {}
        val first = PendingInboxIdentityCapture(row, InboxIdentityCaptureStage.AVATAR_TAP_PENDING, 100L)
        assertNull(state.checkCaptureAdmission(key, log))
        assertNull(state.registerCapture(key, first, log))
        assertEquals(ContactIdentityCaptureRequestResult.IN_PROGRESS, state.checkCaptureAdmission(key, log))
        state.transitionAvatarClickState(first, MomoAvatarClickTransactionState.AVATAR_COMPLETED, "illegal", log)
        assertEquals(MomoAvatarClickTransactionState.AVATAR_CLICK_PREPARING, first.avatarClickState)
        listOf(MomoAvatarClickTransactionState.AVATAR_PAGE_READING,
            MomoAvatarClickTransactionState.AVATAR_LOCATING,
            MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING,
            MomoAvatarClickTransactionState.AVATAR_CLICKING).forEach {
            state.transitionAvatarClickState(first, it, "fixture", log)
        }
        state.applyAvatarGestureResult(first, GestureExecutionStatus.COMPLETED, log)
        assertEquals(InboxIdentityCaptureStage.WAITING_PROFILE, first.stage)
        assertEquals(MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING, first.avatarClickState)
        assertFalse(first.profileEntryVerified)
        assertFalse(first.profileReadCompleted)
        assertNull(first.platformUserId)
        state.releaseAvatarCaptureAfterProfileMiss(first, MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED, log)
        assertNull(state.pendingInboxIdentityCapture)
        assertFalse(key in state.failedInboxIdentityCaptureKeys)
        val second = PendingInboxIdentityCapture(row, InboxIdentityCaptureStage.AVATAR_TAP_PENDING, 200L)
        assertNull(state.registerCapture(key, second, log))
        state.applyAvatarGestureResult(first, GestureExecutionStatus.REJECTED, log)
        state.releaseIfCurrent(first)
        assertSame(second, state.pendingInboxIdentityCapture)
        state.applyAvatarGestureResult(second, GestureExecutionStatus.DEFERRED, log)
        assertNull(state.pendingInboxIdentityCapture)
        assertEquals(MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING, second.avatarClickState)
        assertNull(state.checkCaptureAdmission(key, log))
        assertEquals(0, repo.contacts.size)
    }

    @Test fun cacheAdmissionAndReturnSignalsStayIndependentOfProfileGesture() {
        val state = MomoInboxIdentityCaptureState(ContactIdentityManager(Repository()) { "fixture" })
        val row = InboxIdentityRowAnchor(1, listOf(0, 1), "Fixture", ScreenRect(0, 0, 100, 100))
        val key = row.opaqueKey()
        val log: (String) -> Unit = {}
        state.invalidateCachedIdentity(key, MomoIdentityVerificationResult.IDENTITY_RETRYABLE, log)
        assertNull(state.checkCaptureAdmission(key, log))
        state.invalidateCachedIdentity(key, MomoIdentityVerificationResult.IDENTITY_CONFLICT, log)
        assertEquals(ContactIdentityCaptureRequestResult.REJECTED, state.checkCaptureAdmission(key, log))
        state.cancelObservedCapture(key)
        assertNull(state.checkCaptureAdmission(key, log))
        val target = ObservedDetailIdentityAnchor("Fixture", WindowRef("fixture", "Chat", 7L, 21), 8L, "visit")
        val pending = PendingInboxIdentityCapture(target, InboxIdentityCaptureStage.WAITING_LIST_RETURN, 100L)
        val targetKey = target.opaqueKey()
        assertNull(state.registerCapture(targetKey, pending, log))
        assertTrue(state.hasObservedCapture(targetKey))
        state.publishVerifiedReturn(pending)
        assertFalse(state.hasObservedReturn(targetKey))
        pending.platformUserId = "fixture-platform"
        state.completePendingInboxIdentityBinding(pending, true, log)
        state.publishVerifiedReturn(pending)
        assertFalse(state.hasReturnReinspection())
        assertTrue(state.consumeObservedReturn(targetKey))
        assertFalse(state.consumeObservedReturn(targetKey))
    }
}
