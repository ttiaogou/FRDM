package com.freedom.features.aireply.domain.contact

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Outcome of recording one send event that the target app has positively confirmed. */
enum class ContactSendRecordStatus {
    RECORDED,
    DUPLICATE,
    CONTACT_LIMIT_REACHED,
    INVALID_INPUT
}

data class ContactSendCountUpdate(
    val status: ContactSendRecordStatus,
    val count: Int,
    val threshold: Int,
    val thresholdReachedNow: Boolean,
    val shouldPauseContact: Boolean,
    val imageThresholdReachedNow: Boolean = false
)

/**
 * Run-local contact send counter. It retains neither display names nor message bodies:
 * contact keys are normalized and SHA-256 hashed, while send-event IDs are opaque identifiers
 * supplied by the sender. Nothing is written to disk.
 *
 * A count can only increase through [recordSuccessfulSend], which must be called after a
 * positive send-success result from the target app. The ID must be unique per send event, not a
 * hash of message text (repeated identical messages are still separate sends). Reading old
 * outgoing bubbles is not a send. The contact entry is removed only after
 * [confirmInterceptionEnabled] verifies the external intercept switch has actually become enabled.
 */
/** Unified, durable counter. The legacy source path is retained to avoid an unnecessary file delete. */
internal class ContactMessageLedger(
    private val repository: ContactMessageLedgerRepository = InMemoryContactMessageLedgerRepository(),
    private val defaultInterceptionThreshold: Int = 0,
    private val defaultImageThreshold: Int = 0
) {
    private data class ContactState(
        val successfulSendConfirmationHashes: LinkedHashSet<String> = LinkedHashSet(),
        var interceptionThresholdReached: Boolean = false,
        var imageThresholdReached: Boolean = false,
        var imageStatus: ContactImageSendStatus = ContactImageSendStatus.NOT_DUE,
        var interceptionStatus: ContactInterceptionStatus = ContactInterceptionStatus.NOT_DUE,
        var pendingRecoveryAction: ContactPendingRecoveryAction = ContactPendingRecoveryAction.NONE
    )

    private val contacts = LinkedHashMap<String, ContactState>()

    init {
        require(defaultInterceptionThreshold >= 0) { "default interception threshold must not be negative" }
        require(defaultImageThreshold >= 0) { "default image threshold must not be negative" }
        repository.loadAll().forEach { entry ->
            contacts[entry.contactKeyHash] = ContactState(
                successfulSendConfirmationHashes = LinkedHashSet(entry.confirmedSendEventHashes),
                interceptionThresholdReached = entry.interceptionStatus != ContactInterceptionStatus.NOT_DUE,
                imageThresholdReached = entry.imageStatus != ContactImageSendStatus.NOT_DUE,
                imageStatus = entry.imageStatus,
                interceptionStatus = entry.interceptionStatus,
                pendingRecoveryAction = entry.pendingRecoveryAction
            )
        }
    }

    @Synchronized
    fun recordSuccessfulSend(
        contactIdentityKey: String?,
        sendConfirmationId: String?,
        interceptionThreshold: Int = defaultInterceptionThreshold,
        imageThreshold: Int = defaultImageThreshold
    ): ContactSendCountUpdate {
        val contactHash = hashContactKey(contactIdentityKey)
        val confirmationId = sendConfirmationId?.trim().orEmpty()
        if (contactHash == null || confirmationId.isBlank() || confirmationId.length > MAX_CONFIRMATION_ID_LENGTH) {
            return ContactSendCountUpdate(
                ContactSendRecordStatus.INVALID_INPUT, 0, interceptionThreshold.coerceAtLeast(0),
                thresholdReachedNow = false, shouldPauseContact = false
            )
        }
        if (interceptionThreshold < 0 || imageThreshold < 0) {
            return ContactSendCountUpdate(ContactSendRecordStatus.INVALID_INPUT, 0, 0, false, false)
        }

        val state = contacts.getOrPut(contactHash) { ContactState() }
        if (interceptionThreshold > 0 && state.interceptionThresholdReached) {
            return ContactSendCountUpdate(
                ContactSendRecordStatus.CONTACT_LIMIT_REACHED,
                state.successfulSendConfirmationHashes.size,
                interceptionThreshold,
                thresholdReachedNow = false,
                shouldPauseContact = true
            )
        }
        if (!state.successfulSendConfirmationHashes.add(hashConfirmationId(confirmationId))) {
            return ContactSendCountUpdate(
                ContactSendRecordStatus.DUPLICATE,
                state.successfulSendConfirmationHashes.size,
                interceptionThreshold,
                thresholdReachedNow = false,
                shouldPauseContact = false
            )
        }

        val count = state.successfulSendConfirmationHashes.size
        val reachedNow = interceptionThreshold > 0 && count >= interceptionThreshold
        if (reachedNow) {
            state.interceptionThresholdReached = true
            state.interceptionStatus = ContactInterceptionStatus.PENDING_ENABLE
        }
        val imageReachedNow = imageThreshold > 0 && !state.imageThresholdReached && count >= imageThreshold
        if (imageReachedNow) {
            state.imageThresholdReached = true
            state.imageStatus = ContactImageSendStatus.PENDING_SEND
        }
        state.pendingRecoveryAction = when {
            state.imageStatus == ContactImageSendStatus.PENDING_SEND -> ContactPendingRecoveryAction.SEND_IMAGE
            state.interceptionStatus == ContactInterceptionStatus.PENDING_ENABLE -> ContactPendingRecoveryAction.ENABLE_INTERCEPTION
            else -> ContactPendingRecoveryAction.NONE
        }
        persist(contactHash, state)
        return ContactSendCountUpdate(
            ContactSendRecordStatus.RECORDED,
            count,
            interceptionThreshold,
            thresholdReachedNow = reachedNow,
            shouldPauseContact = reachedNow,
            imageThresholdReachedNow = imageReachedNow
        )
    }

    /** Remove a contact only after the application-level intercept switch is verified ON. */
    @Synchronized
    fun confirmInterceptionEnabled(contactIdentityKey: String?): Boolean {
        val key = hashContactKey(contactIdentityKey) ?: return false
        val state = contacts[key] ?: return false
        if (!state.interceptionThresholdReached || state.interceptionStatus == ContactInterceptionStatus.ENABLED) return false
        state.interceptionStatus = ContactInterceptionStatus.ENABLED
        state.pendingRecoveryAction = when {
            state.imageStatus == ContactImageSendStatus.PENDING_SEND -> ContactPendingRecoveryAction.SEND_IMAGE
            else -> ContactPendingRecoveryAction.NONE
        }
        persist(key, state)
        return true
    }

    @Synchronized
    fun successfulSendCount(contactIdentityKey: String?): Int {
        val key = hashContactKey(contactIdentityKey) ?: return 0
        return contacts[key]?.successfulSendConfirmationHashes?.size ?: 0
    }

    @Synchronized
    fun clearAll() {
        contacts.clear()
        repository.clearAll()
    }

    @Synchronized
    fun pendingRecoveryAction(contactIdentityKey: String?): ContactPendingRecoveryAction {
        val key = hashContactKey(contactIdentityKey) ?: return ContactPendingRecoveryAction.NONE
        return contacts[key]?.pendingRecoveryAction ?: ContactPendingRecoveryAction.NONE
    }

    private fun persist(contactHash: String, state: ContactState) {
        repository.save(ContactMessageLedgerEntry(
            contactKeyHash = contactHash,
            confirmedTextCount = state.successfulSendConfirmationHashes.size,
            confirmedSendEventHashes = state.successfulSendConfirmationHashes.toSet(),
            imageStatus = state.imageStatus,
            interceptionStatus = state.interceptionStatus,
            pendingRecoveryAction = state.pendingRecoveryAction
        ))
    }

    private fun hashContactKey(value: String?): String? {
        val normalized = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hashConfirmationId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_CONFIRMATION_ID_LENGTH = 128
    }
}
