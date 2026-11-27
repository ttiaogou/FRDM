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
    INVALID_INPUT,
    PERSISTENCE_FAILED
}

data class ContactSendCountUpdate(
    val status: ContactSendRecordStatus,
    val count: Int,
    val threshold: Int,
    val thresholdReachedNow: Boolean,
    val shouldPauseContact: Boolean,
    val imageThresholdReachedNow: Boolean = false
)

/** Private-chat confirmed-send ledger; identity and event keys are opaque hashes.
 * The existing repository owns durable state, including pending follow-up checkpoints.
 * Runtime reset is not an explicit deletion of business data.
 */
internal class ContactMessageLedger(
    private val repository: ContactMessageLedgerRepository = InMemoryContactMessageLedgerRepository(),
    private val defaultInterceptionThreshold: Int = 0,
    private val defaultImageThreshold: Int = 0
) {
    private data class ContactState(
        val successfulSendConfirmationHashes: LinkedHashSet<String> = LinkedHashSet(),
        var imageStatus: ContactImageSendStatus = ContactImageSendStatus.NOT_DUE,
        var interceptionStatus: ContactInterceptionStatus = ContactInterceptionStatus.NOT_DUE,
        var pendingRecoveryAction: ContactPendingRecoveryAction = ContactPendingRecoveryAction.NONE,
        var followUpCheckpoint: ContactFollowUpCheckpoint? = null
    )

    private val contacts = LinkedHashMap<String, ContactState>()

    init {
        require(defaultInterceptionThreshold >= 0) { "default interception threshold must not be negative" }
        require(defaultImageThreshold >= 0) { "default image threshold must not be negative" }
        repository.loadAll().forEach { entry ->
            contacts[entry.contactKeyHash] = ContactState(
                successfulSendConfirmationHashes = LinkedHashSet(entry.confirmedSendEventHashes),
                imageStatus = entry.imageStatus,
                interceptionStatus = entry.interceptionStatus,
                pendingRecoveryAction = entry.pendingRecoveryAction,
                followUpCheckpoint = entry.followUpCheckpoint
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

        val previous = contacts[contactHash] ?: ContactState()
        val state = previous.copy(successfulSendConfirmationHashes = LinkedHashSet(previous.successfulSendConfirmationHashes))
        if (interceptionThreshold > 0 && state.interceptionStatus != ContactInterceptionStatus.NOT_DUE) {
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
            state.interceptionStatus = ContactInterceptionStatus.PENDING_ENABLE
        }
        val imageReachedNow = imageThreshold > 0 && state.imageStatus == ContactImageSendStatus.NOT_DUE && count >= imageThreshold
        if (imageReachedNow) {
            state.imageStatus = ContactImageSendStatus.PENDING_SEND
        }
        state.pendingRecoveryAction = when {
            state.imageStatus == ContactImageSendStatus.PENDING_SEND -> ContactPendingRecoveryAction.SEND_IMAGE
            state.interceptionStatus == ContactInterceptionStatus.PENDING_ENABLE -> ContactPendingRecoveryAction.ENABLE_INTERCEPTION
            else -> ContactPendingRecoveryAction.NONE
        }
        if (state.pendingRecoveryAction != previous.pendingRecoveryAction) {
            state.followUpCheckpoint = initialCheckpoint(contactHash, hashConfirmationId(confirmationId), state.pendingRecoveryAction)
        }
        if (!persist(contactHash, state)) {
            return ContactSendCountUpdate(ContactSendRecordStatus.PERSISTENCE_FAILED,
                previous.successfulSendConfirmationHashes.size, interceptionThreshold, false, false)
        }
        return ContactSendCountUpdate(
            ContactSendRecordStatus.RECORDED,
            count,
            interceptionThreshold,
            thresholdReachedNow = reachedNow,
            shouldPauseContact = reachedNow,
            imageThresholdReachedNow = imageReachedNow
        )
    }

    @Synchronized
    fun followUpCheckpoint(contactIdentityKey: String?): ContactFollowUpCheckpoint? {
        val key = hashContactKey(contactIdentityKey) ?: return null
        return contacts[key]?.followUpCheckpoint
    }

    /** CAS of the existing action checkpoint. Caller supplies freshly reconciled page evidence
     * and retains the existing task lease; this method does not itself authorize a UI write. */
    @Synchronized
    fun updateFollowUpCheckpoint(
        contactIdentityKey: String?,
        expected: ContactFollowUpCheckpoint,
        step: ContactFollowUpStep,
        reconciliationRequired: Boolean
    ): Boolean {
        val key = hashContactKey(contactIdentityKey) ?: return false
        val state = contacts[key] ?: return false
        if (state.followUpCheckpoint != expected || step.action != state.pendingRecoveryAction ||
            expected.revision == Long.MAX_VALUE) return false
        return persist(key, state.copy(followUpCheckpoint = expected.copy(
            step = step, revision = expected.revision + 1, reconciliationRequired = reconciliationRequired
        )))
    }

    /** Only positive external proof for this exact contact/action may complete the image. */
    @Synchronized
    fun confirmImageSent(contactIdentityKey: String?, expected: ContactFollowUpCheckpoint?): Boolean {
        val key = hashContactKey(contactIdentityKey) ?: return false
        val state = contacts[key] ?: return false
        if (expected == null || state.followUpCheckpoint != expected ||
            state.imageStatus != ContactImageSendStatus.PENDING_SEND ||
            state.pendingRecoveryAction != ContactPendingRecoveryAction.SEND_IMAGE) return false
        val nextAction = if (state.interceptionStatus == ContactInterceptionStatus.PENDING_ENABLE) {
            ContactPendingRecoveryAction.ENABLE_INTERCEPTION
        } else ContactPendingRecoveryAction.NONE
        return persist(key, state.copy(
            imageStatus = ContactImageSendStatus.SENT_CONFIRMED,
            pendingRecoveryAction = nextAction,
            followUpCheckpoint = initialCheckpoint(key, expected.transactionId, nextAction)
        ))
    }

    /** Only independently verified ON and completed return may finish this exact action. */
    @Synchronized
    fun confirmInterceptionEnabled(contactIdentityKey: String?, expected: ContactFollowUpCheckpoint?): Boolean {
        val key = hashContactKey(contactIdentityKey) ?: return false
        val state = contacts[key] ?: return false
        if (expected == null || state.followUpCheckpoint != expected ||
            state.interceptionStatus != ContactInterceptionStatus.PENDING_ENABLE ||
            state.imageStatus == ContactImageSendStatus.PENDING_SEND ||
            state.pendingRecoveryAction != ContactPendingRecoveryAction.ENABLE_INTERCEPTION) return false
        return persist(key, state.copy(
            interceptionStatus = ContactInterceptionStatus.ENABLED,
            pendingRecoveryAction = ContactPendingRecoveryAction.NONE,
            followUpCheckpoint = null
        ))
    }

    @Synchronized
    fun successfulSendCount(contactIdentityKey: String?): Int {
        val key = hashContactKey(contactIdentityKey) ?: return 0
        return contacts[key]?.successfulSendConfirmationHashes?.size ?: 0
    }

    @Synchronized
    fun clearAll() {
        repository.clearAll()
        contacts.clear()
    }

    @Synchronized
    fun pendingRecoveryAction(contactIdentityKey: String?): ContactPendingRecoveryAction {
        val key = hashContactKey(contactIdentityKey) ?: return ContactPendingRecoveryAction.NONE
        return contacts[key]?.pendingRecoveryAction ?: ContactPendingRecoveryAction.NONE
    }

    private fun initialCheckpoint(
        contactHash: String, seed: String, action: ContactPendingRecoveryAction
    ): ContactFollowUpCheckpoint? {
        val step = when (action) {
            ContactPendingRecoveryAction.SEND_IMAGE -> ContactFollowUpStep.IMAGE_OPEN_ALBUM
            ContactPendingRecoveryAction.ENABLE_INTERCEPTION -> ContactFollowUpStep.INTERCEPTION_OPEN_SETTINGS
            ContactPendingRecoveryAction.NONE -> return null
        }
        return ContactFollowUpCheckpoint(hashConfirmationId("$contactHash|$seed|${action.name}"), step)
    }

    /** Publish the new in-memory state only after the repository has saved it. */
    private fun persist(contactHash: String, state: ContactState): Boolean {
        val saved = runCatching {
            repository.save(ContactMessageLedgerEntry(
                contactKeyHash = contactHash,
                confirmedTextCount = state.successfulSendConfirmationHashes.size,
                confirmedSendEventHashes = state.successfulSendConfirmationHashes.toSet(),
                imageStatus = state.imageStatus,
                interceptionStatus = state.interceptionStatus,
                pendingRecoveryAction = state.pendingRecoveryAction,
                followUpCheckpoint = state.followUpCheckpoint
            ))
        }.isSuccess
        if (saved) contacts[contactHash] = state
        return saved
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
