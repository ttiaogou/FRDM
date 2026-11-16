package com.freedom.adapters.social.momo

import com.freedom.data.samples.SessionSampleArchiveIntegrity

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSampleArchiveIntegrityTest {
    @Test
    fun writesCoverageAndHashesThenVerifiesDirectoryAndZip() {
        val root = Files.createTempDirectory("freedom-archive-integrity").toFile()
        try {
            val run = File(root, "run-1").apply { mkdirs() }
            File(run, "run.jsonl").writeText("{\"event\":\"run_started\"}\n")
            File(run, "run_manifest.json").writeText("{\"schemaVersion\":3}")
            File(run, "runtime_logs.txt").writeText("service connected\n")
            File(run, "node_trees").mkdirs()
            File(run, "node_trees/page_001.json").writeText("{}")

            val snapshot = SessionSampleArchiveIntegrity.writeManifest(
                runDirectory = run,
                runId = "run-1",
                generatedAtEpochMs = 1234L,
                warnings = emptyList()
            )

            assertTrue(SessionSampleArchiveIntegrity.verifyDirectory(run, snapshot).valid)
            val manifestText = File(run, SessionSampleArchiveIntegrity.MANIFEST_NAME).readText()
            assertTrue(manifestText.contains("runtime_logs"))
            assertTrue(manifestText.contains("node_trees"))
            assertTrue(manifestText.contains("sha256"))
            assertEquals(4, snapshot.files.size)

            val zip = File(root, "run-1.zip")
            ZipOutputStream(zip.outputStream()).use { output ->
                run.walkTopDown().filter(File::isFile).forEach { file ->
                    val name = file.relativeTo(run).invariantSeparatorsPath
                    output.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            assertTrue(SessionSampleArchiveIntegrity.verifyZip(zip, snapshot).valid)
            assertTrue(SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(zip).valid)

            val tamperedZip = File(root, "run-1-tampered.zip")
            ZipOutputStream(tamperedZip.outputStream()).use { output ->
                run.walkTopDown().filter(File::isFile).forEach { file ->
                    val name = file.relativeTo(run).invariantSeparatorsPath
                    output.putNextEntry(ZipEntry(name))
                    if (name == "runtime_logs.txt") output.write("modified log\n".toByteArray())
                    else file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            assertFalse(SessionSampleArchiveIntegrity.verifyZip(tamperedZip, snapshot).valid)
            assertFalse(SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(tamperedZip).valid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsChangedSourceFileAfterManifestCreation() {
        val root = Files.createTempDirectory("freedom-archive-tamper").toFile()
        try {
            val run = File(root, "run-2").apply { mkdirs() }
            File(run, "run.jsonl").writeText("start")
            File(run, "run_manifest.json").writeText("metadata")
            val snapshot = SessionSampleArchiveIntegrity.writeManifest(run, "run-2", 5678L, emptyList())
            File(run, "run.jsonl").writeText("changed")

            val result = SessionSampleArchiveIntegrity.verifyDirectory(run, snapshot)
            assertFalse(result.valid)
            assertTrue(result.errors.any { it.contains("SHA-256") })

            File(run, "run.jsonl").writeText("start")
            File(run, SessionSampleArchiveIntegrity.MANIFEST_NAME).writeText("tampered manifest")
            val manifestResult = SessionSampleArchiveIntegrity.verifyDirectory(run, snapshot)
            assertFalse(manifestResult.valid)
            assertTrue(manifestResult.errors.any { it.contains("完整性清单 SHA-256") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recordsCaptureWarningsWithoutCallingTheArchiveCorrupt() {
        val root = Files.createTempDirectory("freedom-archive-warnings").toFile()
        try {
            val run = File(root, "run-3").apply { mkdirs() }
            File(run, "run.jsonl").writeText("start")
            File(run, "run_manifest.json").writeText("metadata")
            val snapshot = SessionSampleArchiveIntegrity.writeManifest(
                run, "run-3", 9000L, listOf("NODE_TREE_SAMPLE_LIMIT_REACHED")
            )
            val manifest = File(run, SessionSampleArchiveIntegrity.MANIFEST_NAME).readText()
            assertTrue(manifest.contains("HASH_INVENTORY_CREATED_WITH_CAPTURE_WARNINGS"))
            assertTrue(manifest.contains("NODE_TREE_SAMPLE_LIMIT_REACHED"))
            assertTrue(SessionSampleArchiveIntegrity.verifyDirectory(run, snapshot).valid)
        } finally {
            root.deleteRecursively()
        }
    }
}
