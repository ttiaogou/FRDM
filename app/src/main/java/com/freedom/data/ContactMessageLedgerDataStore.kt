package com.freedom.data

import android.content.Context
import com.freedom.features.aireply.domain.contact.ContactImageSendStatus
import com.freedom.features.aireply.domain.contact.ContactInterceptionStatus
import com.freedom.features.aireply.domain.contact.ContactMessageLedgerEntry
import com.freedom.features.aireply.domain.contact.ContactMessageLedgerRepository
import com.freedom.features.aireply.domain.contact.ContactPendingRecoveryAction

/** SharedPreferences implementation containing hashes and state names only, never chat content. */
class ContactMessageLedgerDataStore(context: Context) : ContactMessageLedgerRepository {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun loadAll(): List<ContactMessageLedgerEntry> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { decode(key, it) }
    }

    override fun save(entry: ContactMessageLedgerEntry) {
        preferences.edit().putString(entry.contactKeyHash, encode(entry)).apply()
    }

    override fun clear(contactKeyHash: String) { preferences.edit().remove(contactKeyHash).apply() }
    override fun clearAll() { preferences.edit().clear().apply() }

    private fun encode(entry: ContactMessageLedgerEntry): String = listOf(
        entry.confirmedTextCount,
        entry.confirmedSendEventHashes.sorted().joinToString(","),
        entry.imageStatus.name,
        entry.interceptionStatus.name,
        entry.pendingRecoveryAction.name
    ).joinToString("|")

    private fun decode(key: String, value: String): ContactMessageLedgerEntry? = runCatching {
        val parts = value.split("|", limit = 5)
        if (parts.size != 5) return@runCatching null
        val hashes = parts[1].split(',').filter(String::isNotBlank).toSet()
        ContactMessageLedgerEntry(
            contactKeyHash = key,
            confirmedTextCount = parts[0].toInt(),
            confirmedSendEventHashes = hashes,
            imageStatus = ContactImageSendStatus.valueOf(parts[2]),
            interceptionStatus = ContactInterceptionStatus.valueOf(parts[3]),
            pendingRecoveryAction = ContactPendingRecoveryAction.valueOf(parts[4])
        )
    }.getOrNull()

    private companion object { const val NAME = "freedom_contact_message_ledger" }
}
