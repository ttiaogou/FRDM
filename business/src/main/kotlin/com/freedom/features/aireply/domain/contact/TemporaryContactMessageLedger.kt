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
internal class TemporaryContactMessageLedger(
    private val defaultInterceptionThreshold: Int = 0,
    private val defaultImageThreshold: Int = 0
) {
    private data class ContactState(
        val successfulSendConfirmationIds: LinkedHashSet<String> = LinkedHashSet(),
        var interceptionThresholdReached: Boolean = false,
        var imageThresholdReached: Boolean = false
    )

    private val contacts = LinkedHashMap<String, ContactState>()

    init {
        require(defaultInterceptionThreshold >= 0) { "default interception threshold must not be negative" }
        require(defaultImageThreshold >= 0) { "default image threshold must not be negative" }
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
                state.successfulSendConfirmationIds.size,
                interceptionThreshold,
                thresholdReachedNow = false,
                shouldPauseContact = true
            )
        }
        if (!state.successfulSendConfirmationIds.add(confirmationId)) {
            return ContactSendCountUpdate(
                ContactSendRecordStatus.DUPLICATE,
                state.successfulSendConfirmationIds.size,
                interceptionThreshold,
                thresholdReachedNow = false,
                shouldPauseContact = false
            )
        }

        val count = state.successfulSendConfirmationIds.size
        val reachedNow = interceptionThreshold > 0 && count >= interceptionThreshold
        if (reachedNow) state.interceptionThresholdReached = true
        val imageReachedNow = imageThreshold > 0 && !state.imageThresholdReached && count >= imageThreshold
        if (imageReachedNow) state.imageThresholdReached = true
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
        if (!state.interceptionThresholdReached) return false
        contacts.remove(key)
        return true
    }

    @Synchronized
    fun successfulSendCount(contactIdentityKey: String?): Int {
        val key = hashContactKey(contactIdentityKey) ?: return 0
        return contacts[key]?.successfulSendConfirmationIds?.size ?: 0
    }

    @Synchronized
    fun clearAll() {
        contacts.clear()
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

    private companion object {
        const val MAX_CONFIRMATION_ID_LENGTH = 128
    }
}
