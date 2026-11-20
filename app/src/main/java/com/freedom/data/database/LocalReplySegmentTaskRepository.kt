package com.freedom.data.database

import com.freedom.features.aireply.ai.ReplySegmentTaskRecord
import com.freedom.features.aireply.ai.ReplySegmentTaskRepository
import com.freedom.features.aireply.ai.ReplySegmentTaskTransitions
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Durable segment ledger kept separate from the existing whole-request ledger. */
internal class LocalReplySegmentTaskRepository(private val file: File) : ReplySegmentTaskRepository {
    private val lock = Any()

    override fun find(taskId: String): ReplySegmentTaskRecord? = synchronized(lock) { read()[taskId] }

    override fun findByRequest(requestId: String): List<ReplySegmentTaskRecord> = synchronized(lock) {
        read().values.filter { it.requestId == requestId }.sortedBy { it.segmentIndex }
    }

    override fun save(record: ReplySegmentTaskRecord) = synchronized(lock) {
        val records = read(); require(record.taskId !in records) { "Duplicate segment task" }
        records[record.taskId] = record; write(records)
    }

    override fun update(record: ReplySegmentTaskRecord): Boolean = synchronized(lock) {
        val records = read(); val previous = records[record.taskId] ?: return false
        if (previous.requestId != record.requestId || !ReplySegmentTaskTransitions.isAllowed(previous.status, record.status)) return false
        records[record.taskId] = record; write(records); true
    }

    private fun read(): LinkedHashMap<String, ReplySegmentTaskRecord> {
        val result = LinkedHashMap<String, ReplySegmentTaskRecord>()
        if (!file.isFile) return result
        file.forEachLine { line -> runCatching {
            val f = line.split('\t'); if (f.size != 11) return@runCatching
            val record = ReplySegmentTaskRecord(
                taskId = unpack(f[0]), requestId = unpack(f[1]), conversationKey = unpack(f[2]),
                segmentIndex = f[3].toInt(), segmentCount = f[4].toInt(), segmentContentHash = f[5],
                status = com.freedom.features.aireply.ai.ReplySegmentTaskStatus.valueOf(f[6]),
                createdAt = f[7].toLong(), sentAt = f[8].toLongOrNull(), confirmationId = unpack(f[9]).ifBlank { null }
            )
            result[record.taskId] = record
        } }
        return result
    }

    private fun write(records: Map<String, ReplySegmentTaskRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(records.values.joinToString("\n") { r ->
            listOf(pack(r.taskId), pack(r.requestId), pack(r.conversationKey), r.segmentIndex, r.segmentCount,
                r.segmentContentHash, r.status.name, r.createdAt, r.sentAt ?: "", pack(r.confirmationId.orEmpty()), "v1").joinToString("\t")
        }, StandardCharsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun pack(value: String) = Base64.getUrlEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun unpack(value: String) = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
