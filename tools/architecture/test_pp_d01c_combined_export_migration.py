"""D01-C: exact authorized export range changes; preserve D01-B and R08-D history."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = "app/src/main/java/com/freedom/MainActivity.kt"
MANAGER = "app/src/main/java/com/freedom/ui/logs/SessionSampleArchiveManager.kt"
FLOATING_TEST = "app/src/test/java/com/freedom/architecture/FloatingWindowLifecycleBoundaryTest.kt"
PREVIOUS = {ACTIVITY: "94a8c122f60d52f24d59b4edb96d212a7d6a295d", MANAGER: "7840a6f0f279bd4b6f33bc6814c6e053eed059ae", FLOATING_TEST: "9dc38c8285e88d2d30f033f414f46b7111e6eab2"}
CURRENT = {ACTIVITY: "099ca52fb0d6dcf1ae9d394fa624725f5d233a3c", MANAGER: "c1527cfb178dffd2b429924f0a245cb6327edde3", FLOATING_TEST: "0d3ac1e093e9a90f10290a2d11c463a94bcfe160"}
EDITS = {
    ACTIVITY: [["                    onSealed(emptySet())\n                }\n                return@Runnable","                    showSessionSampleArchiveExportPicker()\n                }\n                return@Runnable"],["                    onSealed(newlySealed.map { it.runId }.toSet())","                    showSessionSampleArchiveExportPicker(newlySealed.map { it.runId }.toSet())"],["                onSealed(sealedIds)","                showSessionSampleArchiveExportPicker(sealedIds)"],["        requestedAtMillis: Long = System.currentTimeMillis(),\n        onSealed: (Set<String>) -> Unit = { showSessionSampleArchiveExportPicker(it) }\n    ) {","        requestedAtMillis: Long = System.currentTimeMillis()\n    ) {"],["                    requestedAtMillis = requestedAtMillis,\n                    onSealed = onSealed\n                )","                    requestedAtMillis = requestedAtMillis\n                )"],["    private fun confirmStopCurrentRunAndExport(\n        activeRunIds: Set<String>,\n        onSealed: (Set<String>) -> Unit = { showSessionSampleArchiveExportPicker(it) }\n    ) {","    private fun confirmStopCurrentRunAndExport(activeRunIds: Set<String>) {"],["    private fun showCombinedExportActions() {\n        val archives = listSessionSampleArchives()\n        val activeRunIds = SessionSampleArchiveManager.listActiveRunIds(sessionSampleRunsDirectory())\n        if (activeRunIds.isEmpty()) {\n            confirmCombinedExport(archives)\n            return\n        }\n        val actions = listOf(\n            CollectionExportDialog.Action(\n                label = \"导出当前全部已封存数据\",\n                description = \"不停止当前运行；活动或尚未封存的数据不参与导出\",\n                primary = true\n            ) { confirmCombinedExport(archives) },\n            CollectionExportDialog.Action(\n                label = if (monitorRepository.isRunRequested()) \"停止当前采集、封存后综合导出\" else \"等待当前采集封存后综合导出\"\n            ) {\n                confirmStopCurrentRunAndExport(activeRunIds) {\n                    confirmCombinedExport(listSessionSampleArchives())\n                }\n            }\n        )\n        CollectionExportDialog.show(\n            activity = this,\n            dp = ::dp,\n            title = \"综合导出 ZIP\",\n            actions = actions,\n            message = \"已有 ${archives.size} 条已封存采集归档；另有 ${activeRunIds.size} 条采集尚未封存。\"\n        )\n    }\n\n    private fun confirmCombinedExport(archives: List<SessionSampleArchive>) {\n        AlertDialog.Builder(this)\n            .setTitle(\"综合导出 ZIP\")\n            .setMessage(\n                \"全部可用已封存采集归档（${archives.size} 条）、运行诊断、异常证据和最近展示日志将合并为一个 ZIP。\\n\\n\" +\n                    \"正在运行或尚未封存的目录不会打包；没有采集归档时仍可导出运行诊断。\\n\\n\" +\n                    \"内容可能包含聊天文本、截图及其他个人信息。保存并回读校验成功后，仅清理符合保护条件的源数据及已导出的展示日志；取消或保存失败保留原始数据。\"\n            )\n            .setNegativeButton(\"取消\", null)\n            .setPositiveButton(\"导出 ZIP\") { _, _ ->\n                val snapshot = monitorRepository.snapshotDisplayLogsForExport()\n                beginSessionSampleExport(archives, includeAllDiagnostics = true, displayLogs = snapshot)\n            }\n            .show()\n    }\n\n    private fun showArchiveExportActions() {","    private fun showArchiveExportActions() {"],["                CollectionExportDialog.Action(\n                    label = \"综合导出 ZIP\",\n                    description = \"默认打包全部可用已封存采集归档、运行诊断及最近展示日志\",\n                    primary = true\n                ) { showCombinedExportActions() },\n                CollectionExportDialog.Action(\n                    label = \"导出运行诊断 ZIP\",\n                    description = \"运行日志、诊断报告、已封存异常证据\"\n                ) { launchLogExport() },\n                CollectionExportDialog.Action(\n                    label = \"导出完整采集归档 ZIP\",\n                    description = \"会话样本、截图、OCR、节点树及关联运行诊断；可选择归档或等待封存\"\n                ) { showArchiveExportActions() },\n            ),\n            message = \"导出可能包含个人信息。保存并回读校验成功后，符合保护条件的源数据及已导出的展示日志将被清理；取消或保存失败保留原始数据。\"\n        )","                CollectionExportDialog.Action(\n                    label = \"采集 ZIP 运行日志\",\n                    description = \"运行日志、诊断报告及已封存的异常证据\",\n                    primary = true\n                ) { launchLogExport() },\n                CollectionExportDialog.Action(\n                    label = \"已封存采集归档 ZIP\",\n                    description = \"查看归档、选择导出或等待当前采集封存\"\n                ) { showArchiveExportActions() },\n            )\n        )"]],
    MANAGER: [["(id in linkedIds && (File(primary, \"diagnostic_report.json\").isFile || File(primary, \"diagnostic_report.txt\").isFile))","(!includeAllDiagnostics && (File(primary, \"diagnostic_report.json\").isFile || File(primary, \"diagnostic_report.txt\").isFile))"]],
    FLOATING_TEST: [["            .substringBefore(\"private fun showCombinedExportActions()\")\n","            .substringBefore(\"private fun showArchiveExportActions()\")\n"],["        assertTrue(recordsEntry.contains(\"导出运行诊断 ZIP\"))\n        assertTrue(recordsEntry.contains(\"导出完整采集归档 ZIP\"))\n        assertTrue(recordsEntry.contains(\"综合导出 ZIP\"))\n        val combinedEntry = recordsEntry.substringBefore(\"launchLogExport()\")\n        assertTrue(combinedEntry.contains(\"primary = true\\n                ) { showCombinedExportActions() }\"))\n        assertTrue(recordsEntry.split(\"CollectionExportDialog.Action(\").size - 1 == 3)","        assertTrue(recordsEntry.contains(\"采集 ZIP 运行日志\"))\n        assertTrue(recordsEntry.contains(\"已封存采集归档 ZIP\"))"]],
}


def restore_d01c_source(path, source):
    for current, previous in EDITS[path]:
        if source.count(current) != 1:
            raise AssertionError("D01-C inverse anchor changed: " + current[:100])
        source = source.replace(current, previous, 1)
    if git_blob(source.encode("utf-8")) != PREVIOUS[path]:
        raise AssertionError("D01-C baseline not reconstructed: " + path)
    return source


class D01cCombinedExportMigrationTest(unittest.TestCase):
    def test_exact_authorized_changes(self):
        for path in EDITS:
            source = (ROOT / path).read_text(encoding="utf-8")
            self.assertEqual(CURRENT[path], git_blob(source.encode("utf-8")))
            self.assertEqual(PREVIOUS[path], git_blob(restore_d01c_source(path, source).encode("utf-8")))

    def test_single_transaction_and_default_comprehensive_scope(self):
        activity = (ROOT / ACTIVITY).read_text(encoding="utf-8")
        manager = (ROOT / MANAGER).read_text(encoding="utf-8")
        entry = activity.split("private fun showCollectionExportActions()", 1)[1].split("private fun showCombinedExportActions()", 1)[0]
        self.assertEqual(3, entry.count("CollectionExportDialog.Action("))
        self.assertLess(entry.index('label = "综合导出 ZIP"'), entry.index('label = "导出运行诊断 ZIP"'))
        self.assertEqual(1, activity.count("SessionSampleArchiveManager.createExportBundle("))
        self.assertEqual(1, activity.count("cleanupAfterVerifiedExport("))
        self.assertIn("beginSessionSampleExport(archives, includeAllDiagnostics = true, displayLogs = snapshot)", activity)
        self.assertIn("beginSessionSampleExport(emptyList(), includeAllDiagnostics = true, displayLogs = snapshot)", activity)
        self.assertIn("confirmStopCurrentRunAndExport(activeRunIds)", activity)
        self.assertIn("onSealed = onSealed", activity)
        self.assertIn("id !in excluded &&", manager)
        self.assertIn("id in linkedIds &&", manager)

    def test_mutations_reject_removed_sealing_or_cleanup_guards(self):
        for path, token in ((ACTIVITY, "cleanupAfterVerifiedExport("), (MANAGER, "id !in excluded &&"), (MANAGER, "sampleIsSealed(archive.file.parentFile, snapshot)")):
            source = (ROOT / path).read_text(encoding="utf-8")
            self.assertIn(token, source)
            with self.assertRaises(AssertionError):
                restore_d01c_source(path, source.replace(token, "BROKEN", 1))
