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

class SessionSampleArchiveManagerTest {
    @Test
    fun verifiedExportCleansOnlySelectedUnchangedSourcesAndProtectsReferences() {
        val root = Files.createTempDirectory("freedom-export-cleanup").toFile()
        try {
            val samples = File(root, "samples").apply { mkdirs() }
            val runs = File(root, "diagnostics/runs").apply { mkdirs() }
            val selected = createRunArchive(samples, "selected.zip", "selected", 1L, 2L, 0, 0, false, diagnosticRunId = "shared")
            val remaining = createRunArchive(samples, "remaining.zip", "remaining", 1L, 2L, 0, 0, false, diagnosticRunId = "shared")
            val diagnostic = File(runs, "shared").apply { mkdirs() }
            File(diagnostic, "diagnostic_manifest.json").writeText("{}")
            File(diagnostic, "events.jsonl").writeText("evidence")
            val output = File(root, "out.zip")
            val receipt = SessionSampleArchiveManager.createExportBundle(
                listOf(requireNotNull(SessionSampleArchiveManager.inspect(selected))), runs, output
            )
            assertTrue(selected.isFile)
            val result = SessionSampleArchiveManager.cleanupAfterVerifiedExport(receipt, output.inputStream(), samples, runs) { it(emptySet()) }
            assertEquals(1, result.deleted)
            assertEquals(1, result.retained)
            assertFalse(selected.exists())
            assertTrue(remaining.isFile)
            assertTrue(diagnostic.isDirectory)
            val second = SessionSampleArchiveManager.createExportBundle(
                listOf(requireNotNull(SessionSampleArchiveManager.inspect(remaining))), runs, output
            )
            val finalResult = SessionSampleArchiveManager.cleanupAfterVerifiedExport(second, output.inputStream(), samples, runs) { it(emptySet()) }
            assertEquals(2, finalResult.deleted)
            assertFalse(diagnostic.exists())
            assertFalse(remaining.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedReadbackAndChangedOrActiveSourcesNeverAuthorizeDeletion() {
        val root = Files.createTempDirectory("freedom-export-verify").toFile()
        try {
            val samples = File(root, "samples").apply { mkdirs() }
            val runs = File(root, "diagnostics/runs").apply { mkdirs() }
            fun diagnostic(id: String): File = File(runs, id).apply {
                mkdirs()
                File(this, "diagnostic_manifest.json").writeText("{}")
                File(this, "events.jsonl").writeText("original")
            }
            val changed = diagnostic("changed")
            val active = diagnostic("active")
            val stable = diagnostic("stable")
            val retained = File(runs.parentFile, "incident_history/retained").apply { mkdirs() }
            File(retained, "incident_retention.json").writeText("{}")
            File(retained, "incidents").mkdirs()
            File(retained, "incidents/transaction.json").writeText("evidence")
            val output = File(root, "out.zip")
            val receipt = SessionSampleArchiveManager.createExportBundle(emptyList(), runs, output, includeAllDiagnostics = true)
            val invalid = runCatching {
                SessionSampleArchiveManager.cleanupAfterVerifiedExport(receipt, byteArrayOf(1).inputStream(), samples, runs) { it(emptySet()) }
            }
            assertTrue(invalid.isFailure)
            assertTrue(stable.isDirectory)
            File(changed, "events.jsonl").writeText("new evidence")
            val result = SessionSampleArchiveManager.cleanupAfterVerifiedExport(receipt, output.inputStream(), samples, runs) { it(setOf("active")) }
            assertEquals(2, result.deleted)
            assertEquals(2, result.retained)
            assertTrue(changed.isDirectory)
            assertTrue(active.isDirectory)
            assertFalse(stable.exists())
            assertFalse(retained.exists())
        } finally { root.deleteRecursively() }
    }


    @Test
    fun listsOnlyRunDirectoriesWithActiveOwnerMarkersAsInProgress() {
        val directory = Files.createTempDirectory("freedom-sample-active").toFile()
        try {
            val activeId = "20261112-234748-11866731"
            File(directory, activeId).mkdirs()
            File(directory, ".$activeId.active.json").writeText("{}")

            val staleMarkerId = "stale-run"
            File(directory, ".$staleMarkerId.active.json").writeText("{}")

            val sealingId = "sealing-run"
            File(directory, sealingId).mkdirs()
            File(directory, ".$sealingId.active.json").writeText("{}")
            File(directory, "$sealingId.zip").writeBytes(byteArrayOf(1))

            val alreadySealedId = "sealed-run"
            File(directory, ".$alreadySealedId.active.json").writeText("{}")
            File(directory, "$alreadySealedId.zip").writeBytes(byteArrayOf(1))

            assertEquals(setOf(activeId, sealingId), SessionSampleArchiveManager.listActiveRunIds(directory))
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun doesNotListPartiallyWrittenZipAsSealedWhileOwnerMarkerAndSourceDirectoryRemain() {
        val directory = Files.createTempDirectory("freedom-sample-sealing").toFile()
        try {
            val runId = "sealing-run"
            File(directory, runId).mkdirs()
            File(directory, ".$runId.active.json").writeText("{}")
            createRunArchive(
                directory = directory,
                fileName = "$runId.zip",
                runId = runId,
                startedAt = 1000L,
                finishedAt = 2000L,
                sessionLists = 1,
                chatBatches = 0,
                includeChatSamples = false
            )

            assertTrue(SessionSampleArchiveManager.listArchives(directory).any { it.runId == runId })
            assertTrue(SessionSampleArchiveManager.listSealedArchives(directory).isEmpty())

            File(directory, runId).deleteRecursively()
            File(directory, ".$runId.active.json").delete()
            assertEquals(listOf(runId), SessionSampleArchiveManager.listSealedArchives(directory).map { it.runId })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun inspectReadsRunMetadataAndOnlyListsExistingContent() {
        val directory = Files.createTempDirectory("freedom-sample-inspect").toFile()
        try {
            val archiveFile = createRunArchive(
                directory = directory,
                fileName = "20261109-090849.zip",
                runId = "20261109-090849-584662502",
                startedAt = 1_794_186_529_523L,
                finishedAt = 1_794_186_540_834L,
                sessionLists = 1,
                chatBatches = 0,
                includeChatSamples = false
            )

            val archive = SessionSampleArchiveManager.inspect(archiveFile)
            requireNotNull(archive)
            assertEquals("20261109-090849-584662502", archive.runId)
            assertEquals(11_311L, archive.durationMillis)
            assertEquals(1, archive.sessionListCount)
            assertEquals(0, archive.chatBatchCount)
            assertTrue("会话列表样本" in archive.contentLabels)
            assertFalse("聊天消息与 OCR 识别结果" in archive.contentLabels)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completeRunArchiveListsRuntimeLogsEventTimelineAndNodeDiagnostics() {
        val directory = Files.createTempDirectory("freedom-sample-complete-evidence").toFile()
        try {
            val file = createRunArchive(
                directory = directory,
                fileName = "complete.zip",
                runId = "complete-run",
                startedAt = 1000L,
                finishedAt = 3000L,
                sessionLists = 1,
                chatBatches = 1,
                includeChatSamples = true,
                includeCompleteEvidence = true
            )
            val archive = requireNotNull(SessionSampleArchiveManager.inspect(file))
            assertTrue("归档文件清单与 SHA-256 完整性证据" in archive.contentLabels)
            assertTrue("采集数据存在截断或写入告警" in archive.contentLabels)
            assertEquals("HASH_INVENTORY_CREATED_WITH_CAPTURE_WARNINGS", archive.archiveIntegrityStatus)
            assertEquals(listOf("NODE_TREE_SAMPLE_LIMIT_REACHED"), archive.captureWarnings)
            assertTrue("本次运行日志" in archive.contentLabels)
            assertTrue("脱敏无障碍事件时间线" in archive.contentLabels)
            assertFalse("节点查找诊断" in archive.contentLabels)
            assertEquals(1, archive.nodeTreeCaptureCount)
            assertEquals(1, archive.selectorDiagnosticCount)
            assertTrue("无障碍节点树 1 份及结构差异证据" in archive.contentLabels)
            assertTrue("Selector 查询诊断 1 条" in archive.contentLabels)
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun inspectLabelsProcessRecoveredArchiveWithoutMisreportingCaptureTruncation() {
        val directory = Files.createTempDirectory("freedom-sample-recovered").toFile()
        try {
            val file = File(directory, "recovered.zip")
            ZipOutputStream(file.outputStream()).use { output ->
                writeEntry(output, "run.jsonl", """
                    {"event":"run_started","runId":"recovered-run","startedAt":1000}
                    {"event":"run_recovered_after_process_restart","recoveredAtEpochMs":9000,"recoveryReason":"PROCESS_INTERRUPTED"}
                """.trimIndent() + "\n")
                writeEntry(output, "run_recovery.json", """
                    {"schemaVersion":1,"recoveredAtEpochMs":9000,"recoveryReason":"PROCESS_INTERRUPTED","collectionResumed":false}
                """.trimIndent())
                writeEntry(output, "archive_manifest.json", """
                    {"schemaVersion":1,"integrityStatus":"HASH_INVENTORY_CREATED_WITH_CAPTURE_WARNINGS","captureWarnings":["PROCESS_INTERRUPTED_RECOVERED"]}
                """.trimIndent())
            }

            val archive = requireNotNull(SessionSampleArchiveManager.inspect(file))
            assertTrue(archive.recoveredAfterProcessRestart)
            assertEquals(9_000L, archive.finishedAtMillis)
            assertTrue("进程中断后自动恢复封存" in archive.contentLabels)
            assertFalse("采集数据存在截断或写入告警" in archive.contentLabels)
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun recoveryTimeDoesNotReplaceOriginalFinishedAtWhenRunHadFinishedRecord() {
        val directory = Files.createTempDirectory("freedom-sample-recovered-seal").toFile()
        try {
            val file = File(directory, "seal-recovered.zip")
            ZipOutputStream(file.outputStream()).use { output ->
                writeEntry(output, "run.jsonl", """
                    {"event":"run_started","runId":"seal-run","startedAt":1000}
                    {"event":"run_finished","finishedAt":8000,"sessionLists":1,"chatBatches":1}
                    {"event":"run_recovered_after_process_restart","recoveredAtEpochMs":9000,"recoveryReason":"ARCHIVE_SEAL_INTERRUPTED"}
                """.trimIndent() + "\n")
                writeEntry(output, "run_recovery.json", """
                    {"schemaVersion":1,"recoveredAtEpochMs":9000,"recoveryReason":"ARCHIVE_SEAL_INTERRUPTED","collectionResumed":false}
                """.trimIndent())
                writeEntry(output, "archive_manifest.json", """
                    {"schemaVersion":1,"integrityStatus":"HASH_INVENTORY_CREATED_WITH_CAPTURE_WARNINGS","captureWarnings":["PROCESS_INTERRUPTED_RECOVERED","ARCHIVE_SEAL_RESUMED_AFTER_PROCESS_RESTART"]}
                """.trimIndent())
            }

            val archive = requireNotNull(SessionSampleArchiveManager.inspect(file))
            assertEquals(8_000L, archive.finishedAtMillis)
            assertEquals(7_000L, archive.durationMillis)
            assertTrue(archive.recoveredAfterProcessRestart)
            assertFalse("采集数据存在截断或写入告警" in archive.contentLabels)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun inspectReadsPairedDiagnosticRunIdAndExportBundleIncludesReportAndEvents() {
        val directory = Files.createTempDirectory("freedom-sample-diagnostic-bundle").toFile()
        try {
            val diagnosticRunId = "diagnostic-run-123"
            val sampleFile = createRunArchive(
                directory = directory,
                fileName = "paired-run.zip",
                runId = "paired-run",
                startedAt = 1000L,
                finishedAt = 3000L,
                sessionLists = 1,
                chatBatches = 0,
                includeChatSamples = false,
                includeCompleteEvidence = true,
                diagnosticRunId = diagnosticRunId
            )
            val archive = requireNotNull(SessionSampleArchiveManager.inspect(sampleFile))
            assertEquals(diagnosticRunId, archive.diagnosticRunId)
            assertTrue("关联的运行诊断报告与事件记录（可随采集包一起导出）" in archive.contentLabels)

            val diagnosticsRuns = File(directory, "freedom_diagnostics/runs")
            val diagnosticDirectory = File(diagnosticsRuns, diagnosticRunId).apply { mkdirs() }
            File(diagnosticDirectory, "diagnostic_report.txt").writeText("run diagnostic report")
            File(diagnosticDirectory, "events.jsonl").writeText("{\"eventType\":\"test\"}\n")
            val contextEvidence = "{\"stage\":\"REQUEST_PREPARED\",\"messages\":[{\"role\":\"user\",\"content\":\"我请你\"}]}\n"
            File(diagnosticDirectory, "ai_context_diagnostics.jsonl").writeText(contextEvidence)
            File(diagnosticDirectory, "diagnostic_counters.json").writeText("{}")
            val export = File(directory, "export-bundle.zip")

            SessionSampleArchiveManager.createExportBundle(listOf(archive), diagnosticsRuns, export)

            ZipFile(export).use { bundle ->
                assertTrue(bundle.getEntry("samples/paired-run/run.jsonl") != null)
                assertTrue(bundle.getEntry("samples/paired-run/runtime_logs.txt") != null)
                assertTrue(bundle.getEntry("diagnostics/$diagnosticRunId/diagnostic_report.txt") != null)
                assertTrue(bundle.getEntry("diagnostics/$diagnosticRunId/events.jsonl") != null)
                assertEquals(contextEvidence, bundle.getInputStream(
                    bundle.getEntry("diagnostics/$diagnosticRunId/ai_context_diagnostics.jsonl"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() })
                assertTrue(bundle.getEntry("diagnostics/$diagnosticRunId/diagnostic_counters.json") != null)
                val manifest = bundle.getInputStream(bundle.getEntry("EXPORT-MANIFEST.txt"))
                    .bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("报告已包含=是"))
            }
            assertTrue("原始采集 ZIP 不应被修改或删除", sampleFile.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun batchArchiveKeepsEachRunInItsOwnDirectory() {
        val directory = Files.createTempDirectory("freedom-sample-batch").toFile()
        try {
            val firstFile = createRunArchive(
                directory, "first.zip", "run-first", 1000L, 3000L, 2, 1, true
            )
            val secondFile = createRunArchive(
                directory, "second.zip", "run-second", 4000L, 7000L, 1, 0, false
            )
            val first = requireNotNull(SessionSampleArchiveManager.inspect(firstFile))
            val second = requireNotNull(SessionSampleArchiveManager.inspect(secondFile))
            val destination = File(directory, "combined.zip")

            SessionSampleArchiveManager.createBatchArchive(listOf(first, second), destination)

            ZipFile(destination).use { combined ->
                assertTrue(combined.getEntry("run-first/run.jsonl") != null)
                assertTrue(combined.getEntry("run-first/chat_samples.jsonl") != null)
                assertTrue(combined.getEntry("run-second/run.jsonl") != null)
                assertTrue(combined.getEntry("EXPORT-MANIFEST.txt") != null)
                assertEquals("run-first-chat", combined.getInputStream(
                    combined.getEntry("run-first/chat_samples.jsonl")
                ).bufferedReader().use { it.readText() })
            }
            assertTrue(firstFile.isFile)
            assertTrue(secondFile.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun batchArchiveRequiresAtLeastTwoRuns() {
        val directory = Files.createTempDirectory("freedom-sample-one").toFile()
        try {
            val source = createRunArchive(directory, "one.zip", "one", 1L, 2L, 0, 0, false)
            val archive = requireNotNull(SessionSampleArchiveManager.inspect(source))
            SessionSampleArchiveManager.createBatchArchive(listOf(archive), File(directory, "out.zip"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun l11eExportIncludesRetainedIncidentHistoryWithoutClaimingNormalReportExists() {
        val directory = Files.createTempDirectory("freedom-retained-incident-export").toFile()
        try {
            val id = "diagnostic-retained"
            val sampleFile = createRunArchive(
                directory, "sample.zip", "sample", 1L, 2L, 0, 0, false,
                diagnosticRunId = id
            )
            val archive = requireNotNull(SessionSampleArchiveManager.inspect(sampleFile))
            val runs = File(directory, "freedom_diagnostics/runs").apply { mkdirs() }
            val retained = File(runs.parentFile, "incident_history/$id").apply { mkdirs() }
            File(retained, "incident_retention.json").writeText("{}")
            File(retained, "incidents/incident-1").mkdirs()
            File(retained, "incidents/incident-1/transaction.json").writeText("""{"state":"UNRESOLVED"}""")
            val destination = File(directory, "retained.zip")
            SessionSampleArchiveManager.createExportBundle(listOf(archive), runs, destination)
            ZipFile(destination).use { bundle ->
                assertTrue(bundle.getEntry("diagnostics/$id/incidents/incident-1/transaction.json") != null)
                assertTrue(bundle.getEntry("diagnostics/$id/diagnostic_report.txt") == null)
                val manifest = bundle.getInputStream(bundle.getEntry("EXPORT-MANIFEST.txt")).bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("独立异常证据已打包"))
                assertTrue(manifest.contains("报告已包含=否"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun logOnlyBundleIncludesUnpairedSealedAndRetainedRunsButExcludesActiveHistory() {
        val directory = Files.createTempDirectory("freedom-log-only-export").toFile()
        try {
            val runs = File(directory, "freedom_diagnostics/runs").apply { mkdirs() }
            val sealed = File(runs, "normal-run").apply { mkdirs() }
            File(sealed, "diagnostic_manifest.json").writeText("{}")
            File(sealed, "events.jsonl").writeText("normal evidence")
            val active = File(runs, "active-run").apply { mkdirs() }
            File(active, "diagnostic_manifest.json").writeText("{}")
            File(active, "events.jsonl").writeText("not sealed")
            val retained = File(runs.parentFile, "incident_history/retained-run").apply { mkdirs() }
            File(retained, "incident_retention.json").writeText("{}")
            File(retained, "incidents/incident-1").mkdirs()
            File(retained, "incidents/incident-1/transaction.json").writeText("""{"state":"UNRESOLVED"}""")
            val destination = File(directory, "logs.zip")
            SessionSampleArchiveManager.createExportBundle(
                emptyList(), runs, destination, includeAllDiagnostics = true,
                recentLogText = "recent display", withSealedHistory = { copy -> copy(setOf("active-run")) }
            )
            ZipFile(destination).use { zip ->
                assertTrue(zip.getEntry("diagnostics/normal-run/events.jsonl") != null)
                assertTrue(zip.getEntry("diagnostics/active-run/events.jsonl") == null)
                assertTrue(zip.getEntry("diagnostics/retained-run/incidents/incident-1/transaction.json") != null)
                assertEquals("recent display", zip.getInputStream(zip.getEntry("recent-display.txt")).bufferedReader().use { it.readText() })
                val manifest = zip.getInputStream(zip.getEntry("EXPORT-MANIFEST.txt")).bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("运行尚未封存，本次未打包"))
            }
            assertTrue(File(sealed, "events.jsonl").isFile)
            assertTrue(File(retained, "incidents/incident-1/transaction.json").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun allHistoryExportDeduplicatesIdenticalIncidentCopiesAndPreservesDifferentEvidence() {
        val directory = Files.createTempDirectory("freedom-incident-export-dedup").toFile()
        try {
            val runs = File(directory, "freedom_diagnostics/runs").apply { mkdirs() }
            val history = File(runs.parentFile, "incident_history").apply { mkdirs() }
            for (id in listOf("same-run", "different-run")) {
                val primary = File(runs, id).apply { mkdirs() }
                val retained = File(history, id).apply { mkdirs() }
                File(primary, "diagnostic_manifest.json").writeText("{}")
                File(primary, "events.jsonl").writeText("canonical")
                File(primary, "event_segments").mkdirs()
                File(primary, "event_segments/normal-1.jsonl").writeText("duplicate")
                File(retained, "incident_retention.json").writeText("{}")
                for (source in listOf(primary, retained)) {
                    File(source, "incidents/incident-1").mkdirs()
                    File(source, "incidents/incident-1/transaction.json").writeText("original")
                }
                if (id == "different-run") {
                    File(retained, "incidents/incident-1/transaction.json").writeText("independent")
                }
            }
            val destination = File(directory, "logs.zip")
            SessionSampleArchiveManager.createExportBundle(emptyList(), runs, destination, includeAllDiagnostics = true)
            ZipFile(destination).use { zip ->
                assertTrue(zip.getEntry("diagnostics/same-run/incidents/incident-1/transaction.json") != null)
                assertTrue(zip.getEntry("incident_history/same-run/incidents/incident-1/transaction.json") == null)
                assertTrue(zip.getEntry("incident_history/different-run/incidents/incident-1/transaction.json") != null)
                assertTrue(zip.getEntry("diagnostics/same-run/event_segments/normal-1.jsonl") == null)
                assertTrue(zip.getEntry("diagnostics/same-run/events.jsonl") != null)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createRunArchive(
        directory: File,
        fileName: String,
        runId: String,
        startedAt: Long,
        finishedAt: Long,
        sessionLists: Int,
        chatBatches: Int,
        includeChatSamples: Boolean,
        includeCompleteEvidence: Boolean = false,
        diagnosticRunId: String? = null
    ): File {
        val file = File(directory, fileName)
        ZipOutputStream(file.outputStream()).use { output ->
            writeEntry(output, "run.jsonl", """
                {"event":"run_started","runId":"$runId","diagnosticRunId":${diagnosticRunId?.let { "\"$it\"" } ?: "null"},"startedAt":$startedAt}
                {"event":"run_finished","finishedAt":$finishedAt,"sessionLists":$sessionLists,"chatBatches":$chatBatches}
            """.trimIndent() + "\n")
            writeEntry(output, "session_lists.jsonl", "{}\n")
            if (includeChatSamples) writeEntry(output, "chat_samples.jsonl", "run-first-chat")
            if (includeCompleteEvidence) {
                writeEntry(output, "archive_manifest.json", "{\"schemaVersion\":1,\"integrityStatus\":\"HASH_INVENTORY_CREATED_WITH_CAPTURE_WARNINGS\",\"captureWarnings\":[\"NODE_TREE_SAMPLE_LIMIT_REACHED\"]}")
                writeEntry(output, "run_manifest.json", "{\"nodeTreeCaptureCount\":1,\"selectorDiagnosticCount\":1}")
                writeEntry(output, "runtime_logs.txt", "06:00:00.000  monitor started\n")
                writeEntry(output, "runtime_logs.jsonl", "{\"sequence\":1}\n")
                writeEntry(output, "event_timeline.jsonl", "{\"type\":\"WINDOW_CONTENT_CHANGED\"}\n")
                writeEntry(output, "node_search.jsonl", "{\"matchCount\":1}\n")
                writeEntry(output, "node_trees/page_001.json", "{}")
            }
            writeEntry(output, "screenshots/shot.png", "png-data")
        }
        return file
    }

    private fun writeEntry(output: ZipOutputStream, name: String, content: String) {
        output.putNextEntry(ZipEntry(name))
        output.write(content.toByteArray(Charsets.UTF_8))
        output.closeEntry()
    }
}
