package com.freedom.data

import android.content.Context
import com.freedom.features.aireply.domain.contact.ContactImageSendStatus
import com.freedom.features.aireply.domain.contact.ContactInterceptionStatus
import com.freedom.features.aireply.domain.contact.ContactMessageLedgerEntry
import com.freedom.features.aireply.domain.contact.ContactMessageLedgerRepository
import com.freedom.features.aireply.domain.contact.ContactPendingRecoveryAction
import com.freedom.features.aireply.domain.contact.ContactFollowUpCheckpoint
import com.freedom.features.aireply.domain.contact.ContactFollowUpStep

/** SharedPreferences implementation containing hashes and state names only, never chat content. */
class ContactMessageLedgerDataStore(context: Context) : ContactMessageLedgerRepository {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun loadAll(): List<ContactMessageLedgerEntry> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { decode(key, it) }
    }

    override fun save(entry: ContactMessageLedgerEntry) {
        check(preferences.edit().putString(entry.contactKeyHash, encode(entry)).commit()) {
            "Private contact ledger write failed"
        }
    }

    override fun clear(contactKeyHash: String) {
        check(preferences.edit().remove(contactKeyHash).commit()) { "Private contact ledger clear failed" }
    }
    override fun clearAll() {
        check(preferences.edit().clear().commit()) { "Private contact ledger clear failed" }
    }

    private fun encode(entry: ContactMessageLedgerEntry): String = listOf(
        entry.confirmedTextCount,
        entry.confirmedSendEventHashes.sorted().joinToString(","),
        entry.imageStatus.name,
        entry.interceptionStatus.name,
        entry.pendingRecoveryAction.name,
        entry.followUpCheckpoint?.transactionId.orEmpty(),
        entry.followUpCheckpoint?.step?.name.orEmpty(),
        entry.followUpCheckpoint?.revision?.toString().orEmpty(),
        entry.followUpCheckpoint?.reconciliationRequired?.toString().orEmpty()
    ).joinToString("|")

    private fun decode(key: String, value: String): ContactMessageLedgerEntry? = runCatching {
        val parts = value.split("|", limit = 9)
        if (parts.size != 9) return@runCatching null
        val checkpoint = if (parts[5].isBlank()) {
            require(parts.drop(5).all(String::isEmpty))
            null
        } else ContactFollowUpCheckpoint(
            transactionId = parts[5],
            step = ContactFollowUpStep.valueOf(parts[6]),
            revision = parts[7].toLong(),
            reconciliationRequired = parts[8].toBooleanStrict()
        )
        val hashes = parts[1].split(',').filter(String::isNotBlank).toSet()
        ContactMessageLedgerEntry(
            contactKeyHash = key,
            confirmedTextCount = parts[0].toInt(),
            confirmedSendEventHashes = hashes,
            imageStatus = ContactImageSendStatus.valueOf(parts[2]),
            interceptionStatus = ContactInterceptionStatus.valueOf(parts[3]),
            pendingRecoveryAction = ContactPendingRecoveryAction.valueOf(parts[4]),
            followUpCheckpoint = checkpoint
        )
    }.getOrNull()

    // The old store mixed greetings and private replies (including weak title keys).
    // Keep it untouched; its ambiguous history cannot be safely imported as private counts.
    private companion object { const val NAME = "freedom_private_contact_message_ledger" }
}
