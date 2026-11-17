package com.freedom.data.database

import com.freedom.features.aireply.ai.ReplyTaskRecord
import com.freedom.features.aireply.ai.ReplyTaskRepository
import com.freedom.features.aireply.ai.ReplyTaskStatus
import android.os.Process
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

/** Durable request ledger with cross-process locking and atomic replacement writes. */
internal class LocalReplyTaskRepository(
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ReplyTaskRepository {
    private val processLock = Any()
    private val lockFile: File get() = File(file.parentFile, "${file.name}.lock")

    override fun find(requestKey: String): ReplyTaskRecord? = withFileLock {
        val records = readRecords()
        val key = hash(requestKey)
        val record = records[key] ?: return@withFileLock null
        if (record.status == ReplyTaskStatus.REQUESTED &&
            nowMillis() - record.updatedAtMillis >= REQUEST_TIMEOUT_MILLIS
        ) {
            val recovered = record.copy(status = ReplyTaskStatus.UNKNOWN, updatedAtMillis = nowMillis())
            records[key] = recovered
            writeRecords(records)
            recovered
        } else record
    }

    override fun save(record: ReplyTaskRecord) = withFileLock {
        val records = readRecords()
        records[hash(record.requestKey)] = record
        writeRecords(records)
    }

    override fun tryClaim(record: ReplyTaskRecord, requestTimeoutMillis: Long): Boolean = withFileLock {
        require(record.status == ReplyTaskStatus.REQUESTED) { "Only REQUESTED tasks can be claimed" }
        require(requestTimeoutMillis > 0L)
        val records = readRecords()
        val key = hash(record.requestKey)
        val existing = records[key]
        if (existing != null) {
            if (existing.status == ReplyTaskStatus.FAILED_RETRYABLE) {
                records[key] = record
                writeRecords(records)
                return@withFileLock true
            }
            val stale = existing.status == ReplyTaskStatus.REQUESTED &&
                nowMillis() - existing.updatedAtMillis >= requestTimeoutMillis
            if (stale) {
                records[key] = existing.copy(status = ReplyTaskStatus.UNKNOWN, updatedAtMillis = nowMillis())
                writeRecords(records)
            }
            return@withFileLock false
        }
        records[key] = record
        writeRecords(records)
        true
    }

    override fun updateStatus(
        requestKey: String,
        requestId: String,
        status: ReplyTaskStatus,
        updatedAtMillis: Long
    ): Boolean = withFileLock {
        val records = readRecords()
        val key = hash(requestKey)
        val existing = records[key] ?: return@withFileLock false
        if (existing.requestId != requestId) return@withFileLock false
        records[key] = existing.copy(status = status, updatedAtMillis = updatedAtMillis)
        writeRecords(records)
        true
    }

    private fun <T> withFileLock(block: () -> T): T = synchronized(processLock) {
        file.parentFile?.mkdirs()
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun readRecords(): LinkedHashMap<String, ReplyTaskRecord> {
        val result = LinkedHashMap<String, ReplyTaskRecord>()
        if (!file.isFile) return result
        file.forEachLine { line -> runCatching {
            val fields = line.split('\t')
            if (fields.size != FIELD_COUNT) return@runCatching
            result[fields[0]] = ReplyTaskRecord(
                requestKey = unpack(fields[1]),
                conversationKey = unpack(fields[2]),
                logicalMessageId = unpack(fields[3]),
                contentRevision = unpack(fields[4]),
                status = ReplyTaskStatus.valueOf(fields[5]),
                requestId = unpack(fields[6]),
                updatedAtMillis = fields[7].toLong()
            )
        } }
        return result
    }

    private fun writeRecords(records: Map<String, ReplyTaskRecord>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${Process.myPid()}.${Thread.currentThread().id}.tmp")
        val content = records.map { (key, value) ->
            listOf(
                key, pack(value.requestKey), pack(value.conversationKey), pack(value.logicalMessageId),
                pack(value.contentRevision), value.status.name, pack(value.requestId),
                value.updatedAtMillis.toString()
            ).joinToString("\t")
        }.joinToString("\n")
        temporary.writeText(content, StandardCharsets.UTF_8)
        FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pack(value: String): String = Base64.getUrlEncoder()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unpack(value: String): String = String(
        Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8
    )

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIELD_COUNT = 8
        const val REQUEST_TIMEOUT_MILLIS = 120_000L
    }
}
