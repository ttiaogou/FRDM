package com.freedom.data.database

import com.freedom.features.aireply.ai.ReplyTaskRecord
import com.freedom.features.aireply.ai.ReplyTaskRepository
import com.freedom.features.aireply.ai.ReplyTaskStatus
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Small durable idempotency ledger; it stores request metadata, never OCR text. */
internal class LocalReplyTaskRepository(private val file: File) : ReplyTaskRepository {
    private val lock = Any()
    private val records = linkedMapOf<String, ReplyTaskRecord>()
    private var loaded = false

    override fun find(requestKey: String): ReplyTaskRecord? = synchronized(lock) {
        loadIfNeeded(); records[hash(requestKey)]
    }

    override fun save(record: ReplyTaskRecord) = synchronized(lock) {
        loadIfNeeded(); records[hash(record.requestKey)] = record; flush()
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        file.forEachLine { line -> runCatching {
            val f = line.split('\t'); if (f.size != 8) return@runCatching
            records[f[0]] = ReplyTaskRecord(unpack(f[1]), unpack(f[2]), unpack(f[3]), unpack(f[4]), ReplyTaskStatus.valueOf(f[5]), unpack(f[6]), f[7].toLong())
        } }
    }

    private fun flush() {
        file.parentFile?.mkdirs()
        file.writeText(records.map { (key, value) -> listOf(key, pack(value.requestKey), pack(value.conversationKey), pack(value.logicalMessageId), pack(value.contentRevision), value.status.name, pack(value.requestId), value.updatedAtMillis.toString()).joinToString("\t") }.joinToString("\n"), StandardCharsets.UTF_8)
    }

    private fun pack(value: String) = Base64.getUrlEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun unpack(value: String) = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
