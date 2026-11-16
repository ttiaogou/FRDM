package com.freedom.adapters.accessibility.diagnostics

import android.content.Context
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.diagnostics.NodeDumpFormat
import com.freedom.core.accessibility.diagnostics.NodeSelectorDiagnosticsBuffer
import com.freedom.core.accessibility.diagnostics.NodeDumpFormatter
import com.freedom.core.accessibility.diagnostics.NodeDumpOptions
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** In-memory snapshots kept separately by capture origin; no node content is written to preferences. */
object LatestAccessibilityNodeDump {
    @Volatile
    private var latestManualSnapshots: List<AccessibilityTreeSnapshot> = emptyList()
    @Volatile
    private var latestAutomaticSnapshots: List<AccessibilityTreeSnapshot> = emptyList()

    fun update(snapshot: AccessibilityTreeSnapshot, source: NodeCaptureSource = NodeCaptureSource.AUTOMATIC) =
        updateAll(listOf(snapshot), source)

    fun updateAll(snapshots: List<AccessibilityTreeSnapshot>, source: NodeCaptureSource) {
        val detached = snapshots.toList()
        when (source) {
            NodeCaptureSource.MANUAL -> latestManualSnapshots = detached
            NodeCaptureSource.AUTOMATIC -> latestAutomaticSnapshots = detached
        }
    }

    fun latest(source: NodeCaptureSource): AccessibilityTreeSnapshot? = latestAll(source).firstOrNull()

    fun latestAll(source: NodeCaptureSource): List<AccessibilityTreeSnapshot> = when (source) {
        NodeCaptureSource.MANUAL -> latestManualSnapshots
        NodeCaptureSource.AUTOMATIC -> latestAutomaticSnapshots
    }

    fun clear() {
        latestManualSnapshots = emptyList()
        latestAutomaticSnapshots = emptyList()
    }

    /** Creates a temporary ZIP consumed by the Activity's existing SAF document-save workflow. */
    fun createArchive(
        context: Context,
        source: NodeCaptureSource? = null,
        windowId: Int? = null
    ): File? {
        val available = when (source) {
            NodeCaptureSource.MANUAL -> latestManualSnapshots
            NodeCaptureSource.AUTOMATIC -> latestAutomaticSnapshots
            null -> latestManualSnapshots.ifEmpty { latestAutomaticSnapshots }
        }
        val snapshots = if (windowId == null) available else available.filter { it.windowId == windowId }
        if (snapshots.isEmpty()) return null
        val directory = File(context.cacheDir, "FreedomExports")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val destination = File(directory, "Freedom-Node-Dump-$stamp.zip")
        return runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->
                val manifestEntries = ArrayList<String>(snapshots.size)
                snapshots.forEachIndexed { index, snapshot ->
                    val safeBudget = snapshot.requestedMaxNodes.coerceIn(1, NodeDumpOptions.DEEP_MAX_NODES)
                    val json = NodeDumpFormatter.dump(snapshot, NodeDumpOptions(format = NodeDumpFormat.JSON, maxNodes = safeBudget, includeText = false))
                    val text = NodeDumpFormatter.dump(snapshot, NodeDumpOptions(format = NodeDumpFormat.TEXT, maxNodes = safeBudget, includeText = false))
                    val xml = NodeDumpFormatter.dump(snapshot, NodeDumpOptions(format = NodeDumpFormat.XML, maxNodes = safeBudget, includeText = false))
                    val windowPrefix = "windows/${index + 1}-id-${snapshot.windowId.coerceAtLeast(0)}"
                    writeEntry(output, "$windowPrefix/manifest.json", json.manifestJson)
                    writeEntry(output, "$windowPrefix/node_tree.json", json.content)
                    writeEntry(output, "$windowPrefix/node_tree.txt", text.content)
                    writeEntry(output, "$windowPrefix/node_tree.xml", xml.content)
                    val searchEvidence = NodeSelectorDiagnosticsBuffer.toJsonLines(snapshot.window)
                    if (searchEvidence.isNotBlank()) writeEntry(output, "$windowPrefix/node_search.jsonl", searchEvidence)
                    manifestEntries += "window=${snapshot.windowId}; package=${snapshot.window.packageName}; activity=${snapshot.window.activityClass}; " +
                        "nodes=${snapshot.visitedNodeCount}; truncated=${snapshot.truncated}; entry=$windowPrefix/"
                }
                writeEntry(output, "README.txt", buildString {
                    append("Freedom Accessibility Node Dump\n")
                    append("Target-app windows captured: ").append(snapshots.size).append('\n')
                    append("Text fields are omitted by default. This archive contains structural metadata only.\n")
                    append("Each window has its own JSON/TEXT/XML tree under windows/.\n")
                    append(manifestEntries.joinToString("\n")).append('\n')
                })
            }
            check(destination.isFile && destination.length() > 0L) { "节点 Dump ZIP 生成失败" }
            destination
        }.getOrElse {
            runCatching { destination.delete() }
            null
        }
    }

    private fun writeEntry(output: ZipOutputStream, name: String, value: String) {
        output.putNextEntry(ZipEntry(name))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.closeEntry()
    }
}
