package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.logging.NodeTreeCaptureAdmission
import com.freedom.core.logging.NodeTreeCaptureBudgetPolicy
import com.freedom.core.logging.NodeTreeCapturePriority
import com.freedom.core.logging.RunLogBudgetPolicy
import com.freedom.core.accessibility.query.NodeTreeIndex
import com.freedom.core.accessibility.diagnostics.NodeDumpFormat
import com.freedom.core.accessibility.diagnostics.NodeDumpFormatter
import com.freedom.core.accessibility.diagnostics.NodeDumpOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Owns bounded structural node-tree dumps, deltas and the per-run node-tree capture quota. */
internal class MomoChatSampleNodeTreeStore(
    private val isRunAcceptingRecords: () -> Boolean,
    private val runDirectory: () -> File?
) {
    private companion object {
        const val MAX_NODE_TREE_NODES = 480
        const val MAX_NODE_TREE_SAMPLES = RunLogBudgetPolicy.MAX_SAMPLE_NODE_TREE_SAMPLES
    }

    private var nextNodeTreeIndex = 1
    private var lastNodeTreeSignature = ""
    private var lastNodeTreeIndex: NodeTreeIndex? = null
    private var keyPageNodeTreeCaptureCount = 0
    private var standardNodeTreeCaptureCount = 0
    private var standardNodeTreeQuotaSkippedCount = 0
    private var nodeTreeCaptureLimitReached = false
    private var nodeTreeWriteFailureCount = 0

    val captureCount: Int get() = nextNodeTreeIndex - 1
    val maxSamples: Int get() = MAX_NODE_TREE_SAMPLES
    val keyPageCount: Int get() = keyPageNodeTreeCaptureCount
    val standardCount: Int get() = standardNodeTreeCaptureCount
    val standardQuotaSkippedCount: Int get() = standardNodeTreeQuotaSkippedCount
    val captureLimitReached: Boolean get() = nodeTreeCaptureLimitReached
    val writeFailureCount: Int get() = nodeTreeWriteFailureCount

    fun resetForNewRun() {
        nextNodeTreeIndex = 1
        lastNodeTreeSignature = ""
        lastNodeTreeIndex = null
        keyPageNodeTreeCaptureCount = 0
        standardNodeTreeCaptureCount = 0
        standardNodeTreeQuotaSkippedCount = 0
        nodeTreeCaptureLimitReached = false
        nodeTreeWriteFailureCount = 0
    }

    fun record(
        snapshot: AccessibilityTreeSnapshot,
        priority: NodeTreeCapturePriority = NodeTreeCapturePriority.STANDARD
    ): Boolean {
        if (!isRunAcceptingRecords()) return false
        val directory = runDirectory() ?: return false
        val signature = MomoChatSampleDataMapper.structuralNodeTreeSignature(snapshot)
        if (signature.isBlank() || signature == lastNodeTreeSignature) return false

        when (NodeTreeCaptureBudgetPolicy.evaluate(
            priority = priority,
            totalCaptured = nextNodeTreeIndex - 1,
            standardCaptured = standardNodeTreeCaptureCount
        )) {
            NodeTreeCaptureAdmission.ALLOWED -> Unit
            NodeTreeCaptureAdmission.STANDARD_QUOTA_REACHED -> {
                standardNodeTreeQuotaSkippedCount++
                return false
            }
            NodeTreeCaptureAdmission.TOTAL_LIMIT_REACHED -> {
                nodeTreeCaptureLimitReached = true
                return false
            }
        }

        val currentTreeIndex = NodeTreeIndex(snapshot)
        val delta = lastNodeTreeIndex?.let(currentTreeIndex::diffFrom)
        val index = nextNodeTreeIndex
        val treeDirectory = File(directory, "node_trees")
        if (!treeDirectory.isDirectory && !treeDirectory.mkdirs()) {
            nodeTreeWriteFailureCount++
            return false
        }
        val baseName = "page_${index.toString().padStart(3, '0')}"
        val indexFile = File(treeDirectory, "index.jsonl")
        val deltaFile = File(treeDirectory, "deltas.jsonl")
        val originalIndexLength = indexFile.takeIf(File::isFile)?.length() ?: 0L
        val originalDeltaLength = deltaFile.takeIf(File::isFile)?.length() ?: 0L
        val json = NodeDumpFormatter.dump(
            snapshot,
            NodeDumpOptions(format = NodeDumpFormat.JSON, maxNodes = MAX_NODE_TREE_NODES, includeText = false)
        )
        val text = NodeDumpFormatter.dump(
            snapshot,
            NodeDumpOptions(format = NodeDumpFormat.TEXT, maxNodes = MAX_NODE_TREE_NODES, includeText = false)
        )
        val xml = NodeDumpFormatter.dump(
            snapshot,
            NodeDumpOptions(format = NodeDumpFormat.XML, maxNodes = MAX_NODE_TREE_NODES, includeText = false)
        )
        val outputFiles = listOf(
            File(treeDirectory, "$baseName.json"),
            File(treeDirectory, "$baseName.txt"),
            File(treeDirectory, "$baseName.xml")
        )
        val temporaryFiles = outputFiles.map { File(treeDirectory, it.name + ".tmp") }
        val manifestFile = File(treeDirectory, "manifest.json")
        val manifestTemporary = File(treeDirectory, "manifest.json.tmp")
        val manifestBackup = File(treeDirectory, "manifest.json.bak")
        return runCatching {
            listOf(json.content, text.content, xml.content).forEachIndexed { fileIndex, content ->
                temporaryFiles[fileIndex].writeText(content, Charsets.UTF_8)
                check(temporaryFiles[fileIndex].isFile) { "节点树临时文件未生成" }
            }
            temporaryFiles.forEachIndexed { fileIndex, temporary ->
                val destination = outputFiles[fileIndex]
                if (destination.exists()) check(destination.delete()) { "无法替换旧节点树文件" }
                check(temporary.renameTo(destination)) { "节点树文件提交失败" }
            }
            val captureManifest = JSONObject(json.manifestJson)
                .put("captureId", baseName)
                .put("capturePriority", priority.name)
            manifestTemporary.writeText(captureManifest.toString(2), Charsets.UTF_8)
            check(manifestTemporary.isFile) { "节点树清单临时文件未生成" }

            indexFile.appendText(
                JSONObject()
                    .put("captureId", baseName)
                    .put("capturePriority", priority.name)
                    .put("capturedAtEpochMs", snapshot.capturedAtEpochMs)
                    .put("packageName", snapshot.window.packageName)
                    .put("activityClass", snapshot.window.activityClass)
                    .put("windowGeneration", snapshot.window.generation)
                    .put("visitedNodeCount", snapshot.visitedNodeCount)
                    .put("exportedNodeCount", json.exportedNodeCount)
                    .put("walkTruncated", snapshot.walkTruncated)
                    .put("depthTruncated", snapshot.depthTruncated)
                    .put("dumpTruncated", json.truncated)
                    .put("stopReason", json.stopReason)
                    .put("signature", signature)
                    .put("deltaComparable", delta?.comparable ?: false)
                    .put("deltaReason", delta?.reason)
                    .put("addedNodeCount", delta?.addedPaths?.size ?: JSONObject.NULL)
                    .put("removedNodeCount", delta?.removedPaths?.size ?: JSONObject.NULL)
                    .put("changedNodeCount", delta?.changedPaths?.size ?: JSONObject.NULL)
                    .put("unchangedNodeCount", delta?.unchangedNodeCount ?: JSONObject.NULL)
                    .toString() + "\n",
                Charsets.UTF_8
            )
            if (delta != null) {
                deltaFile.appendText(
                    JSONObject()
                        .put("captureId", baseName)
                        .put("comparable", delta.comparable)
                        .put("reason", delta.reason)
                        .put("addedPaths", JSONArray().apply { delta.addedPaths.forEach { put(it.childIndices.joinToString("/")) } })
                        .put("removedPaths", JSONArray().apply { delta.removedPaths.forEach { put(it.childIndices.joinToString("/")) } })
                        .put("changedPaths", JSONArray().apply { delta.changedPaths.forEach { put(it.childIndices.joinToString("/")) } })
                        .put("unchangedNodeCount", delta.unchangedNodeCount)
                        .toString() + "\n",
                    Charsets.UTF_8
                )
            }
            // Publish the per-snapshot manifest last. Keep the previous manifest until both
            // append-only indexes are committed so a failed capture cannot erase prior evidence.
            if (manifestBackup.exists()) check(manifestBackup.delete()) { "无法清理旧节点树清单备份" }
            if (manifestFile.exists()) check(manifestFile.renameTo(manifestBackup)) { "无法备份旧节点树清单" }
            check(manifestTemporary.renameTo(manifestFile)) { "节点树清单提交失败" }
            runCatching { manifestBackup.delete() }

            nextNodeTreeIndex++
            when (priority) {
                NodeTreeCapturePriority.KEY_PAGE -> keyPageNodeTreeCaptureCount++
                NodeTreeCapturePriority.STANDARD -> standardNodeTreeCaptureCount++
            }
            lastNodeTreeSignature = signature
            lastNodeTreeIndex = currentTreeIndex
            true
        }.getOrElse {
            // Roll back append-only indexes and restore the last committed manifest.
            runCatching { if (indexFile.exists()) java.io.RandomAccessFile(indexFile, "rw").use { it.setLength(originalIndexLength) } }
            runCatching { if (deltaFile.exists()) java.io.RandomAccessFile(deltaFile, "rw").use { it.setLength(originalDeltaLength) } }
            if (manifestBackup.exists()) {
                runCatching { if (manifestFile.exists()) manifestFile.delete() }
                runCatching { manifestBackup.renameTo(manifestFile) }
            }
            (outputFiles + temporaryFiles + manifestTemporary).forEach {
                runCatching { it.delete() }
            }
            nodeTreeWriteFailureCount++
            false
        }
    }


}
