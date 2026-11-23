package com.freedom.features.aireply.domain.contact

/** Durable, redacted contact state for confirmed text sends and resumable follow-up actions. */
data class ContactMessageLedgerEntry(
    val contactKeyHash: String,
    val confirmedTextCount: Int = 0,
    val confirmedSendEventHashes: Set<String> = emptySet(),
    val imageStatus: ContactImageSendStatus = ContactImageSendStatus.NOT_DUE,
    val interceptionStatus: ContactInterceptionStatus = ContactInterceptionStatus.NOT_DUE,
    val pendingRecoveryAction: ContactPendingRecoveryAction = ContactPendingRecoveryAction.NONE
) {
    init {
        require(contactKeyHash.isNotBlank())
        require(confirmedTextCount >= 0)
        require(confirmedTextCount == confirmedSendEventHashes.size)
    }
}

enum class ContactImageSendStatus { NOT_DUE, PENDING_SEND, SENT_CONFIRMED }
enum class ContactInterceptionStatus { NOT_DUE, PENDING_ENABLE, ENABLED }
enum class ContactPendingRecoveryAction { NONE, SEND_IMAGE, ENABLE_INTERCEPTION }

interface ContactMessageLedgerRepository {
    fun loadAll(): List<ContactMessageLedgerEntry>
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
