"""D01-B: retire only the independent node ZIP path; preserve frozen history."""
import unittest
import re
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_d01c_combined_export_migration import restore_d01c_source

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = "app/src/main/java/com/freedom/MainActivity.kt"
CACHE = "app/src/main/java/com/freedom/adapters/accessibility/diagnostics/LatestAccessibilityNodeDump.kt"
ACTIVITY_REMOVALS = [[30,"import com.freedom.adapters.accessibility.diagnostics.LatestAccessibilityNodeDump\n"],[82,"    private var pendingNodeDumpExportFile: File? = null\n"],[1782,"                CollectionExportDialog.Action(\n"],[1783,"                    label = \"导出最近节点预览 ZIP\",\n"],[1784,"                    description = \"独立的节点结构快照\"\n"],[1785,"                ) { launchNodeDumpExport() }\n"],[1988,"    /** Exports the latest structural-only node snapshot through the existing SAF path. */\n"],[1989,"    private fun launchNodeDumpExport(\n"],[1990,"        source: com.freedom.core.accessibility.diagnostics.NodeCaptureSource? = null,\n"],[1991,"        windowId: Int? = null\n"],[1992,"    ) {\n"],[1993,"        val archive = LatestAccessibilityNodeDump.createArchive(this, source, windowId)\n"],[1994,"        if (archive == null) {\n"],[1995,"            Toast.makeText(this, \"暂无可导出的节点快照，请先运行并等待节点预览采集\", Toast.LENGTH_LONG).show()\n"],[1996,"            return\n"],[1997,"        }\n"],[1998,"        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {\n"],[1999,"            addCategory(Intent.CATEGORY_OPENABLE)\n"],[2000,"            type = \"application/zip\"\n"],[2001,"            putExtra(Intent.EXTRA_TITLE, archive.name)\n"],[2002,"        }\n"],[2003,"        pendingNodeDumpExportFile = archive\n"],[2004,"        try {\n"],[2005,"            suppressFloatingButtonOnPause = true\n"],[2006,"            @Suppress(\"DEPRECATION\")\n"],[2007,"            startActivityForResult(intent, REQUEST_EXPORT_NODE_DUMP)\n"],[2008,"        } catch (error: Exception) {\n"],[2009,"            suppressFloatingButtonOnPause = false\n"],[2010,"            pendingNodeDumpExportFile = null\n"],[2011,"            runCatching { archive.delete() }\n"],[2012,"            monitorRepository.addLog(timestamp() + \"  启动节点 Dump 导出失败：${error.javaClass.simpleName}\")\n"],[2013,"            Toast.makeText(this, \"无法打开节点结构保存窗口，请稍后重试\", Toast.LENGTH_LONG).show()\n"],[2014,"        }\n"],[2015,"    }\n"],[2016,"\n"],[2017,"    private fun exportNodeDumpArchive(resultCode: Int, data: Intent?) {\n"],[2018,"        val archive = pendingNodeDumpExportFile\n"],[2019,"        pendingNodeDumpExportFile = null\n"],[2020,"        if (resultCode != RESULT_OK || data?.data == null) {\n"],[2021,"            archive?.let { runCatching { it.delete() } }\n"],[2022,"            Toast.makeText(this, \"已取消节点结构导出\", Toast.LENGTH_SHORT).show()\n"],[2023,"            return\n"],[2024,"        }\n"],[2025,"        if (archive == null || !archive.isFile) {\n"],[2026,"            Toast.makeText(this, \"节点结构临时文件已失效，请重新采集\", Toast.LENGTH_LONG).show()\n"],[2027,"            return\n"],[2028,"        }\n"],[2029,"        Thread {\n"],[2030,"            val result = runCatching {\n"],[2031,"                val output = contentResolver.openOutputStream(data.data!!, \"w\")\n"],[2032,"                    ?: throw java.io.IOException(\"无法打开所选文件\")\n"],[2033,"                output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }\n"],[2034,"            }\n"],[2035,"            runCatching { archive.delete() }\n"],[2036,"            runOnUiThread {\n"],[2037,"                result.onSuccess {\n"],[2038,"                    Toast.makeText(this, \"节点结构 JSON/TXT/XML 已导出\", Toast.LENGTH_LONG).show()\n"],[2039,"                }.onFailure { error ->\n"],[2040,"                    monitorRepository.addLog(timestamp() + \"  写入节点 Dump ZIP 失败：${error.javaClass.simpleName}\")\n"],[2041,"                    Toast.makeText(this, \"节点结构导出失败：${error.message ?: error.javaClass.simpleName}\", Toast.LENGTH_LONG).show()\n"],[2042,"                }\n"],[2043,"            }\n"],[2044,"        }.start()\n"],[2045,"    }\n"],[2046,"\n"],[2265,"        if (requestCode == REQUEST_EXPORT_NODE_DUMP) {\n"],[2266,"            exportNodeDumpArchive(resultCode, data)\n"],[2267,"            return\n"],[2268,"        }\n"],[2294,"        private const val REQUEST_EXPORT_NODE_DUMP = 2005\n"]]
CACHE_REMOVALS = [[2,"import android.content.Context\n"],[4,"import com.freedom.core.accessibility.diagnostics.NodeDumpFormat\n"],[5,"import com.freedom.core.accessibility.diagnostics.NodeSelectorDiagnosticsBuffer\n"],[6,"import com.freedom.core.accessibility.diagnostics.NodeDumpFormatter\n"],[7,"import com.freedom.core.accessibility.diagnostics.NodeDumpOptions\n"],[9,"import java.io.BufferedOutputStream\n"],[10,"import java.io.File\n"],[11,"import java.io.FileOutputStream\n"],[12,"import java.text.SimpleDateFormat\n"],[13,"import java.util.Date\n"],[14,"import java.util.Locale\n"],[15,"import java.util.zip.ZipEntry\n"],[16,"import java.util.zip.ZipOutputStream\n"],[47,"\n"],[48,"    /** Creates a temporary ZIP consumed by the Activity's existing SAF document-save workflow. */\n"],[49,"    fun createArchive(\n"],[50,"        context: Context,\n"],[51,"        source: NodeCaptureSource? = null,\n"],[52,"        windowId: Int? = null\n"],[53,"    ): File? {\n"],[54,"        val available = when (source) {\n"],[55,"            NodeCaptureSource.MANUAL -> latestManualSnapshots\n"],[56,"            NodeCaptureSource.AUTOMATIC -> latestAutomaticSnapshots\n"],[57,"            null -> latestManualSnapshots.ifEmpty { latestAutomaticSnapshots }\n"],[58,"        }\n"],[59,"        val snapshots = if (windowId == null) available else available.filter { it.windowId == windowId }\n"],[60,"        if (snapshots.isEmpty()) return null\n"],[61,"        val directory = File(context.cacheDir, \"FreedomExports\")\n"],[62,"        if (!directory.isDirectory && !directory.mkdirs()) return null\n"],[63,"        val stamp = SimpleDateFormat(\"yyyyMMdd-HHmmss-SSS\", Locale.US).format(Date())\n"],[64,"        val destination = File(directory, \"Freedom-Node-Dump-$stamp.zip\")\n"],[65,"        return runCatching {\n"],[66,"            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->\n"],[67,"                val manifestEntries = ArrayList<String>(snapshots.size)\n"],[68,"                snapshots.forEachIndexed { index, snapshot ->\n"],[69,"                    val safeBudget = snapshot.requestedMaxNodes.coerceIn(1, NodeDumpOptions.DEEP_MAX_NODES)\n"],[70,"                    val json = NodeDumpFormatter.dump(snapshot, NodeDumpOptions(format = NodeDumpFormat.JSON, maxNodes = safeBudget, includeText = false))\n"],[71,"                    val text = NodeDumpFormatter.dump(snapshot, NodeDumpOptions(format = NodeDumpFormat.TEXT, maxNodes = safeBudget, includeText = false))\n"],[72,"                    val xml = NodeDumpFormatter.dump(snapshot, NodeDumpOptions(format = NodeDumpFormat.XML, maxNodes = safeBudget, includeText = false))\n"],[73,"                    val windowPrefix = \"windows/${index + 1}-id-${snapshot.windowId.coerceAtLeast(0)}\"\n"],[74,"                    writeEntry(output, \"$windowPrefix/manifest.json\", json.manifestJson)\n"],[75,"                    writeEntry(output, \"$windowPrefix/node_tree.json\", json.content)\n"],[76,"                    writeEntry(output, \"$windowPrefix/node_tree.txt\", text.content)\n"],[77,"                    writeEntry(output, \"$windowPrefix/node_tree.xml\", xml.content)\n"],[78,"                    val searchEvidence = NodeSelectorDiagnosticsBuffer.toJsonLines(snapshot.window)\n"],[79,"                    if (searchEvidence.isNotBlank()) writeEntry(output, \"$windowPrefix/node_search.jsonl\", searchEvidence)\n"],[80,"                    manifestEntries += \"window=${snapshot.windowId}; package=${snapshot.window.packageName}; activity=${snapshot.window.activityClass}; \" +\n"],[81,"                        \"nodes=${snapshot.visitedNodeCount}; truncated=${snapshot.truncated}; entry=$windowPrefix/\"\n"],[82,"                }\n"],[83,"                writeEntry(output, \"README.txt\", buildString {\n"],[84,"                    append(\"Freedom Accessibility Node Dump\\n\")\n"],[85,"                    append(\"Target-app windows captured: \").append(snapshots.size).append('\\n')\n"],[86,"                    append(\"Text fields are omitted by default. This archive contains structural metadata only.\\n\")\n"],[87,"                    append(\"Each window has its own JSON/TEXT/XML tree under windows/.\\n\")\n"],[88,"                    append(manifestEntries.joinToString(\"\\n\")).append('\\n')\n"],[89,"                })\n"],[90,"            }\n"],[91,"            check(destination.isFile && destination.length() > 0L) { \"节点 Dump ZIP 生成失败\" }\n"],[92,"            destination\n"],[93,"        }.getOrElse {\n"],[94,"            runCatching { destination.delete() }\n"],[95,"            null\n"],[96,"        }\n"],[97,"    }\n"],[98,"\n"],[99,"    private fun writeEntry(output: ZipOutputStream, name: String, value: String) {\n"],[100,"        output.putNextEntry(ZipEntry(name))\n"],[101,"        output.write(value.toByteArray(Charsets.UTF_8))\n"],[102,"        output.closeEntry()\n"],[103,"    }\n"]]
PREVIOUS = {ACTIVITY: "7e4148b1006924f66b11308f394b7752279f0984", CACHE: "0373e7e20e2d921aebb2559fe910a6c7aed22c67"}
CURRENT = {ACTIVITY: "94a8c122f60d52f24d59b4edb96d212a7d6a295d", CACHE: "2d636faa98ed11fdecae133692ab3b4ff720905e"}


