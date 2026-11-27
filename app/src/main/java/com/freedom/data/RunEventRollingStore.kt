package com.freedom.data

import com.freedom.core.logging.RunLogBudgetPolicy
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque

/**
 * Writer-thread-owned event spool. Normal chunks rotate; warning, critical and lifecycle
 * lanes have independent hard budgets. The sealed compatibility file is produced once,
 * atomically, before the spool is removed. A process restart can seal the same spool.
 */
internal class RunEventRollingStore(
    private val directory: File,
    private val normalLimit: Long = RunLogBudgetPolicy.MAX_NORMAL_EVENT_BYTES.toLong(),
    private val warningLimit: Long = RunLogBudgetPolicy.MAX_WARNING_EVENT_BYTES.toLong(),
    private val criticalLimit: Long = RunLogBudgetPolicy.MAX_CRITICAL_EVENT_EXTRA_BYTES.toLong(),
    private val lifecycleLimit: Long = RunLogBudgetPolicy.MAX_LIFECYCLE_EVENT_BYTES.toLong(),
    private val segmentLimit: Long = RunLogBudgetPolicy.NORMAL_EVENT_SEGMENT_BYTES.toLong()
) {
    enum class Lane { NORMAL, WARNING, CRITICAL, LIFECYCLE }
    enum class Status { WRITTEN, BUDGET_REJECTED, WRITE_FAILED }
    data class AppendResult(
        val status: Status,
        val evictedRecords: Long = 0,
        val evictedBytes: Long = 0
    )
    private data class Segment(val file: File, var records: Long = 0, var bytes: Long = 0)
    private val spool = File(directory, SPOOL)
    private val normal = ArrayDeque<Segment>()
    private var nextSegment = 0L
    private var normalBytes = 0L

    init {
        require(normalLimit > 0 && warningLimit > 0 && criticalLimit > 0 && lifecycleLimit > 0)
        require(segmentLimit > 0 && segmentLimit <= normalLimit)
    }

    fun append(lane: Lane, line: String): AppendResult {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var evictedRecords = 0L
        var evictedBytes = 0L
        fun result(status: Status) = AppendResult(status, evictedRecords, evictedBytes)
        return try {
            check(!File(directory, SEALED).exists()) { "sealed event store is immutable" }
            check(spool.isDirectory || spool.mkdirs()) { "cannot create diagnostic spool" }
            if (lane != Lane.NORMAL) {
                val (name, limit) = when (lane) {
                    Lane.WARNING -> "warning.jsonl" to warningLimit
                    Lane.CRITICAL -> "critical.jsonl" to criticalLimit
                    Lane.LIFECYCLE -> "lifecycle.jsonl" to lifecycleLimit
                    Lane.NORMAL -> error("unreachable")
                }
                val file = File(spool, name)
                if (file.length() + bytes.size > limit) return result(Status.BUDGET_REJECTED)
                appendRecord(file, bytes)
                return result(Status.WRITTEN)
            }
            if (bytes.size > normalLimit || bytes.size > segmentLimit) return result(Status.BUDGET_REJECTED)
            while (normalBytes + bytes.size > normalLimit) {
                val oldest = normal.peekFirst() ?: error("missing normal segment")
                check(oldest.file.delete()) { "cannot evict oldest normal segment" }
                normal.removeFirst()
                normalBytes -= oldest.bytes
                evictedBytes += oldest.bytes
                evictedRecords += oldest.records
            }
            val current = normal.peekLast()
            if (current == null || current.bytes + bytes.size > segmentLimit) {
                val next = Segment(File(spool, "normal-${nextSegment++}.jsonl"))
                appendRecord(next.file, bytes)
                next.bytes = bytes.size.toLong()
                next.records = 1
                normal.addLast(next)
            } else {
                appendRecord(current.file, bytes)
                current.bytes += bytes.size
                current.records++
            }
            normalBytes += bytes.size
            result(Status.WRITTEN)
        } catch (_: Exception) {
            result(Status.WRITE_FAILED)
        }
    }

    private fun appendRecord(file: File, bytes: ByteArray) {
        RandomAccessFile(file, "rw").use { output ->
            val before = output.length()
            try {
                output.seek(before)
                output.write(bytes)
            } catch (error: Exception) {
                runCatching { output.setLength(before) }
                throw error
            }
        }
    }

    companion object {
        private const val SPOOL = "event_segments"
        private const val SEALED = "events.jsonl"
        private const val TEMP = "events.jsonl.sealing"

        /** Reserve slots for WARN, then ERROR/FATAL and finally the seal barrier. */
        fun canEnqueue(lane: Lane, remainingCapacity: Int): Boolean =
            remainingCapacity > when (lane) {
                Lane.NORMAL -> 128
                Lane.WARNING -> 64
                else -> 1
            }

        /** Only owned JSONL segments participate. Legacy sealed runs remain readable. */
        fun sources(directory: File): List<File> {
            val sealed = File(directory, SEALED)
            if (sealed.isFile) return listOf(sealed)
            return File(directory, SPOOL).listFiles().orEmpty().filter {
                it.isFile && (it.name.matches(Regex("normal-[0-9]+\\.jsonl")) ||
                    it.name in setOf("warning.jsonl", "critical.jsonl", "lifecycle.jsonl"))
            }
        }

        /**
         * A crash before rename leaves all source segments; after rename the canonical file
         * wins, so interrupted cleanup cannot duplicate evidence. Sort by event sequence
         * because the separate lanes and dedup summaries can be written out of sequence.
         */
        fun seal(directory: File, sequenceOf: (String) -> Long) {
            val sealed = File(directory, SEALED)
            val spool = File(directory, SPOOL)
            if (sealed.isFile) {
                spool.deleteRecursively()
                File(directory, TEMP).delete()
                return
            }
            val files = sources(directory)
            val lines = files.flatMap { it.readLines(Charsets.UTF_8) }
                .filter(String::isNotBlank)
                .map { line -> sequenceOf(line) to line }
                .sortedBy { it.first }
            val temporary = File(directory, TEMP)
            try {
                temporary.bufferedWriter(Charsets.UTF_8).use { output ->
                    lines.forEach { (_, line) -> output.write(line); output.newLine() }
                }
                Files.move(temporary.toPath(), sealed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            spool.deleteRecursively()
        }
    }
}
