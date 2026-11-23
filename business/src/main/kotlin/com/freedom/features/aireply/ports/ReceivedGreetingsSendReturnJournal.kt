package com.freedom.features.aireply.ports

/**
 * A durable, privacy-preserving reply outcome is independent of the expiring list-traversal
 * checkpoint. SEND_CONFIRMED prevents another reply, while RETURN_VERIFIED permits the list
 * cursor to advance only after the existing stable-list observation confirms safe return.
 */
enum class ReceivedGreetingsSendReturnPhase { SEND_CONFIRMED, RETURN_VERIFIED }

data class ReceivedGreetingsSendReturnRecord(
    val rowKeyHash: String,
    val contactIdHash: String,
    val sendConfirmationHash: String,
    val phase: ReceivedGreetingsSendReturnPhase,
    val savedAtEpochMs: Long = System.currentTimeMillis()
)

interface ReceivedGreetingsSendReturnRepository {
    /** Corrupt/unavailable storage must throw rather than silently appear empty. */
    fun loadAll(): List<ReceivedGreetingsSendReturnRecord>
    /** Must synchronously persist the complete transaction or return false. */
    fun save(record: ReceivedGreetingsSendReturnRecord): Boolean
}

class ReceivedGreetingsSendReturnJournal(private val repository: ReceivedGreetingsSendReturnRepository) {
    @Synchronized
    fun recordConfirmedSend(rowKeyHash: String, contactIdHash: String, confirmationHash: String): Boolean {
        if (rowKeyHash.isBlank() || contactIdHash.isBlank() || confirmationHash.isBlank()) return false
        val previous = repository.loadAll().firstOrNull { it.rowKeyHash == rowKeyHash }
        if (previous != null) {
            // RETURN_VERIFIED is terminal. A fresh send cannot reopen a completed row.
            return previous.phase == ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED &&
                previous.contactIdHash == contactIdHash &&
                previous.sendConfirmationHash == confirmationHash
        }
        return repository.save(
            ReceivedGreetingsSendReturnRecord(
                rowKeyHash, contactIdHash, confirmationHash, ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED
            )
        )
    }

    @Synchronized
    fun confirmVerifiedReturn(rowKeyHash: String, contactIdHash: String): Boolean {
        val previous = repository.loadAll().firstOrNull { it.rowKeyHash == rowKeyHash } ?: return false
        if (previous.contactIdHash != contactIdHash) return false
        if (previous.phase == ReceivedGreetingsSendReturnPhase.RETURN_VERIFIED) return true
        return repository.save(previous.copy(phase = ReceivedGreetingsSendReturnPhase.RETURN_VERIFIED))
    }

    fun findByRow(rowKeyHash: String): ReceivedGreetingsSendReturnRecord? =
        repository.loadAll().firstOrNull { it.rowKeyHash == rowKeyHash }

    fun hasConfirmedSendForContact(contactIdHash: String): Boolean =
        repository.loadAll().any { it.contactIdHash == contactIdHash }

    fun pendingReturns(): List<ReceivedGreetingsSendReturnRecord> =
        repository.loadAll().filter { it.phase == ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED }
}
