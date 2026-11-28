"""D01-D1: exact retirement of the unused second ZIP packager."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MANAGER = "app/src/main/java/com/freedom/ui/logs/SessionSampleArchiveManager.kt"
TEST = "app/src/test/java/com/freedom/ui/logs/SessionSampleArchiveManagerTest.kt"
PREVIOUS = {MANAGER: "c1527cfb178dffd2b429924f0a245cb6327edde3", TEST: "755a8f1aa7c51037e3c1036223ea9e733d313423"}
CURRENT = {MANAGER: "f64aa840ffb36e8ae9a679af9bbcb2af7fc1efe5", TEST: "efbc15dfc2c46e3c976caa10270e07c34aa2fa25"}
REMOVED = "    /** Writes one aggregate ZIP. Each source run is isolated under its own runId directory. */\n    fun createBatchArchive(\n        archives: List<SessionSampleArchive>,\n        destination: File\n    ) {\n        require(archives.size >= 2) { \"批量导出至少需要选择两条采集任务\" }\n        require(archives.all { it.file.isFile }) { \"部分采集归档已不存在，请刷新列表\" }\n        destination.parentFile?.let { parent ->\n            check(parent.isDirectory || parent.mkdirs()) { \"无法创建批量导出临时目录\" }\n        }\n        if (destination.exists()) check(destination.delete()) { \"无法覆盖临时导出文件\" }\n\n        try {\n            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->\n                val manifest = StringBuilder()\n                    .append(\"Freedom 会话采集批量导出\\n\")\n                    .append(\"导出时间：\")\n                    .append(SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\", Locale.getDefault()).format(Date()))\n                    .append(\"\\n采集任务数量：\").append(archives.size).append(\"\\n\\n\")\n                val usedRunDirectoryNames = linkedSetOf<String>()\n                archives.forEachIndexed { index, archive ->\n                    val baseRunDirectoryName = safePathSegment(archive.runId)\n                    var safeRunId = baseRunDirectoryName\n                    var suffix = 2\n                    while (!usedRunDirectoryNames.add(safeRunId)) {\n                        safeRunId = \"$baseRunDirectoryName-$suffix\"\n                        suffix += 1\n                    }\n                    manifest.append(index + 1).append(\". \").append(archive.runId)\n                        .append(\" | \").append(archive.durationLabel())\n                        .append(\" | \").append(archive.sampleCountLabel())\n                        .append(\" | 完整性清单=\").append(archive.archiveIntegrityStatus ?: \"无\")\n                        .append(\" | 中断恢复=\").append(if (archive.recoveredAfterProcessRestart) \"是\" else \"否\")\n                        .append(\" | 采集告警=\").append(archive.captureWarnings.joinToString(\",\").ifBlank { \"无\" })\n                        .append('\\n')\n                    ZipFile(archive.file).use { source ->\n                        val entries = source.entries()\n                        while (entries.hasMoreElements()) {\n                            val entry = entries.nextElement()\n                            if (entry.isDirectory) continue\n                            val safeEntryName = safeEntryPath(entry.name)\n                            val targetName = \"$safeRunId/$safeEntryName\"\n                            output.putNextEntry(ZipEntry(targetName).apply {\n                                if (entry.time > 0L) time = entry.time\n                            })\n                            source.getInputStream(entry).use { input -> input.copyTo(output) }\n                            output.closeEntry()\n                        }\n                    }\n                }\n                output.putNextEntry(ZipEntry(\"EXPORT-MANIFEST.txt\"))\n                output.write(manifest.toString().toByteArray(Charsets.UTF_8))\n                output.closeEntry()\n            }\n            check(destination.isFile && destination.length() > 0L) { \"批量 ZIP 生成失败\" }\n        } catch (error: Exception) {\n            runCatching { destination.delete() }\n            throw error\n        }\n    }\n\n"
TEST_EDITS = [["    @Test\n    fun unifiedExportAcceptsOneRunAndPreservesSource() {\n        val directory = Files.createTempDirectory(\"freedom-sample-one\").toFile()\n        try {\n            val source = createRunArchive(directory, \"one.zip\", \"one\", 1L, 2L, 0, 0, false)\n            val archive = requireNotNull(SessionSampleArchiveManager.inspect(source))\n            val destination = File(directory, \"out.zip\")\n            val receipt = SessionSampleArchiveManager.createExportBundle(\n                listOf(archive), File(directory, \"diagnostics/runs\"), destination\n            )\n            ZipFile(destination).use { zip ->\n                assertTrue(zip.getEntry(\"samples/one/run.jsonl\") != null)\n                assertTrue(zip.getEntry(\"EXPORT-MANIFEST.txt\") != null)\n            }\n            assertEquals(listOf(\"one\"), receipt.sources.map { it.runId })\n            assertTrue(source.isFile)\n        } finally {\n            directory.deleteRecursively()\n        }\n    }\n\n    @Test(expected = IllegalArgumentException::class)\n    fun unifiedExportRejectsEmptySelectionWithoutDiagnostics() {\n        val directory = Files.createTempDirectory(\"freedom-sample-empty\").toFile()\n        try {\n            SessionSampleArchiveManager.createExportBundle(\n                emptyList(), File(directory, \"diagnostics/runs\"), File(directory, \"out.zip\")\n            )\n        } finally {\n            directory.deleteRecursively()\n        }\n    }","    @Test(expected = IllegalArgumentException::class)\n    fun batchArchiveRequiresAtLeastTwoRuns() {\n        val directory = Files.createTempDirectory(\"freedom-sample-one\").toFile()\n        try {\n            val source = createRunArchive(directory, \"one.zip\", \"one\", 1L, 2L, 0, 0, false)\n            val archive = requireNotNull(SessionSampleArchiveManager.inspect(source))\n            SessionSampleArchiveManager.createBatchArchive(listOf(archive), File(directory, \"out.zip\"))\n        } finally {\n            directory.deleteRecursively()\n        }\n    }"],["\"samples/run-second/run.jsonl\"","\"run-second/run.jsonl\""],["assertTrue(combined.getEntry(\"samples/run-first/chat_samples.jsonl\") != null)","assertTrue(combined.getEntry(\"run-first/chat_samples.jsonl\") != null)"],["                    combined.getEntry(\"samples/run-first/chat_samples.jsonl\")\n","                    combined.getEntry(\"run-first/chat_samples.jsonl\")\n"],["\"samples/run-first/run.jsonl\"","\"run-first/run.jsonl\""],["            val receipt = SessionSampleArchiveManager.createExportBundle(\n                listOf(first, second), File(directory, \"diagnostics/runs\"), destination\n            )\n            assertEquals(setOf(\"run-first\", \"run-second\"), receipt.sources\n                .filter { it.kind == SessionSampleArchiveManager.SourceKind.SAMPLE }\n                .map { it.runId }.toSet())","            SessionSampleArchiveManager.createBatchArchive(listOf(first, second), destination)"],["fun unifiedExportKeepsEachRunInItsOwnDirectory()","fun batchArchiveKeepsEachRunInItsOwnDirectory()"]]


def restore_d01d1_source(path, source):
    if path == MANAGER:
        anchor = "    private fun jsonString("
        if source.count(anchor) != 1:
            raise AssertionError("D01-D1 manager inverse anchor changed")
        source = source.replace(anchor, REMOVED + anchor, 1)
    elif path == TEST:
        for current, previous in TEST_EDITS:
            if source.count(current) != 1:
                raise AssertionError("D01-D1 test inverse anchor changed")
            source = source.replace(current, previous, 1)
    else:
        raise AssertionError("Unexpected D01-D1 path")
    if git_blob(source.encode("utf-8")) != PREVIOUS[path]:
        raise AssertionError("D01-D1 exact historical reconstruction failed: " + path)
    return source


class D01d1BatchPackagerRetirementTest(unittest.TestCase):
    def test_exact_retirement_and_test_migration(self):
        for path in (MANAGER, TEST):
            source = (ROOT / path).read_text(encoding="utf-8")
            self.assertEqual(CURRENT[path], git_blob(source.encode("utf-8")))
            self.assertEqual(PREVIOUS[path], git_blob(restore_d01d1_source(path, source).encode("utf-8")))

    def test_no_retired_packager_reference_in_any_module(self):
        issues = []
        for path in ROOT.rglob("*"):
            if not path.is_file() or path.suffix not in {".kt", ".java", ".xml", ".aidl", ".json", ".properties", ".kts"}:
                continue
            parts = path.relative_to(ROOT).parts
            if "src" not in parts or "build" in parts or ".git" in parts or ".gradle" in parts:
                continue
            if re.search(r"\bcreateBatchArchive\b", path.read_text(encoding="utf-8")):
                issues.append(str(path.relative_to(ROOT)))
        self.assertEqual([], issues)
        manager = (ROOT / MANAGER).read_text(encoding="utf-8")
        self.assertEqual(1, manager.count("fun createExportBundle("))
        self.assertIn("fun cleanupAfterVerifiedExport(", manager)

    def test_inverse_rejects_live_packager_damage(self):
        source = (ROOT / MANAGER).read_text(encoding="utf-8")
        for token in ("fun createExportBundle(", "sampleIsSealed(archive.file.parentFile, snapshot)", "fun cleanupAfterVerifiedExport("):
            self.assertIn(token, source)
            with self.assertRaises(AssertionError):
                restore_d01d1_source(MANAGER, source.replace(token, "BROKEN", 1))
