package com.freedom.data

import com.freedom.core.logging.RunIncidentEvidencePolicy.Outcome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Independent bounded incident retention. Normal run pruning may delete a source
 * only after the incident-only copy is atomically published and verified.
 */
internal class RunIncidentHistoryStore(
    private val root: File,
    private val maxRuns: Int = MAX_RUNS,
    private val maxBytes: Long = MAX_BYTES
) {
    companion object {
        const val DIRECTORY = "incident_history"
        const val MAX_RUNS = 20
        const val MAX_BYTES = 16L * 1024 * 1024
        const val RETENTION_MARKER = "incident_retention.json"

        private fun safeId(id: String) = id.matches(Regex("[A-Za-z0-9._-]{1,120}")) && id != "." && id != ".."
        private fun size(dir: File): Long = dir.walkTopDown().filter(File::isFile).sumOf(File::length)
        private fun unresolved(dir: File): Boolean =
            RunIncidentEvidenceStore.pageEvidenceFiles(dir).isNotEmpty() ||
            RunIncidentEvidenceStore.directories(dir).any {
                RunIncidentEvidenceStore.transactionState(it) != Outcome.RESOLVED
            }
    }

    /** A failed copy, manifest or verification must leave the original run untouched. */
    fun preserve(run: File, writeManifest: (File) -> Unit): Boolean {
        val incidents = RunIncidentEvidenceStore.directories(run)
        if (incidents.isEmpty() && RunIncidentEvidenceStore.pageEvidenceFiles(run).isEmpty()) return true
        if (!safeId(run.name)) return false
        val target = File(root, run.name)
        val pending = File(root, ".${run.name}.pending")
        return runCatching {
            check(root.isDirectory || root.mkdirs())
            val sourceFiles = RunIncidentEvidenceStore.files(run) +
                listOf(File(run, "incidents/index.json")).filter(File::isFile)
            fun matchesCopy(destination: File): Boolean =
                File(destination, RETENTION_MARKER).isFile && File(destination, "diagnostic_manifest.json").isFile &&
                    incidents.all { File(destination, it.relativeTo(run).invariantSeparatorsPath).isDirectory } &&
                    sourceFiles.all { source ->
                        val copy = File(destination, source.relativeTo(run).invariantSeparatorsPath)
                        copy.isFile && source.length() == copy.length() && sameContent(source, copy)
                    }
            if (target.exists()) return@runCatching matchesCopy(target)
            check(!pending.exists() || pending.deleteRecursively())
            check(pending.mkdirs())
            try {
                // Preserve all known incident directories, including a partially written one.
                for (incident in incidents) {
                    val destination = File(pending, incident.relativeTo(run).invariantSeparatorsPath)
                    check(destination.mkdirs() || destination.isDirectory)
                }
                for (file in sourceFiles) {
                    val destination = File(pending, file.relativeTo(run).invariantSeparatorsPath)
                    destination.parentFile?.mkdirs()
                    file.copyTo(destination, overwrite = false)
                }
                File(pending, RETENTION_MARKER).writeText(
                    """{"schemaVersion":1,"scope":"INCIDENTS_ONLY","normalRunRetained":false,"sourceRunId":"${run.name}","sourceFinishedAt":${File(run, "diagnostic_report.json").lastModified()},"retainedAt":${System.currentTimeMillis()}}"""
                )
                File(pending, RETENTION_MARKER).setLastModified(
                    File(run, "diagnostic_report.json").lastModified().takeIf { it > 0L } ?: run.lastModified()
                )
                writeManifest(pending)
                check(matchesCopy(pending))
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            } finally {
                if (pending.exists()) pending.deleteRecursively()
            }
        }.getOrDefault(false)
    }

    private fun sameContent(a: File, b: File): Boolean {
        a.inputStream().buffered().use { first ->
            b.inputStream().buffered().use { second ->
                val x = ByteArray(8192)
                val y = ByteArray(8192)
                val left = java.io.DataInputStream(first)
                val right = java.io.DataInputStream(second)
                var remaining = a.length()
                while (remaining > 0L) {
                    val count = minOf(remaining, x.size.toLong()).toInt()
                    left.readFully(x, 0, count)
                    right.readFully(y, 0, count)
                    for (index in 0 until count) if (x[index] != y[index]) return false
                    remaining -= count
                }
                return left.read() == -1 && right.read() == -1
            }
        }
    }

    /** Resolved histories are evicted first, then oldest unresolved ones only at the independent cap. */
    fun prune(): Int {
        val directories = root.listFiles().orEmpty().filter {
            it.isDirectory && !it.name.startsWith(".") && safeId(it.name) && File(it, RETENTION_MARKER).isFile
        }.sortedWith(compareBy<File> { unresolved(it) }.thenBy {
            File(it, RETENTION_MARKER).lastModified()
        }.thenBy { it.name })
        var remaining = directories.size
        var total = directories.sumOf(::size)
        var removed = 0
        for (directory in directories) {
            if (remaining <= maxRuns && total <= maxBytes) break
            val bytes = size(directory)
            if (directory.deleteRecursively()) {
                total = (total - bytes).coerceAtLeast(0L)
                remaining--
                removed++
            }
        }
        return removed
    }
}