def restore_exact(source, removals, expected):
    lines = source.splitlines(keepends=True)
    for index, line in removals:
        lines.insert(index, line)
    restored = "".join(lines)
    if git_blob(restored.encode("utf-8")) != expected:
        raise AssertionError("D01-B exact historical reconstruction failed")
    return restored


def restore_d01b_activity(source):
    source = restore_d01c_source(ACTIVITY, source)
    return restore_exact(source, ACTIVITY_REMOVALS, PREVIOUS[ACTIVITY])


class D01bNodeZipRetirementTest(unittest.TestCase):
    def test_exact_removals_only_and_retained_cache_contract(self):
        for path, removals in ((ACTIVITY, ACTIVITY_REMOVALS), (CACHE, CACHE_REMOVALS)):
            source = (ROOT / path).read_text(encoding="utf-8")
            if path == ACTIVITY:
                source = restore_d01c_source(path, source)
            self.assertEqual(CURRENT[path], git_blob(source.encode("utf-8")))
            self.assertEqual(PREVIOUS[path], git_blob(restore_exact(source, removals, PREVIOUS[path]).encode("utf-8")))
        activity = (ROOT / ACTIVITY).read_text(encoding="utf-8")
        cache = (ROOT / CACHE).read_text(encoding="utf-8")
        self.assertNotIn("fun createArchive(", cache)
        for token in ("fun update(", "fun updateAll(", "fun latestAll(", "fun clear("):
            self.assertIn(token, cache)
        for token in ("launchLogExport()", "showArchiveExportActions()", "cleanupAfterVerifiedExport(", "exportSessionSampleArchive(resultCode, data)"):
            self.assertIn(token, activity)

    def test_retired_zip_has_no_production_reference(self):
        retired = re.compile(r"\b(?:launchNodeDumpExport|exportNodeDumpArchive|pendingNodeDumpExportFile|REQUEST_EXPORT_NODE_DUMP)\b|LatestAccessibilityNodeDump\s*\.\s*createArchive\s*\(|Freedom-Node-Dump-|导出最近节点预览 ZIP")
        issues = []
        for path in (ROOT / "app" / "src" / "main").rglob("*"):
            if path.is_file() and path.suffix in {".kt", ".java", ".xml", ".json", ".properties"}:
                if retired.search(path.read_text(encoding="utf-8")):
                    issues.append(str(path.relative_to(ROOT)))
        self.assertEqual([], issues)

    def test_inverse_rejects_damage_to_live_export_and_cache(self):
        activity = (ROOT / ACTIVITY).read_text(encoding="utf-8")
        cache = (ROOT / CACHE).read_text(encoding="utf-8")
        for token in ("launchLogExport()", "cleanupAfterVerifiedExport("):
            with self.assertRaises(AssertionError):
                restore_d01b_activity(activity.replace(token, "retired", 1))
        with self.assertRaises(AssertionError):
            restore_exact(cache.replace("fun latestAll(", "fun broken(", 1), CACHE_REMOVALS, PREVIOUS[CACHE])
