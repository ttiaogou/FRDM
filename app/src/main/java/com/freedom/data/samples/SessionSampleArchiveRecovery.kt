package com.freedom.data.samples

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Recovers unfinished sample-run directories left by a process death. A small owner marker
 * outside the run directory distinguishes a live run in this process from an abandoned one.
 * Recovery seals the existing evidence; it never resumes collection or invents missing samples.
 */
internal object SessionSampleArchiveRecovery {
    private const val OWNER_MARKER_SUFFIX = ".active.json"
    private const val STALE_UNOWNED_AGE_MS = 0L
    private const val RECOVERY_WARNING = "PROCESS_INTERRUPTED_RECOVERED"
    private val processToken: String = UUID.randomUUID().toString()
    private val lock = Any()

    data class RecoveryReport(
        val recoveredCount: Int = 0,
        val cleanedSealedCount: Int = 0,
        val skippedActiveCount: Int = 0,
        val skippedRecentUnownedCount: Int = 0,
        val failedCount: Int = 0,
        val errors: List<String> = emptyList()
    )

    /** Create/update the live-process marker before any samples are written. */
    fun writeOwnerMarker(runsDirectory: File, runId: String, startedAtEpochMs: Long): Boolean =
        synchronized(lock) {
            if (!isSafeRunId(runId)) return false
            if (!runsDirectory.isDirectory && !runsDirectory.mkdirs()) return false
            val marker = ownerMarker(runsDirectory, runId)
            val temporary = File(runsDirectory, "${marker.name}.tmp")
            return runCatching {
                FileOutputStream(temporary).use { output ->
                    output.write(
                        ("{\"schemaVersion\":1,\"runId\":${quote(runId)}," +
                            "\"ownerProcessToken\":${quote(processToken)}," +
                            "\"startedAtEpochMs\":$startedAtEpochMs}\n").toByteArray(Charsets.UTF_8)
                    )
                    output.fd.sync()
                }
                if (marker.exists() && !marker.delete()) error("无法替换采集任务活动标记")
                if (!temporary.renameTo(marker)) error("无法写入采集任务活动标记")
                true
            }.getOrElse {
                runCatching { temporary.delete() }
                false
            }
        }

    /** Remove the marker only after a final ZIP has passed integrity verification. */
    fun removeOwnerMarker(runsDirectory: File, runId: String) = synchronized(lock) {
        if (isSafeRunId(runId)) runCatching { ownerMarker(runsDirectory, runId).delete() }
    }

