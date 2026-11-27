package com.freedom.features.aireply.domain.contact

/** Durable, redacted contact state for confirmed text sends and resumable follow-up actions. */
data class ContactMessageLedgerEntry(
    val contactKeyHash: String,
    val confirmedTextCount: Int = 0,
    val confirmedSendEventHashes: Set<String> = emptySet(),
    val imageStatus: ContactImageSendStatus = ContactImageSendStatus.NOT_DUE,
    val interceptionStatus: ContactInterceptionStatus = ContactInterceptionStatus.NOT_DUE,
    val pendingRecoveryAction: ContactPendingRecoveryAction = ContactPendingRecoveryAction.NONE,
    val followUpCheckpoint: ContactFollowUpCheckpoint? = null
) {
    init {
        require(contactKeyHash.isNotBlank())
        require(confirmedTextCount >= 0)
        require(confirmedTextCount == confirmedSendEventHashes.size)
        require((pendingRecoveryAction == ContactPendingRecoveryAction.NONE) == (followUpCheckpoint == null))
        require(followUpCheckpoint == null || followUpCheckpoint.step.action == pendingRecoveryAction)
    }
}

enum class ContactImageSendStatus { NOT_DUE, PENDING_SEND, SENT_CONFIRMED }
enum class ContactInterceptionStatus { NOT_DUE, PENDING_ENABLE, ENABLED }
enum class ContactPendingRecoveryAction { NONE, SEND_IMAGE, ENABLE_INTERCEPTION }

/** Durable business progress only. Never persists a window, lease, node, or gesture coordinate. */
enum class ContactFollowUpStep(val action: ContactPendingRecoveryAction) {
    IMAGE_OPEN_ALBUM(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_OPEN_DIRECTORY(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_OPEN_FREEDOM(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_SELECT(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_SEND(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_VERIFY(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_RETRY_OPEN(ContactPendingRecoveryAction.SEND_IMAGE),
    IMAGE_RETRY_CONFIRM(ContactPendingRecoveryAction.SEND_IMAGE),
    INTERCEPTION_OPEN_SETTINGS(ContactPendingRecoveryAction.ENABLE_INTERCEPTION),
    INTERCEPTION_OPEN_CONTROL(ContactPendingRecoveryAction.ENABLE_INTERCEPTION),
    INTERCEPTION_SWITCH(ContactPendingRecoveryAction.ENABLE_INTERCEPTION),
    INTERCEPTION_VERIFY(ContactPendingRecoveryAction.ENABLE_INTERCEPTION),
    INTERCEPTION_DISMISS(ContactPendingRecoveryAction.ENABLE_INTERCEPTION),
    INTERCEPTION_BACK(ContactPendingRecoveryAction.ENABLE_INTERCEPTION),
    INTERCEPTION_RETURN(ContactPendingRecoveryAction.ENABLE_INTERCEPTION)
}

/** A compare-and-set token for a single contact/action, not permission to replay a tap.
 * Persist reconciliationRequired before attempting a mutation; after restart, fresh evidence
 * must reconcile the possible effect before the caller may retry or advance.
 */
data class ContactFollowUpCheckpoint(
    val transactionId: String,
    val step: ContactFollowUpStep,
    val revision: Long = 0,
    val reconciliationRequired: Boolean = false
) {
    init {
        require(transactionId.matches(Regex("[0-9a-f]{64}")))
        require(revision >= 0)
    }
}

interface ContactMessageLedgerRepository {
    fun loadAll(): List<ContactMessageLedgerEntry>
    /** Returns only after durable storage succeeds; throws on write failure. */
    fun save(entry: ContactMessageLedgerEntry)
    fun clear(contactKeyHash: String)
    fun clearAll()
}

/** Test/default implementation. Android composition replaces this with durable storage. */
class InMemoryContactMessageLedgerRepository : ContactMessageLedgerRepository {
    private val entries = LinkedHashMap<String, ContactMessageLedgerEntry>()
    override fun loadAll(): List<ContactMessageLedgerEntry> = entries.values.toList()
    override fun save(entry: ContactMessageLedgerEntry) { entries[entry.contactKeyHash] = entry }
    override fun clear(contactKeyHash: String) { entries.remove(contactKeyHash) }
    override fun clearAll() { entries.clear() }
}
