package com.freedom.data

import com.freedom.core.logging.RunDiagnosticEvent
import com.freedom.core.logging.RunIncidentEvidencePolicy
import com.freedom.core.logging.RunIncidentEvidencePolicy.Relation
import com.freedom.core.logging.RunIncidentEvidencePolicy.Outcome
import com.freedom.core.logging.RunIncidentEvidencePolicy.TransactionKey
import com.freedom.core.logging.RunLogBudgetPolicy
import com.freedom.core.logging.RunLogLevel
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Bounded incident evidence capture, owned by the existing diagnostic writer.
 * No second recent-event buffer, scheduler, business state machine or UI access.
 * A capture window is not a recovery transaction or a verified business outcome.
 */
internal class RunIncidentEvidenceStore(
    private val runDirectory: File,
    private val encode: (RunDiagnosticEvent) -> String
) {
    data class Stats(
        val captured: Long = 0,
        val rejected: Long = 0,
        val writeFailures: Long = 0,
        val postLimited: Long = 0,
        val merged: Long = 0,
        val resolved: Long = 0,
        val unresolved: Long = 0,
        val interrupted: Long = 0
    )

    private data class Transaction(
        val state: Outcome = Outcome.OPEN,
        val occurrences: Long = 1,
        val lastTriggerSequence: Long,
        val lastTriggerAt: Long,
        val highestLevel: RunLogLevel,
        val lastEvidenceSequence: Long,
        val progressEvents: Long = 0,
        val lastProgressSequence: Long? = null,
        val lastProgressCode: String = "NONE",
        val endReason: String = "NONE"
    )

    private data class OpenCapture(
        val directory: File,
        val trigger: RunDiagnosticEvent,
        val level: RunLogLevel,
        val key: TransactionKey?,
        var transaction: Transaction,
        var afterRecords: Int = 0,
        var nearbyRecords: Int = 0,
        var nearbyBytes: Long = 0,
        var afterBytes: Long = 0
    )

    private val open = mutableListOf<OpenCapture>()
    // Capture windows and transaction lifetimes are independent. Bounded by claimed slots.
    private val transactions = mutableListOf<OpenCapture>()
    private val byKey = mutableMapOf<TransactionKey, OpenCapture>()
    private var claimed = 0
    private var warningClaims = 0
    private var nonFatalClaims = 0

    @Volatile var stats: Stats = Stats()
        private set

    /** Called in FIFO writer order, before adding this event to the existing recent buffer. */
    fun observe(event: RunDiagnosticEvent, recent: Collection<RunDiagnosticEvent>) {
        val key = RunIncidentEvidencePolicy.transactionKey(event)
        val level = RunIncidentEvidencePolicy.triggerLevel(event)
        val matching = key?.let(byKey::get)?.takeUnless {
            it.transaction.state in setOf(Outcome.RESOLVED, Outcome.INTERRUPTED)
        }
        var mergedTrigger = false
        if (matching != null && event.sequence > matching.transaction.lastEvidenceSequence) {
            val outcome = RunIncidentEvidencePolicy.outcome(event, requireNotNull(key))
            if (level != null || outcome != null) {
                val prior = matching.transaction
                val updated = prior.copy(
                    state = outcome ?: prior.state,
                    occurrences = prior.occurrences + if (level != null) 1L else 0L,
                    lastTriggerSequence = if (level != null) event.sequence else prior.lastTriggerSequence,
                    lastTriggerAt = if (level != null) event.timestampEpochMs else prior.lastTriggerAt,
                    highestLevel = if (level != null && level.ordinal > prior.highestLevel.ordinal) level else prior.highestLevel,
                    lastEvidenceSequence = event.sequence,
                    progressEvents = prior.progressEvents + if (outcome == Outcome.RECOVERING) 1L else 0L,
                    lastProgressSequence = if (outcome == Outcome.RECOVERING) event.sequence else prior.lastProgressSequence,
                    lastProgressCode = if (outcome == Outcome.RECOVERING) safeCode(event.reason ?: event.eventType)
                        else prior.lastProgressCode,
                    endReason = when (outcome) {
                        Outcome.RESOLVED, Outcome.INTERRUPTED, Outcome.UNRESOLVED -> "EXPLICIT_EVENT"
                        Outcome.RECOVERING -> "NONE"
                        else -> prior.endReason
                    }
                )
                if (persistTransaction(matching, updated, event)) {
                    if (level != null) stats = stats.copy(merged = stats.merged + 1)
                    if (updated.state in setOf(Outcome.RESOLVED, Outcome.INTERRUPTED)) byKey.remove(key)
                    refreshOutcomes()
                }
                // A failed summary update is an evidence failure, not permission to fabricate
                // another incident identity. Original trigger and previous summary survive.
                mergedTrigger = level != null
            }
        }
        for (capture in open.toList()) {
            val elapsed = event.elapsedRealtimeMs - capture.trigger.elapsedRealtimeMs
            if (elapsed > WINDOW_MS) {
                close(capture, "WINDOW_ELAPSED")
                continue
            }
            if (elapsed < 0 || event.sequence <= capture.trigger.sequence) continue
            if (mergedTrigger && capture === matching) continue
            val relation = RunIncidentEvidencePolicy.relation(capture.trigger, event) ?: continue
            if (relation == Relation.NEARBY_UNCORRELATED && capture.nearbyRecords >= MAX_NEARBY) continue
            val line = envelope("AFTER", relation.name, event)
            val size = bytes(line)
            // Uncorrelated background observations cannot exhaust the related-evidence budget
            // or terminate the window before a subsequent exact request/action match.
            if (relation == Relation.NEARBY_UNCORRELATED &&
                (capture.nearbyBytes + size > MAX_NEARBY_AFTER_BYTES ||
                    capture.afterBytes + size > RunLogBudgetPolicy.MAX_INCIDENT_AFTER_BYTES)
            ) continue
            if (capture.afterBytes + size > RunLogBudgetPolicy.MAX_INCIDENT_AFTER_BYTES) {
                close(capture, "POST_BYTE_LIMIT")
                continue
            }
            try {
                appendLine(File(capture.directory, TIMELINE), line)
                capture.afterBytes += size
                capture.afterRecords++
                if (relation == Relation.NEARBY_UNCORRELATED) {
                    capture.nearbyRecords++
                    capture.nearbyBytes += size
                }
                if (capture.afterRecords >= MAX_CONTEXT_RECORDS) close(capture, "POST_RECORD_LIMIT")
            } catch (_: Exception) {
                stats = stats.copy(writeFailures = stats.writeFailures + 1)
                close(capture, "WRITE_FAILED")
            }
        }

        if (level == null || mergedTrigger) return
        // Reserve four of the total slots for fatal events, and at least twelve more
        // for errors. Lower severities cannot exhaust all incident capacity.
        if (claimed >= RunLogBudgetPolicy.MAX_INCIDENT_CAPTURES ||
            (level == RunLogLevel.WARN && warningClaims >= MAX_WARNING_CAPTURES) ||
            (level != RunLogLevel.FATAL && nonFatalClaims >= MAX_NON_FATAL_CAPTURES)
        ) {
            stats = stats.copy(rejected = stats.rejected + 1)
            return
        }
        claimed++
        if (level == RunLogLevel.WARN) warningClaims++
        if (level != RunLogLevel.FATAL) nonFatalClaims++

        val directory = File(File(runDirectory, ROOT), "incident-${event.sequence}")
        try {
            check(directory.mkdirs()) { "cannot create unique incident directory" }
            val triggerLine = envelope("TRIGGER", "TRIGGER", event)
            check(bytes(triggerLine) <= RunLogBudgetPolicy.MAX_INCIDENT_TRIGGER_BYTES)
            val before = selectBefore(event, recent)
            val initial = (before.map { it.second } + triggerLine).joinToString("")
            writeAtomic(File(directory, TIMELINE), initial)
            event.errorDetail?.let { detail ->
                // Reuse only the already bounded/redacted exception evidence.
                val safe = RunLogBudgetPolicy.truncateUtf8(
                    RunLogBudgetPolicy.redactSensitivePayload(detail), RunLogBudgetPolicy.MAX_ERROR_DETAIL_BYTES
                )
                writeAtomic(File(directory, "error_detail.txt"), safe)
            }
            writeAtomic(File(directory, CAPTURE),
                """{"schemaVersion":1,"triggerSequence":${event.sequence},"level":"${level.name}","severitySource":"${event.severitySource.name}","beforeRecords":${before.size},"beforeRelated":${before.count { it.third != Relation.NEARBY_UNCORRELATED }},"windowMs":$WINDOW_MS,"maxAfterRecords":$MAX_CONTEXT_RECORDS,"captureStatus":"OPEN"}""")
            val capture = OpenCapture(directory, event, level, key,
                Transaction(state = key?.let { RunIncidentEvidencePolicy.outcome(event, it) } ?: Outcome.OPEN,
                    lastTriggerSequence = event.sequence, lastTriggerAt = event.timestampEpochMs,
                    highestLevel = level, lastEvidenceSequence = event.sequence,
                    endReason = if (key?.let { RunIncidentEvidencePolicy.outcome(event, it) } == Outcome.UNRESOLVED)
                        "EXPLICIT_EVENT" else "NONE"))
            check(persistTransaction(capture, capture.transaction, null)) { "initial transaction persistence failed" }
            transactions.add(capture)
            if (key != null) byKey[key] = capture
            stats = stats.copy(captured = stats.captured + 1)
            refreshOutcomes()
            val slotAvailable = open.size < MAX_OPEN &&
                (level != RunLogLevel.WARN || open.count { it.level == RunLogLevel.WARN } < MAX_OPEN_WARNINGS) &&
                (level == RunLogLevel.FATAL || open.count { it.level != RunLogLevel.FATAL } < MAX_OPEN - 1)
            if (slotAvailable) open.add(capture)
            else close(capture, "POST_WINDOW_LIMIT")
        } catch (_: Exception) {
            // Leave any successfully frozen files intact. Do not fail the ordinary event write.
            stats = stats.copy(writeFailures = stats.writeFailures + 1)
        }
    }

    private fun selectBefore(
        trigger: RunDiagnosticEvent, recent: Collection<RunDiagnosticEvent>
    ): List<Triple<Long, String, Relation>> {
        val candidates = recent.asSequence()
            .filter { it.sequence < trigger.sequence &&
                trigger.elapsedRealtimeMs - it.elapsedRealtimeMs in 0..WINDOW_MS }
            .mapNotNull { event ->
                RunIncidentEvidencePolicy.relation(trigger, event)?.let { event to it }
            }.sortedByDescending { it.first.sequence }.toList()
        val selected = mutableListOf<Triple<Long, String, Relation>>()
        var used = 0L
        fun take(nearby: Boolean, limit: Int) {
            var count = 0
            for ((event, relation) in candidates) {
                if ((relation == Relation.NEARBY_UNCORRELATED) != nearby || count >= limit) continue
                val line = envelope("BEFORE", relation.name, event)
                val size = bytes(line)
                if (used + size > RunLogBudgetPolicy.MAX_INCIDENT_BEFORE_BYTES) continue
                selected.add(Triple(event.sequence, line, relation))
                used += size
                count++
            }
        }
        take(false, MAX_CONTEXT_RECORDS - MAX_NEARBY)
        take(true, MAX_NEARBY)
        return selected.sortedBy { it.first }
    }

    private fun envelope(phase: String, relation: String, event: RunDiagnosticEvent): String =
        """{"phase":"$phase","relation":"$relation","event":${encode(event)}}""" + "\n"

    private fun close(capture: OpenCapture, status: String) {
        open.remove(capture)
        if (status == "POST_WINDOW_LIMIT" || status == "POST_BYTE_LIMIT") {
            stats = stats.copy(postLimited = stats.postLimited + 1)
        }
        try {
            writeEnd(capture.directory, status, capture.afterRecords)
        } catch (_: Exception) {
            stats = stats.copy(writeFailures = stats.writeFailures + 1)
        }
    }

    private fun safeCode(value: String): String =
        value.takeIf { it.length in 1..100 && it.matches(Regex("[A-Za-z0-9_.:-]+")) } ?: "UNKNOWN"

    private fun persistTransaction(capture: OpenCapture, updated: Transaction, evidence: RunDiagnosticEvent?): Boolean {
        return try {
            if (evidence != null) {
                val line = envelope("TRANSACTION", "EXACT_IDENTITY", evidence)
                check(bytes(line) <= RunLogBudgetPolicy.MAX_INCIDENT_TRIGGER_BYTES)
                // Latest proof is separately bounded and atomically replaced, never append-only.
                writeAtomic(File(capture.directory, LATEST), line)
            }
            writeAtomic(File(capture.directory, TRANSACTION),
                """{"schemaVersion":1,"state":"${updated.state.name}","occurrences":${updated.occurrences},"firstTriggerSequence":${capture.trigger.sequence},"firstTriggerAt":${capture.trigger.timestampEpochMs},"lastTriggerSequence":${updated.lastTriggerSequence},"lastTriggerAt":${updated.lastTriggerAt},"highestLevel":"${updated.highestLevel.name}","lastEvidenceSequence":${updated.lastEvidenceSequence},"progressEvents":${updated.progressEvents},"lastProgressSequence":${updated.lastProgressSequence ?: "null"},"lastProgressCode":"${updated.lastProgressCode}","endReason":"${updated.endReason}"}""")
            capture.transaction = updated
            true
        } catch (_: Exception) {
            stats = stats.copy(writeFailures = stats.writeFailures + 1)
            false
        }
    }

    private fun refreshOutcomes() {
        stats = stats.copy(
            resolved = transactions.count { it.transaction.state == Outcome.RESOLVED }.toLong(),
            interrupted = transactions.count { it.transaction.state == Outcome.INTERRUPTED }.toLong(),
            unresolved = transactions.count { it.transaction.state !in setOf(Outcome.RESOLVED, Outcome.INTERRUPTED) }.toLong()
        )
    }

    fun finish() {
        open.toList().forEach { close(it, "RUN_ENDED") }
        for (capture in transactions) {
            if (capture.transaction.state in setOf(Outcome.OPEN, Outcome.RECOVERING)) {
                persistTransaction(capture, capture.transaction.copy(state = Outcome.UNRESOLVED,
                    endReason = "RUN_ENDED_WITHOUT_PROOF"), null)
            }
        }
        refreshOutcomes()
        byKey.clear()
    }

    companion object {
        private const val ROOT = "incidents"
        private const val TIMELINE = "timeline.jsonl"
        private const val CAPTURE = "capture.json"
        private const val END = "capture_end.json"
        private const val TRANSACTION = "transaction.json"
        private const val LATEST = "latest_evidence.json"
        private const val WINDOW_MS = 30_000L
        private const val MAX_CONTEXT_RECORDS = 32
        private const val MAX_NEARBY = 8
        private const val MAX_NEARBY_AFTER_BYTES = 8 * 1024
        private const val MAX_OPEN = 8
        private const val MAX_OPEN_WARNINGS = 4
        private const val MAX_WARNING_CAPTURES = 16
        private const val MAX_NON_FATAL_CAPTURES = 28

        private fun bytes(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()

        fun directories(runDirectory: File): List<File> =
            File(runDirectory, ROOT).listFiles().orEmpty().filter {
                it.isDirectory && it.name.matches(Regex("incident-[0-9]+"))
            }.sortedBy { it.name.substringAfter("incident-").toLongOrNull() ?: Long.MAX_VALUE }

        fun files(runDirectory: File): List<File> =
            directories(runDirectory).flatMap { dir ->
                listOf(CAPTURE, TIMELINE, END, TRANSACTION, LATEST, "error_detail.txt").map { File(dir, it) }.filter(File::isFile)
            }

        fun transactionState(directory: File): Outcome? = runCatching {
            val value = Regex(""""state":"([A-Z_]+)"""").find(File(directory, TRANSACTION).readText())
                ?.groupValues?.get(1) ?: return@runCatching null
            Outcome.valueOf(value)
        }.getOrNull()

        fun mergedOccurrences(directory: File): Long = runCatching {
            val count = Regex(""""occurrences":([0-9]+)""").find(File(directory, TRANSACTION).readText())
                ?.groupValues?.get(1)?.toLongOrNull() ?: 1L
            (count - 1L).coerceAtLeast(0L)
        }.getOrDefault(0L)

        /** Never resume a capture window or transaction in a different process or run. */
        fun recoverInterrupted(runDirectory: File): Long {
            var failures = 0L
            for (directory in directories(runDirectory)) {
                try {
                    val transaction = File(directory, TRANSACTION)
                    if (transactionState(directory) in setOf(Outcome.OPEN, Outcome.RECOVERING)) {
                        val original = transaction.readText()
                        val changed = original.replace("\"state\":\"${transactionState(directory)?.name}\"", "\"state\":\"INTERRUPTED\"")
                            .replace(Regex(""""endReason":"[A-Z_]+""""), "\"endReason\":\"PROCESS_INTERRUPTED\"")
                        writeAtomic(transaction, changed)
                    }
                    if (File(directory, END).isFile) continue
                    val after = File(directory, TIMELINE).takeIf(File::isFile)?.useLines { lines ->
                        lines.count { it.startsWith("{\"phase\":\"AFTER\"") }
                    } ?: 0
                    val status = if (File(directory, CAPTURE).isFile && File(directory, TIMELINE).isFile)
                        "PROCESS_INTERRUPTED" else "INCOMPLETE_START"
                    writeEnd(directory, status, after)
                } catch (_: Exception) {
                    failures++
                }
            }
            return failures
        }

        private fun writeEnd(directory: File, status: String, after: Int) {
            writeAtomic(File(directory, END),
                """{"schemaVersion":1,"captureStatus":"$status","afterRecords":$after,"businessRecoveryOutcome":"NOT_EVALUATED"}""")
        }

        private fun writeAtomic(file: File, text: String) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            try {
                temp.writeText(text, Charsets.UTF_8)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }

        private fun appendLine(file: File, line: String) {
            RandomAccessFile(file, "rw").use { output ->
                val before = output.length()
                try {
                    output.seek(before)
                    output.write(line.toByteArray(Charsets.UTF_8))
                } catch (failure: Exception) {
                    runCatching { output.setLength(before) }
                    throw failure
                }
            }
        }
    }
}