    /**
     * Re-seals directories abandoned by an earlier process. Directories owned by this process
     * are never touched, including while a normal finishRun() is in progress.
     */
    fun recoverPendingRuns(
        runsDirectory: File,
        nowEpochMs: Long = System.currentTimeMillis(),
        staleUnownedAgeMs: Long = STALE_UNOWNED_AGE_MS
    ): RecoveryReport = synchronized(lock) {
        if (!runsDirectory.isDirectory) return RecoveryReport()
        var recovered = 0
        var cleaned = 0
        var skippedActive = 0
        var skippedRecent = 0
        var failed = 0
        val errors = ArrayList<String>()

        val candidates = runsDirectory.listFiles()
            ?.filter { it.isDirectory && isSafeRunId(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()

        candidates.forEach { directory ->
            val marker = ownerMarker(runsDirectory, directory.name)
            val markerText = marker.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
            val ownerToken = markerText?.let { jsonString(it, "ownerProcessToken") }
            if (ownerToken == processToken) {
                skippedActive++
                return@forEach
            }
            if (ownerToken == null && nowEpochMs - directory.lastModified() < staleUnownedAgeMs) {
                skippedRecent++
                return@forEach
            }

            try {
                val recovery = sealInterruptedDirectory(directory, runsDirectory, nowEpochMs)
                when (recovery) {
                    RecoveryOutcome.RECOVERED -> recovered++
                    RecoveryOutcome.ALREADY_SEALED_CLEANED -> cleaned++
                }
            } catch (error: Exception) {
                failed++
                errors += "${directory.name}: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        // A process may die after the verified ZIP and source-directory removal but before
        // deleting its marker. Such a marker is stale metadata, not an unfinished run.
        runsDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(OWNER_MARKER_SUFFIX) }
            ?.forEach { marker ->
                val runId = marker.name.removePrefix(".").removeSuffix(OWNER_MARKER_SUFFIX)
                if (isSafeRunId(runId) && !File(runsDirectory, runId).exists() &&
                    File(runsDirectory, "$runId.zip").isFile
                ) runCatching { marker.delete() }
            }

        RecoveryReport(
            recoveredCount = recovered,
            cleanedSealedCount = cleaned,
            skippedActiveCount = skippedActive,
            skippedRecentUnownedCount = skippedRecent,
            failedCount = failed,
            errors = errors
        )
    }

    private enum class RecoveryOutcome { RECOVERED, ALREADY_SEALED_CLEANED }

    private fun sealInterruptedDirectory(
        directory: File,
        runsDirectory: File,
        recoveredAtEpochMs: Long
    ): RecoveryOutcome {
        require(directory.isDirectory) { "采集目录不存在" }
        val runId = directory.name
        val destination = File(runsDirectory, "$runId.zip")
        if (destination.isFile && SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(destination).valid) {
            // A crash may have happened after ZIP verification but during source-directory cleanup.
            // Trust only a self-consistent embedded manifest, then remove the redundant source.
            check(directory.deleteRecursively() || !directory.exists()) { "完整 ZIP 已存在但无法清理残留目录" }
            removeOwnerMarker(runsDirectory, runId)
            return RecoveryOutcome.ALREADY_SEALED_CLEANED
        }
        val runJsonl = File(directory, "run.jsonl")
        val priorRunText = runJsonl.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
        val hadRunFinishedRecord = priorRunText.lineSequence().any {
            jsonString(it, "event") == "run_finished"
        }
        val reason = if (hadRunFinishedRecord) "ARCHIVE_SEAL_INTERRUPTED" else "PROCESS_INTERRUPTED"

        val recoveryJson = """
            {
              "schemaVersion": 1,
              "runId": ${quote(runId)},
              "recoveredAtEpochMs": $recoveredAtEpochMs,
              "recoveryReason": ${quote(reason)},
              "previousRunFinishedRecordPresent": $hadRunFinishedRecord,
              "collectionResumed": false,
              "sourceDirectoryRetainedUntilZipVerified": true
            }
        """.trimIndent() + "\n"
        writeAtomically(File(directory, "run_recovery.json"), recoveryJson)

        val recoveryEvent = "{" +
            "\"event\":\"run_recovered_after_process_restart\",\"runId\":${quote(runId)}," +
            "\"recoveredAtEpochMs\":$recoveredAtEpochMs,\"recoveryReason\":${quote(reason)}}\n"
        appendAndSync(runJsonl, recoveryEvent)

        val warnings = listOf(RECOVERY_WARNING) +
            if (hadRunFinishedRecord) listOf("ARCHIVE_SEAL_RESUMED_AFTER_PROCESS_RESTART") else emptyList()
        val snapshot = SessionSampleArchiveIntegrity.writeManifest(
            runDirectory = directory,
            runId = runId,
            generatedAtEpochMs = recoveredAtEpochMs,
            warnings = warnings
        )
        val directoryVerification = SessionSampleArchiveIntegrity.verifyDirectory(directory, snapshot)
        check(directoryVerification.valid) {
            "恢复采集目录完整性检查失败：${directoryVerification.errors.joinToString("；")}"
        }

        val temporaryZip = File(runsDirectory, "$runId.recovery.tmp")
        if (temporaryZip.exists()) check(temporaryZip.delete()) { "无法清理上一次恢复的临时 ZIP" }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryZip))).use { output ->
            directory.walkTopDown().filter(File::isFile).forEach { file ->
                val entryName = file.relativeTo(directory).invariantSeparatorsPath
                output.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        check(temporaryZip.isFile && temporaryZip.length() > 0L) { "恢复 ZIP 为空" }
        val temporaryVerification = SessionSampleArchiveIntegrity.verifyZip(temporaryZip, snapshot)
        check(temporaryVerification.valid) {
            "恢复 ZIP 完整性检查失败：${temporaryVerification.errors.joinToString("；")}"
        }

        val backup = File(runsDirectory, "$runId.previous.bak")
        if (backup.exists() && !backup.delete()) error("无法清理旧 ZIP 备份")
        if (destination.exists()) {
            check(destination.renameTo(backup)) { "无法暂存损坏或过期的 ZIP" }
        }
        try {
            moveAtomically(temporaryZip, destination)
            val finalVerification = SessionSampleArchiveIntegrity.verifyZip(destination, snapshot)
            check(finalVerification.valid) {
                "恢复后的最终 ZIP 校验失败：${finalVerification.errors.joinToString("；")}"
            }
            runCatching { backup.delete() }
        } catch (error: Exception) {
            runCatching { destination.delete() }
            if (backup.exists()) runCatching { backup.renameTo(destination) }
            throw error
        } finally {
            runCatching { temporaryZip.delete() }
        }

        check(directory.deleteRecursively() || !directory.exists()) { "恢复 ZIP 已生成但原始目录清理失败" }
        removeOwnerMarker(runsDirectory, runId)
        return RecoveryOutcome.RECOVERED
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            check(source.renameTo(destination)) { "无法发布恢复 ZIP" }
        }
    }

    private fun appendAndSync(file: File, content: String) {
        FileOutputStream(file, true).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            temporary.delete()
            error("无法替换 ${file.name}")
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("无法写入 ${file.name}")
        }
    }

    private fun ownerMarker(runsDirectory: File, runId: String) =
        File(runsDirectory, ".$runId$OWNER_MARKER_SUFFIX")

    private fun isSafeRunId(runId: String): Boolean =
        runId.isNotBlank() && runId.length <= 120 && runId != "." && runId != ".." &&
            runId.matches(Regex("[A-Za-z0-9._-]+"))

    private fun jsonString(json: String, key: String): String? {
        val escaped = Regex.escape(key)
        val match = Regex("\"$escaped\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(json)
        return match?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
