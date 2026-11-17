package com.freedom.data.database

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.domain.message.MessageStoreResult
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import com.freedom.features.conversation.ports.ConversationRepository
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * App-private conversation store. Complete TSV snapshots are encrypted with an Android Keystore
 * AES-GCM key before they are written. Version-1 Base64-only files are migrated in place on read.
 */
internal class LocalConversationRepository(
    private val directory: File,
    private val cipher: ConversationStoreCipher = AndroidKeystoreConversationStoreCipher(),
    private val retentionPolicy: ConversationStoreRetentionPolicy = ConversationStoreRetentionPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ConversationRepository {
    private val contactsFile = File(directory, CONTACTS_FILE_NAME)
    private val messagesFile = File(directory, MESSAGES_FILE_NAME)

    private var contacts = loadContacts()
    private var messages = loadMessages()

    init {
        pruneExpiredData(nowMillis())
    }

    @Synchronized
    override fun findContactsByIdentityKeyHash(identityKeyHash: String): List<ContactProfile> {
        pruneExpiredData(nowMillis())
        return contacts.values.filter { it.identityKeyHash == identityKeyHash }
    }

    override fun upsertContact(profile: ContactProfile) = upsertContacts(listOf(profile))

    @Synchronized
    override fun upsertContacts(profiles: List<ContactProfile>) {
        pruneExpiredData(nowMillis())
        if (profiles.isEmpty()) return
        val updated = LinkedHashMap(contacts)
        profiles.forEach { profile ->
            require(profile.contactId.isNotBlank() && profile.identityKeyHash.isNotBlank())
            updated[profile.contactId] = profile
        }
        if (updated != contacts) {
            writeContacts(updated.values)
            contacts = updated
        }
    }

    @Synchronized
    override fun persistMessages(
        contactId: String,
        conversationId: String,
        identityConfidence: String,
        capturedAtMillis: Long,
        messages: List<ConversationMessageInput>
    ): MessageStoreResult {
        pruneExpiredData(nowMillis())
        if (contactId.isBlank() || conversationId.isBlank()) {
            return MessageStoreResult(rejectedCount = messages.size)
        }
        if (messages.isEmpty()) return MessageStoreResult()

        val updated = LinkedHashMap(this.messages)
        val occurrenceCounters = HashMap<String, Int>()
        var inserted = 0
        var duplicateObservations = 0
        var rejected = 0

        messages.forEach { message ->
            val fingerprint = message.messageFingerprint.trim()
            if (fingerprint.isBlank()) {
                rejected++
                return@forEach
            }
            val payloadHash = sha256(
                listOf(message.direction, message.type, message.contentQuality, message.content.orEmpty())
                    .joinToString("\u001f")
            )
            val occurrenceKey = "$fingerprint\u001f$payloadHash"
            val occurrence = occurrenceCounters.getOrDefault(occurrenceKey, 0)
            occurrenceCounters[occurrenceKey] = occurrence + 1
            val recordId = sha256(
                listOf(contactId, conversationId, fingerprint, payloadHash, occurrence.toString())
                    .joinToString("\u001f")
            )
            val logicalExisting = updated.values.firstOrNull {
                it.contactId == contactId && it.conversationId == conversationId &&
                    it.messageFingerprint == fingerprint && it.identityConfidence == identityConfidence
            }
            if (logicalExisting != null) {
                updated[logicalExisting.recordId] = logicalExisting.copy(
                    direction = message.direction,
                    type = message.type,
                    content = message.content,
                    contentQuality = message.contentQuality,
                    directionEvidence = message.directionEvidence,
                    bounds = message.bounds,
                    contentRevision = message.contentRevision,
                    observationBatchId = message.observationBatchId,
                    observationRowIndex = message.observationRowIndex,
                    lastObservedAtMillis = maxOf(logicalExisting.lastObservedAtMillis, capturedAtMillis),
                    observationCount = logicalExisting.observationCount + 1
                )
                duplicateObservations++
                return@forEach
            }
            val existing = updated[recordId]
            if (existing == null) {
                updated[recordId] = StoredConversationMessage(
                    recordId = recordId,
                    contactId = contactId,
                    conversationId = conversationId,
                    messageFingerprint = fingerprint,
                    direction = message.direction,
                    type = message.type,
                    content = message.content,
                    contentQuality = message.contentQuality,
                    directionEvidence = message.directionEvidence,
                    bounds = message.bounds,
                    contentRevision = message.contentRevision,
                    observationBatchId = message.observationBatchId,
                    observationRowIndex = message.observationRowIndex,
                    identityConfidence = identityConfidence,
                    firstObservedAtMillis = capturedAtMillis,
                    lastObservedAtMillis = capturedAtMillis,
                    observationCount = 1
                )
                inserted++
            } else {
                updated[recordId] = existing.copy(
                    lastObservedAtMillis = maxOf(existing.lastObservedAtMillis, capturedAtMillis),
                    observationCount = existing.observationCount + 1,
                    identityConfidence = identityConfidence,
                    content = message.content,
                    contentQuality = message.contentQuality,
                    directionEvidence = message.directionEvidence,
                    bounds = message.bounds,
                    contentRevision = message.contentRevision,
                    observationBatchId = message.observationBatchId,
                    observationRowIndex = message.observationRowIndex
                )
                duplicateObservations++
            }
        }

        if (updated != this.messages) {
            writeMessages(updated.values)
            this.messages = updated
        }
        return MessageStoreResult(inserted, duplicateObservations, rejected)
    }

    @Synchronized
    override fun contactsSnapshot(): List<ContactProfile> {
        pruneExpiredData(nowMillis())
        return contacts.values.toList()
    }

    @Synchronized
    override fun messagesForContact(contactId: String): List<StoredConversationMessage> {
        pruneExpiredData(nowMillis())
        return messages.values.filter { it.contactId == contactId }
            .sortedWith(compareBy<StoredConversationMessage> { it.firstObservedAtMillis }.thenBy { it.recordId })
    }

    @Synchronized
    override fun messagesForConversation(
        contactId: String,
        conversationId: String
    ): List<StoredConversationMessage> {
        pruneExpiredData(nowMillis())
        return messages.values
            .filter { it.contactId == contactId && it.conversationId == conversationId }
            .sortedWith(
                compareBy<StoredConversationMessage> { it.firstObservedAtMillis }
                    // Every message in one visible batch shares capturedAtMillis. The validated
                    // message parser emits rows top-to-bottom, so screen position preserves the
                    // conversation order within that observation.
                    .thenBy { it.observationBatchId }
                    .thenBy { it.observationRowIndex ?: Int.MAX_VALUE }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.bottom }
                    .thenBy { it.recordId }
            )
    }

    private fun loadContacts(): LinkedHashMap<String, ContactProfile> {
        val result = LinkedHashMap<String, ContactProfile>()
        val stored = readStore(contactsFile) ?: return result
        stored.records.forEach { line ->
            val fields = line.split('\t')
            if (fields.size != CONTACT_FIELD_COUNT || fields[0] != RECORD_CONTACT) return@forEach
            runCatching {
                val profile = ContactProfile(
                    contactId = decode(fields[1]),
                    identityKeyHash = decode(fields[2]),
                    displayName = decode(fields[3]),
                    identitySource = decode(fields[4]),
                    confidence = enumValueOf(decode(fields[5])),
                    firstSeenAtMillis = fields[6].toLong(),
                    lastSeenAtMillis = fields[7].toLong()
                )
                result[profile.contactId] = profile
            }
        }
        if (stored.legacyBase64Only) writeContacts(result.values)
        return result
    }

    private fun loadMessages(): LinkedHashMap<String, StoredConversationMessage> {
        val result = LinkedHashMap<String, StoredConversationMessage>()
        val stored = readStore(messagesFile) ?: return result
        stored.records.forEach { line ->
            val fields = line.split('\t')
            if ((fields.size != LEGACY_MESSAGE_FIELD_COUNT && fields.size != MESSAGE_FIELD_COUNT) ||
                fields[0] != RECORD_MESSAGE
            ) return@forEach
            runCatching {
                val message = StoredConversationMessage(
                    recordId = decode(fields[1]),
                    contactId = decode(fields[2]),
                    conversationId = decode(fields[3]),
                    messageFingerprint = decode(fields[4]),
                    direction = decode(fields[5]),
                    type = decode(fields[6]),
                    content = decode(fields[7]).ifEmpty { null },
                    contentQuality = decode(fields[8]),
                    directionEvidence = decode(fields[9]),
                    bounds = ScreenRect(
                        fields[10].toInt(), fields[11].toInt(), fields[12].toInt(), fields[13].toInt()
                    ),
                    identityConfidence = decode(fields[14]),
                    firstObservedAtMillis = fields[15].toLong(),
                    lastObservedAtMillis = fields[16].toLong(),
                    observationCount = fields[17].toInt(),
                    contentRevision = fields.getOrNull(18)?.let { decode(it) }.orEmpty(),
                    observationBatchId = fields.getOrNull(19)?.let { decode(it) }.orEmpty(),
                    observationRowIndex = fields.getOrNull(20)?.takeIf(String::isNotBlank)?.toIntOrNull()
                )
                result[message.recordId] = message
            }
        }
        if (stored.legacyBase64Only) writeMessages(result.values)
        return result
    }

    /** Reads v2 encrypted snapshots and reports v1 files so they can be migrated after parsing. */
    private fun readStore(file: File): StoredFile? {
        if (!file.isFile) return null
        val fileText = file.readText(StandardCharsets.UTF_8)
        val header = fileText.substringBefore('\n').removeSuffix("\r")
        val body = fileText.substringAfter('\n', "")
        return when (header) {
            LEGACY_STORE_HEADER -> StoredFile(body.splitLines(), legacyBase64Only = true)
            ENCRYPTED_STORE_HEADER -> {
                val payloadLines = body.splitLines()
                require(payloadLines.size == 1) { "会话存储文件加密载荷格式无效" }
                val encrypted = Base64.getUrlDecoder().decode(payloadLines.single())
                val plaintext = cipher.decrypt(encrypted)
                StoredFile(String(plaintext, StandardCharsets.UTF_8).splitLines(), legacyBase64Only = false)
            }
            else -> throw IllegalStateException("无法识别会话存储文件格式：${file.name}")
        }
    }

    private fun writeContacts(values: Collection<ContactProfile>) {
        writeEncryptedStore(contactsFile, values.map { profile ->
            listOf(
                RECORD_CONTACT,
                encode(profile.contactId),
                encode(profile.identityKeyHash),
                encode(profile.displayName),
                encode(profile.identitySource),
                encode(profile.confidence.name),
                profile.firstSeenAtMillis.toString(),
                profile.lastSeenAtMillis.toString()
            ).joinToString("\t")
        })
    }

    private fun writeMessages(values: Collection<StoredConversationMessage>) {
        writeEncryptedStore(messagesFile, values.map { message ->
            listOf(
                RECORD_MESSAGE,
                encode(message.recordId),
                encode(message.contactId),
                encode(message.conversationId),
                encode(message.messageFingerprint),
                encode(message.direction),
                encode(message.type),
                encode(message.content.orEmpty()),
                encode(message.contentQuality),
                encode(message.directionEvidence),
                message.bounds.left.toString(),
                message.bounds.top.toString(),
                message.bounds.right.toString(),
                message.bounds.bottom.toString(),
                encode(message.identityConfidence),
                message.firstObservedAtMillis.toString(),
                message.lastObservedAtMillis.toString(),
                message.observationCount.toString(),
                encode(message.contentRevision),
                encode(message.observationBatchId),
                message.observationRowIndex?.toString().orEmpty()
            ).joinToString("\t")
        })
    }

    private fun writeEncryptedStore(file: File, records: List<String>) {
        val plaintext = records.joinToString(separator = "\n", postfix = if (records.isEmpty()) "" else "\n")
            .toByteArray(StandardCharsets.UTF_8)
        val encrypted = cipher.encrypt(plaintext)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
        writeAtomically(file, listOf(ENCRYPTED_STORE_HEADER, encoded))
    }

    private fun pruneExpiredData(now: Long) {
        val cutoff = now - retentionPolicy.maxAgeMillis
        val retainedMessages = LinkedHashMap<String, StoredConversationMessage>().apply {
            messages.values.filter { it.firstObservedAtMillis >= cutoff }
                .forEach { put(it.recordId, it) }
        }
        if (retainedMessages != messages) {
            writeMessages(retainedMessages.values)
            messages = retainedMessages
        }

        val contactsWithRetainedMessages = messages.values.mapTo(HashSet()) { it.contactId }
        val retainedContacts = LinkedHashMap<String, ContactProfile>().apply {
            contacts.values.filter { it.lastSeenAtMillis >= cutoff || it.contactId in contactsWithRetainedMessages }
                .forEach { put(it.contactId, it) }
        }
        if (retainedContacts != contacts) {
            writeContacts(retainedContacts.values)
            contacts = retainedContacts
        }
    }

    private fun writeAtomically(file: File, records: List<String>) {
        check(directory.exists() || directory.mkdirs()) { "无法创建会话数据目录" }
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            val writer = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8))
            records.forEach { record ->
                writer.write(record)
                writer.newLine()
            }
            writer.flush()
            stream.fd.sync()
            writer.close()
        }
        try {
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(value: String): String = if (value.isEmpty()) EMPTY_VALUE else
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = if (value == EMPTY_VALUE) "" else
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class StoredFile(val records: List<String>, val legacyBase64Only: Boolean)

    private fun String.splitLines(): List<String> = lineSequence().map { it.removeSuffix("\r") }
        .filter { it.isNotEmpty() }.toList()

    private companion object {
        const val LEGACY_STORE_HEADER = "#FREEDOM_CONVERSATION_STORE\t1"
        const val ENCRYPTED_STORE_HEADER = "#FREEDOM_CONVERSATION_STORE\t2\tAES/GCM/NoPadding"
        const val CONTACTS_FILE_NAME = "contacts.v1.tsv"
        const val MESSAGES_FILE_NAME = "messages.v1.tsv"
        const val RECORD_CONTACT = "C"
        const val RECORD_MESSAGE = "M"
        const val EMPTY_VALUE = "-"
        const val CONTACT_FIELD_COUNT = 8
        const val LEGACY_MESSAGE_FIELD_COUNT = 18
        const val MESSAGE_FIELD_COUNT = 21
    }
}

/** Encryption is injected so storage and migration behavior can be tested without Android Keystore. */
internal interface ConversationStoreCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Local history is retained for 30 days from first observation, then removed on repository access. */
internal data class ConversationStoreRetentionPolicy(
    val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
) {
    init { require(maxAgeMillis > 0L) }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
