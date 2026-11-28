package com.freedom.ui.logs

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class D01cCombinedExportTest {
    @Test
    fun combinedExportIncludesAllSealedSamplesAndOnlyEligibleDiagnosticHistory() {
        val root = Files.createTempDirectory("freedom-combined-export").toFile()
        try {
            val samples = File(root, "samples").apply { mkdirs() }
            sample(samples, "first", "legacy")
            sample(samples, "second", "legacy")
            sample(samples, "third", "active")
            sample(samples, "unsealed", "active")
            File(samples, "unsealed").mkdirs()
            File(samples, ".unsealed.active.json").writeText("{}")
            val runs = File(root, "diagnostics/runs").apply { mkdirs() }
            fun diagnostic(id: String, manifest: Boolean): File =
                File(runs, id).apply {
                    mkdirs()
                    File(this, "diagnostic_report.txt").writeText("report-$id")
                    File(this, "events.jsonl").writeText("events-$id")
                    if (manifest) File(this, "diagnostic_manifest.json").writeText("{}")
                }
            val legacy = diagnostic("legacy", false)
            diagnostic("unlinked-legacy", false)
            val active = diagnostic("active", true)
            diagnostic("standalone", true)
            val retained = File(runs.parentFile, "incident_history/retained").apply { mkdirs() }
            File(retained, "incident_retention.json").writeText("{}")
            File(retained, "incidents/one").mkdirs()
            File(retained, "incidents/one/evidence.txt").writeText("evidence")
            val sealed = SessionSampleArchiveManager.listSealedArchives(samples)
            assertEquals(setOf("first", "second", "third"), sealed.map { it.runId }.toSet())
            val destination = File(root, "combined.zip")
            val receipt = SessionSampleArchiveManager.createExportBundle(
                sealed, runs, destination, includeAllDiagnostics = true,
                recentLogText = "recent", withSealedHistory = { copy -> copy(setOf("active")) }
            )
            ZipFile(destination).use { zip ->
                for (id in listOf("first", "second", "third")) {
                    assertTrue(zip.getEntry("samples/$id/node_trees/page_001.json") != null)
                }
                assertTrue(zip.getEntry("samples/unsealed/run.jsonl") == null)
                assertTrue(zip.getEntry("diagnostics/legacy/events.jsonl") != null)
                assertTrue(zip.getEntry("diagnostics/standalone/events.jsonl") != null)
                assertTrue(zip.getEntry("diagnostics/active/events.jsonl") == null)
                assertTrue(zip.getEntry("diagnostics/unlinked-legacy/events.jsonl") == null)
                assertTrue(zip.getEntry("diagnostics/retained/incidents/one/evidence.txt") != null)
                assertEquals("recent", zip.getInputStream(zip.getEntry("recent-display.txt")).bufferedReader().use { it.readText() })
            }
            assertEquals(1, receipt.sources.count { it.kind == SessionSampleArchiveManager.SourceKind.DIAGNOSTIC && it.runId == "legacy" })
            assertTrue(File(samples, "first.zip").isFile)
            SessionSampleArchiveManager.cleanupAfterVerifiedExport(receipt, destination.inputStream(), samples, runs) { it(setOf("active")) }
            assertFalse(File(samples, "first.zip").exists())
            assertTrue(File(samples, "unsealed.zip").isFile)
            assertTrue(legacy.isDirectory) // Legacy evidence has no deletion-authorizing manifest.
            assertTrue(active.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun combinedExportWithoutSamplesStillExportsSealedDiagnostics() {
        val root = Files.createTempDirectory("freedom-combined-diagnostics-only").toFile()
        try {
            val runs = File(root, "diagnostics/runs").apply { mkdirs() }
            val run = File(runs, "sealed").apply { mkdirs() }
            File(run, "diagnostic_manifest.json").writeText("{}")
            File(run, "events.jsonl").writeText("events")
            val destination = File(root, "combined.zip")
            SessionSampleArchiveManager.createExportBundle(
                emptyList(), runs, destination, includeAllDiagnostics = true, recentLogText = "recent"
            )
            ZipFile(destination).use { zip ->
                assertTrue(zip.getEntry("diagnostics/sealed/events.jsonl") != null)
                assertTrue(zip.getEntry("recent-display.txt") != null)
                assertTrue(zip.getEntry("EXPORT-MANIFEST.txt") != null)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sample(root: File, id: String, diagnostic: String) {
        ZipOutputStream(File(root, "$id.zip").outputStream()).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("run.jsonl", """{"event":"run_started","runId":"$id","diagnosticRunId":"$diagnostic","startedAt":1000}
                |{"event":"run_finished","finishedAt":2000}
                |""".trimMargin())
            entry("node_trees/page_001.json", "{}")
        }
    }
}
