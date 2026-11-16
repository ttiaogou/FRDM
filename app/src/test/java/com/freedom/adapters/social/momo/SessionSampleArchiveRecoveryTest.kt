package com.freedom.adapters.social.momo

import com.freedom.data.samples.SessionSampleArchiveIntegrity
import com.freedom.data.samples.SessionSampleArchiveRecovery

import com.freedom.ui.logs.SessionSampleArchiveManager
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSampleArchiveRecoveryTest {
    @Test
    fun recoversRunOwnedByPreviousProcessAndMarksArchiveAsRecovered() {
        val runs = Files.createTempDirectory("freedom-recovery-previous-process").toFile()
        try {
            val runId = "20261109-120000-123"
            val run = createRun(runs, runId)
            File(runs, ".$runId.active.json").writeText(
                "{\"runId\":\"$runId\",\"ownerProcessToken\":\"dead-process-token\"}"
            )

            val report = SessionSampleArchiveRecovery.recoverPendingRuns(runs, nowEpochMs = 10_000L)

            assertEquals(1, report.recoveredCount)
            assertEquals(0, report.failedCount)
            assertFalse(run.exists())
            assertFalse(File(runs, ".$runId.active.json").exists())
            val zip = File(runs, "$runId.zip")
            assertTrue(zip.isFile)
            ZipFile(zip).use { archive ->
                assertNotNull(archive.getEntry("run_recovery.json"))
                val recovery = archive.getInputStream(archive.getEntry("run_recovery.json"))
                    .bufferedReader().use { it.readText() }
                assertTrue(recovery.contains("PROCESS_INTERRUPTED"))
                val manifest = archive.getInputStream(archive.getEntry("archive_manifest.json"))
                    .bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("PROCESS_INTERRUPTED_RECOVERED"))
            }
            val listed = requireNotNull(SessionSampleArchiveManager.inspect(zip))
            assertTrue(listed.recoveredAfterProcessRestart)
            assertTrue("进程中断后自动恢复封存" in listed.contentLabels)

            val secondPass = SessionSampleArchiveRecovery.recoverPendingRuns(runs, nowEpochMs = 20_000L)
            assertEquals(0, secondPass.recoveredCount)
            assertEquals(0, secondPass.failedCount)
        } finally {
            runs.deleteRecursively()
        }
    }

    @Test
    fun neverTouchesRunOwnedByCurrentProcess() {
        val runs = Files.createTempDirectory("freedom-recovery-active-process").toFile()
        try {
            val runId = "20261109-120001-124"
            val run = createRun(runs, runId)
            assertTrue(SessionSampleArchiveRecovery.writeOwnerMarker(runs, runId, 1L))

            val report = SessionSampleArchiveRecovery.recoverPendingRuns(runs, nowEpochMs = 100_000L)

            assertEquals(0, report.recoveredCount)
            assertEquals(1, report.skippedActiveCount)
            assertTrue(run.isDirectory)
            assertFalse(File(runs, "$runId.zip").exists())
        } finally {
            runs.deleteRecursively()
        }
    }

    @Test
    fun skipsRecentUnownedDirectoryButRecoversItAfterGracePeriod() {
        val runs = Files.createTempDirectory("freedom-recovery-unowned").toFile()
        try {
            val runId = "20261109-120002-125"
            val run = createRun(runs, runId)
            val now = 100_000L
            run.setLastModified(now - 1_000L)

            val recent = SessionSampleArchiveRecovery.recoverPendingRuns(
                runs, nowEpochMs = now, staleUnownedAgeMs = 30_000L
            )
            assertEquals(1, recent.skippedRecentUnownedCount)
            assertTrue(run.isDirectory)

            run.setLastModified(now - 60_000L)
            val stale = SessionSampleArchiveRecovery.recoverPendingRuns(
                runs, nowEpochMs = now, staleUnownedAgeMs = 0L
            )
            assertEquals(1, stale.recoveredCount)
            assertTrue(File(runs, "$runId.zip").isFile)
        } finally {
            runs.deleteRecursively()
        }
    }


    @Test
    fun immediatelyRecoversLegacyUnownedDirectoryFromPreviousVersion() {
        val runs = Files.createTempDirectory("freedom-recovery-legacy-unowned").toFile()
        try {
            val runId = "20261109-120006-129"
            val run = createRun(runs, runId)
            run.setLastModified(System.currentTimeMillis())

            val report = SessionSampleArchiveRecovery.recoverPendingRuns(runs)

            assertEquals(1, report.recoveredCount)
            assertFalse(run.exists())
            assertTrue(File(runs, "$runId.zip").isFile)
        } finally {
            runs.deleteRecursively()
        }
    }

    @Test
    fun recoveryAfterRunFinishedRecordIsReportedAsSealRecovery() {
        val runs = Files.createTempDirectory("freedom-recovery-seal-stage").toFile()
        try {
            val runId = "20261109-120003-126"
            val run = createRun(runs, runId)
            File(run, "run.jsonl").appendText(
                "{\"event\":\"run_finished\",\"finishedAt\":2000}\n"
            )
            File(runs, ".$runId.active.json").writeText(
                "{\"runId\":\"$runId\",\"ownerProcessToken\":\"dead-process-token\"}"
            )

            val report = SessionSampleArchiveRecovery.recoverPendingRuns(runs, nowEpochMs = 5_000L)
            assertEquals(1, report.recoveredCount)
            ZipFile(File(runs, "$runId.zip")).use { archive ->
                val recovery = archive.getInputStream(archive.getEntry("run_recovery.json"))
                    .bufferedReader().use { it.readText() }
                assertTrue(recovery.contains("ARCHIVE_SEAL_INTERRUPTED"))
            }
        } finally {
            runs.deleteRecursively()
        }
    }


    @Test
    fun cleansSourceDirectoryWhenPreviouslyPublishedZipIsSelfConsistent() {
        val runs = Files.createTempDirectory("freedom-recovery-cleanup-after-zip").toFile()
        try {
            val runId = "20261109-120004-127"
            val run = createRun(runs, runId)
            val snapshot = SessionSampleArchiveIntegrity.writeManifest(run, runId, 4_000L, emptyList())
            val zip = File(runs, "$runId.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
                run.walkTopDown().filter(File::isFile).forEach { file ->
                    val name = file.relativeTo(run).invariantSeparatorsPath
                    output.putNextEntry(java.util.zip.ZipEntry(name))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            assertTrue(SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(zip).valid)
            File(runs, ".$runId.active.json").writeText(
                "{\"runId\":\"$runId\",\"ownerProcessToken\":\"dead-process-token\"}"
            )

            val report = SessionSampleArchiveRecovery.recoverPendingRuns(runs, nowEpochMs = 5_000L)

            assertEquals(0, report.recoveredCount)
            assertEquals(1, report.cleanedSealedCount)
            assertFalse(run.exists())
            assertTrue(zip.isFile)
            assertTrue(SessionSampleArchiveIntegrity.verifyZip(zip, snapshot).valid)
        } finally {
            runs.deleteRecursively()
        }
    }

    @Test
    fun rejectsExistingZipWhoseEmbeddedFileHashWasTampered() {
        val runs = Files.createTempDirectory("freedom-recovery-tampered-existing-zip").toFile()
        try {
            val runId = "20261109-120005-128"
            val run = createRun(runs, runId)
            val snapshot = SessionSampleArchiveIntegrity.writeManifest(run, runId, 5_000L, emptyList())
            val zip = File(runs, "$runId.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
                run.walkTopDown().filter(File::isFile).forEach { file ->
                    val name = file.relativeTo(run).invariantSeparatorsPath
                    output.putNextEntry(java.util.zip.ZipEntry(name))
                    if (name == "runtime_logs.txt") output.write("tampered\n".toByteArray())
                    else file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            assertFalse(SessionSampleArchiveIntegrity.verifyZip(zip, snapshot).valid)
            assertFalse(SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(zip).valid)
            File(runs, ".$runId.active.json").writeText(
                "{\"runId\":\"$runId\",\"ownerProcessToken\":\"dead-process-token\"}"
            )

            val report = SessionSampleArchiveRecovery.recoverPendingRuns(runs, nowEpochMs = 6_000L)

            assertEquals(1, report.recoveredCount)
            assertEquals(0, report.failedCount)
            assertFalse(run.exists())
            assertTrue(SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(zip).valid)
        } finally {
            runs.deleteRecursively()
        }
    }

    private fun createRun(runs: File, runId: String): File = File(runs, runId).apply {
        check(mkdirs())
        File(this, "run.jsonl").writeText(
            "{\"event\":\"run_started\",\"runId\":\"$runId\",\"startedAt\":1000}\n"
        )
        File(this, "run_manifest.json").writeText("{\"schemaVersion\":3}")
        File(this, "runtime_logs.txt").writeText("monitor started\n")
    }
}
