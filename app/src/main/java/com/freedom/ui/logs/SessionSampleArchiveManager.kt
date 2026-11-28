package com.freedom.ui.logs

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A completed, independently sealed AI session-collection run. */
internal data class SessionSampleArchive(
    val file: File,
    val runId: String,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
    val sessionListCount: Int,
    val chatBatchCount: Int,
    val entryNames: List<String>,
    val archiveIntegrityStatus: String? = null,
    val captureWarnings: List<String> = emptyList(),
    val recoveredAfterProcessRestart: Boolean = false,
    val nodeTreeCaptureCount: Int = 0,
    val selectorDiagnosticCount: Int = 0,
    val diagnosticRunId: String? = null
) {
    val durationMillis: Long
        get() {
            val start = startedAtMillis ?: return 0L
            val end = finishedAtMillis ?: return 0L
            return (end - start).coerceAtLeast(0L)
        }

    val contentLabels: List<String>
        get() = buildList {
            if ("run.jsonl" in entryNames) add("运行元信息")
            if (archiveIntegrityStatus != null) add("归档文件清单与 SHA-256 完整性证据")
            else if ("archive_manifest.json" in entryNames) add("归档清单存在但格式无法识别")
            if (recoveredAfterProcessRestart) add("进程中断后自动恢复封存")
            val captureOnlyWarnings = captureWarnings - setOf(
                "PROCESS_INTERRUPTED_RECOVERED", "ARCHIVE_SEAL_RESUMED_AFTER_PROCESS_RESTART"
            )
            if (captureOnlyWarnings.isNotEmpty()) add("采集数据存在截断或写入告警")
            if ("runtime_logs.txt" in entryNames || "runtime_logs.jsonl" in entryNames) add("本次运行日志")
            if (!diagnosticRunId.isNullOrBlank()) add("关联的运行诊断报告与事件记录（可随采集包一起导出）")
            if ("event_timeline.jsonl" in entryNames) add("脱敏无障碍事件时间线")
            if ("node_search.jsonl" in entryNames && selectorDiagnosticCount == 0) add("节点查找诊断")
            if ("session_lists.jsonl" in entryNames) add("会话列表样本")
            if ("session_navigation.jsonl" in entryNames) add("会话导航与标题诊断")
            if ("chat_samples.jsonl" in entryNames) add("聊天消息与 OCR 识别结果")
            if (entryNames.any { it.startsWith("screenshots/") || it.endsWith("_screenshots.jsonl") }) {
                add("截图文件及截图索引")
            }
            if (entryNames.any { it.startsWith("message_crops/") }) add("消息裁剪图")
            if (nodeTreeCaptureCount > 0) add("无障碍节点树 ${nodeTreeCaptureCount} 份及结构差异证据")
            if (selectorDiagnosticCount > 0) add("Selector 查询诊断 ${selectorDiagnosticCount} 条")
            val known = setOf(
                "run.jsonl", "run_manifest.json", "run_recovery.json", "archive_manifest.json", "runtime_logs.txt", "runtime_logs.jsonl",
                "event_timeline.jsonl", "node_search.jsonl", "session_lists.jsonl",
                "session_navigation.jsonl", "chat_samples.jsonl", "session_list_screenshots.jsonl",
                "chat_sample_screenshots.jsonl", "node_trees"
            )
            val hasOther = entryNames.any { name ->
                name !in known && !name.startsWith("screenshots/") &&
                    !name.startsWith("message_crops/") && !name.startsWith("node_trees/")
            }
            if (hasOther) add("其他采集文件")
        }

    fun durationLabel(): String {
        if (durationMillis <= 0L) return "时长未知"
        val totalSeconds = durationMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d小时%02d分%02d秒".format(hours, minutes, seconds)
        else "%02d分%02d秒".format(minutes, seconds)
    }

    fun sampleCountLabel(): String =
        "会话列表 ${sessionListCount} 次 · 聊天批次 ${chatBatchCount} 次 · " +
            "节点树 ${nodeTreeCaptureCount} 份 · Selector 诊断 ${selectorDiagnosticCount} 条"
}

/** Reads the sealed per-run ZIPs and builds a combined ZIP without modifying source archives. */
internal object SessionSampleArchiveManager {
    enum class SourceKind { SAMPLE, DIAGNOSTIC, RETAINED_INCIDENT }
    data class ExportSource(val file: File, val runId: String, val kind: SourceKind, val fingerprint: String)
    data class ExportReceipt(val zipSha256: String, val sources: List<ExportSource>)
    data class CleanupResult(val deleted: Int, val retained: Int, val failed: Int)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fingerprint(file: File): String {
        check(!Files.isSymbolicLink(file.toPath())) { "导出源不能是符号链接" }
        if (file.isFile) return file.inputStream().use(::sha256)
        check(file.isDirectory) { "导出源已不存在" }
        val root = file.canonicalFile
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().onFail { _, error -> throw error }.toList()
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { child ->
                check(!Files.isSymbolicLink(child.toPath())) { "导出源包含符号链接" }
                check(child.canonicalFile == root || child.canonicalPath.startsWith(root.path + File.separator)) { "导出源路径越界" }
                val relative = child.relativeTo(root).invariantSeparatorsPath
                val identity = if (child.isDirectory) "D" else {
                    check(child.isFile) { "导出源包含未知文件类型" }
                    "F:" + child.inputStream().use(::sha256)
                }
                digest.update("$relative\u0000$identity\u0000".toByteArray(Charsets.UTF_8))
            }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sourceSnapshot(file: File, id: String, kind: SourceKind): ExportSource =
        ExportSource(file, id, kind, fingerprint(file))

    private fun verifyZip(file: File) {
        ZipFile(file).use { zip ->
            check(zip.getEntry("EXPORT-MANIFEST.txt") != null) { "导出 ZIP 缺少清单" }
            val buffer = ByteArray(64 * 1024)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                CheckedInputStream(zip.getInputStream(entry), CRC32()).use { input ->
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                    }
                    check(size == entry.size && input.checksum.value == entry.crc) { "导出 ZIP 内容校验失败" }
                }
            }
        }
    }

    private fun directChild(root: File, file: File): Boolean =
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.canonicalFile

    private fun sampleIsSealed(root: File, source: ExportSource): Boolean =
        safeDiagnosticId(source.runId) && directChild(root, source.file) &&
            source.file.extension.equals("zip", true) &&
            !File(root, source.runId).exists() && !File(root, ".${source.runId}.active.json").exists()

    /** Only a byte-identical reopened external ZIP authorizes destructive cleanup.
     * The history-owner lock serializes cleanup against retention.
     */
    fun cleanupAfterVerifiedExport(
        receipt: ExportReceipt,
        savedZip: InputStream,
        sampleRoot: File,
        diagnosticsRunsDirectory: File,
        withSealedHistory: ((Set<String>) -> Unit) -> Unit
    ): CleanupResult {
        check(savedZip.use(::sha256) == receipt.zipSha256) { "已保存 ZIP 回读校验失败，原始数据未清理" }
        var deleted = 0
        var retained = 0
        var failed = 0
        fun cleanup(source: ExportSource, eligible: () -> Boolean) {
            try {
                if (!source.file.exists()) return
                if (!eligible() || fingerprint(source.file) != source.fingerprint) { retained++; return }
                val removed = if (source.kind == SourceKind.SAMPLE) source.file.delete()
                    else source.file.deleteRecursively()
                if (removed || !source.file.exists()) deleted++ else failed++
            } catch (_: Exception) { failed++ }
        }
        withSealedHistory { excluded ->
            receipt.sources.filter { it.kind == SourceKind.SAMPLE }.forEach { source ->
                cleanup(source) { sampleIsSealed(sampleRoot, source) }
            }
            // An unreadable archive or unfinished directory with unknown ownership blocks
            // diagnostic deletion; unselected archives keep their diagnostic dependencies.
            val referenced = mutableSetOf<String>()
            var unknownReference = false
            val remaining = if (sampleRoot.exists()) sampleRoot.listFiles() else emptyArray()
            if (remaining == null) unknownReference = true
            remaining.orEmpty().forEach { file ->
                if (file.isFile && file.extension.equals("zip", true)) {
                    val archive = inspect(file)
                    if (archive == null || ("run.jsonl" !in archive.entryNames && "run_manifest.json" !in archive.entryNames)) {
                        unknownReference = true
                    } else archive.diagnosticRunId?.let(referenced::add)
                } else if (file.isDirectory) {
                    val metadata = runCatching { File(file, "run.jsonl").bufferedReader().use { it.readLine() } }.getOrNull()
                    val id = metadata?.let { jsonString(it, "diagnosticRunId") }
                    if (id == null) unknownReference = true else referenced.add(id)
                } else if (file.name.endsWith(".active.json") || file.name.endsWith(".active.json.tmp")) {
                    val id = file.name.removePrefix(".").substringBefore(".active.json")
                    if (!File(sampleRoot, id).isDirectory) unknownReference = true
                }
            }
            val historyRoot = File(diagnosticsRunsDirectory.parentFile, "incident_history")
            receipt.sources.filter { it.kind != SourceKind.SAMPLE }.forEach { source ->
                val root = if (source.kind == SourceKind.DIAGNOSTIC) diagnosticsRunsDirectory else historyRoot
                val marker = if (source.kind == SourceKind.DIAGNOSTIC) "diagnostic_manifest.json" else "incident_retention.json"
                cleanup(source) {
                    val omittedEvidence = File(source.file, "events.jsonl.sealing").exists() ||
                        (File(source.file, "events.jsonl").isFile && File(source.file, "event_segments").exists())
                    !unknownReference && source.runId !in excluded && source.runId !in referenced &&
                        safeDiagnosticId(source.runId) && directChild(root, source.file) &&
                        source.file.name == source.runId && File(source.file, marker).isFile && !omittedEvidence
                }
            }
        }
        return CleanupResult(deleted, retained, failed)
    }

    /** Active marker files are maintained by the sample store until a verified ZIP is published. */
    fun listActiveRunIds(runsDirectory: File): Set<String> {
        if (!runsDirectory.isDirectory) return emptySet()
        return runsDirectory.listFiles()
            ?.asSequence()
            ?.filter { marker -> marker.isFile && marker.name.startsWith(".") && marker.name.endsWith(".active.json") }
            ?.map { marker -> marker.name.removePrefix(".").removeSuffix(".active.json") }
            ?.filter { runId ->
                runId.isNotBlank() && runId.length <= 120 &&
                    runId.matches(Regex("[A-Za-z0-9._-]+")) &&
                    // The owner marker is removed only after the verified ZIP is published
                    // and the source directory is removed. A ZIP file can exist while it is
                    // still being written or verified, so it does not make the run sealed.
                    File(runsDirectory, runId).isDirectory
            }
            ?.toSet()
            .orEmpty()
    }

    fun listArchives(runsDirectory: File): List<SessionSampleArchive> {
        if (!runsDirectory.isDirectory) return emptyList()
        return runsDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            ?.mapNotNull(::inspect)
            ?.sortedWith(compareByDescending<SessionSampleArchive> {
                it.finishedAtMillis ?: it.file.lastModified()
            }.thenByDescending { it.file.name })
            ?.toList()
            .orEmpty()
    }

    /** Lists only archives whose owner marker and source directory prove sealing has completed. */
    fun listSealedArchives(runsDirectory: File): List<SessionSampleArchive> {
        val activeRunIds = listActiveRunIds(runsDirectory)
        return listArchives(runsDirectory).filter { it.runId !in activeRunIds }
    }

    fun inspect(file: File): SessionSampleArchive? {
        if (!file.isFile || !file.extension.equals("zip", ignoreCase = true)) return null
        return runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .toList()
                val runLines = zip.getEntry("run.jsonl")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readLines() }
                }.orEmpty()
                val archiveManifestText = zip.getEntry("archive_manifest.json")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.orEmpty()
                val runManifestText = zip.getEntry("run_manifest.json")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.orEmpty()
                val recoveryText = zip.getEntry("run_recovery.json")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.orEmpty()
                var runId = file.nameWithoutExtension
                var startedAt: Long? = null
                var finishedAt: Long? = null
                var sessionLists = 0
                var chatBatches = 0
                var diagnosticRunId: String? = null
                runLines.forEach { line ->
                    val event = jsonString(line, "event")
                    when (event) {
                        "run_started" -> {
                            runId = jsonString(line, "runId")?.takeIf(String::isNotBlank) ?: runId
                            diagnosticRunId = jsonString(line, "diagnosticRunId")?.takeIf(String::isNotBlank)
                            startedAt = jsonLong(line, "startedAt")
                        }
                        "run_finished" -> {
                            finishedAt = jsonLong(line, "finishedAt")
                            sessionLists = jsonInt(line, "sessionLists") ?: sessionLists
                            chatBatches = jsonInt(line, "chatBatches") ?: chatBatches
                        }
                        "run_recovered_after_process_restart" -> {
                            // Use recovery time only when the original run never wrote run_finished.
                            finishedAt = finishedAt ?: jsonLong(line, "recoveredAtEpochMs")
                        }
                    }
                }
                diagnosticRunId = diagnosticRunId
                    ?: jsonString(runManifestText, "diagnosticRunId")?.takeIf(String::isNotBlank)
                val nodeTreeCountFromManifest = jsonInt(runManifestText, "nodeTreeCaptureCount")
                val nodeTreeCountFromEntries = entries.count { name ->
                    name.matches(Regex("node_trees/page_\\d+\\.json"))
                }
                SessionSampleArchive(
                    file = file,
                    runId = runId,
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt,
                    sessionListCount = sessionLists,
                    chatBatchCount = chatBatches,
                    entryNames = entries,
                    archiveIntegrityStatus = jsonString(archiveManifestText, "integrityStatus"),
                    captureWarnings = jsonStringArray(archiveManifestText, "captureWarnings"),
                    recoveredAfterProcessRestart = recoveryText.contains("\"collectionResumed\": false") ||
                        recoveryText.contains("\"recoveryReason\""),
                    nodeTreeCaptureCount = maxOf(nodeTreeCountFromManifest ?: 0, nodeTreeCountFromEntries),
                    selectorDiagnosticCount = maxOf(
                        jsonInt(runManifestText, "selectorDiagnosticCount") ?: 0,
                        zip.getEntry("node_search.jsonl")?.let { entry ->
                            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader -> reader.lineSequence().count() }
                        } ?: 0
                    ),
                    diagnosticRunId = diagnosticRunId
                )
            }
        }.getOrNull()
    }

    /**
     * Exports sample archives and their paired diagnostic runs in one ZIP. Source archives and
     * diagnostic directories are read-only; every run keeps its own directory and original paths.
     */
    fun createExportBundle(
        archives: List<SessionSampleArchive>,
        diagnosticsRunsDirectory: File,
        destination: File,
        includeAllDiagnostics: Boolean = false,
        recentLogText: String? = null,
        withSealedHistory: ((Set<String>) -> Unit) -> Unit = { copy -> copy(emptySet()) }
    ): ExportReceipt {
        val exportedSources = ArrayList<ExportSource>()
        require(archives.isNotEmpty() || includeAllDiagnostics) { "至少需要选择一条已封存采集任务" }
        require(archives.all { it.file.isFile }) { "部分采集归档已不存在，请刷新列表" }
        destination.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "无法创建运行诊断导出临时目录" }
        }
        if (destination.exists()) check(destination.delete()) { "无法覆盖临时导出文件" }

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->
                val manifest = StringBuilder()
                    .append("Freedom 运行诊断与会话采集导出\n")
                    .append("导出时间：")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    .append("\n采集任务数量：").append(archives.size).append("\n\n")
                val usedSampleNames = linkedSetOf<String>()
                val sampleCounts = mutableListOf<Int>()
                archives.forEach { archive ->
                    val snapshot = sourceSnapshot(archive.file, archive.runId, SourceKind.SAMPLE)
                    check(sampleIsSealed(archive.file.parentFile, snapshot)) { "采集归档尚未完成封存" }
                    val baseName = safePathSegment(archive.runId)
                    var sampleName = baseName
                    var suffix = 2
                    while (!usedSampleNames.add(sampleName)) {
                        sampleName = "$baseName-$suffix"
                        suffix++
                    }
                    var sampleEntries = 0
                    ZipFile(archive.file).use { source ->
                        val entries = source.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            val entryName = safeEntryPath(entry.name)
                            output.putNextEntry(ZipEntry("samples/$sampleName/$entryName").apply {
                                if (entry.time > 0L) time = entry.time
                            })
                            CheckedInputStream(source.getInputStream(entry), CRC32()).use { input ->
                                val size = input.copyTo(output)
                                check(size == entry.size && input.checksum.value == entry.crc) { "采集归档内容校验失败" }
                            }
                            output.closeEntry()
                            sampleEntries++
                        }
                    }
                    check(fingerprint(archive.file) == snapshot.fingerprint && sampleIsSealed(archive.file.parentFile, snapshot)) { "导出期间采集归档发生变化" }
                    exportedSources += snapshot
                    sampleCounts += sampleEntries
                }

                // The owner locks only diagnostic copying, never the potentially large sample ZIPs.
                withSealedHistory { excluded ->
                    val historyRoot = File(diagnosticsRunsDirectory.parentFile, "incident_history")
                    val linkedIds = archives.mapNotNull { it.diagnosticRunId?.takeIf(::safeDiagnosticId) }
                    val allIds = linkedSetOf<String>().apply {
                        addAll(linkedIds)
                        if (includeAllDiagnostics) {
                            addAll(diagnosticsRunsDirectory.listFiles().orEmpty().filter(File::isDirectory).map { it.name }.filter(::safeDiagnosticId).sorted())
                            addAll(historyRoot.listFiles().orEmpty().filter { it.isDirectory && File(it, "incident_retention.json").isFile }.map { it.name }.filter(::safeDiagnosticId).sorted())
                        }
                    }
                    val statuses = linkedMapOf<String, Pair<String, Boolean>>()
                    manifest.append("\n诊断历史范围：").append(if (includeAllDiagnostics) "全部已封存运行及独立异常历史" else "关联运行").append('\n')
                    fun copyAndRecord(directory: File, prefix: String, id: String, kind: SourceKind): Boolean {
                        val snapshot = sourceSnapshot(directory, id, kind)
                        val report = copyDiagnosticDirectory(output, directory, prefix)
                        check(fingerprint(directory) == snapshot.fingerprint) { "导出期间诊断历史发生变化" }
                        exportedSources += snapshot
                        return report
                    }
                    for (id in allIds) {
                        val primary = File(diagnosticsRunsDirectory, id)
                        val retained = File(historyRoot, id)
                        val primarySealed = id !in excluded &&
                            (File(primary, "diagnostic_manifest.json").isFile ||
                                (id in linkedIds && (File(primary, "diagnostic_report.json").isFile || File(primary, "diagnostic_report.txt").isFile)))
                        val retainedSealed = File(retained, "incident_retention.json").isFile
                        var reportIncluded = false
                        val status = when {
                            primarySealed && isContainedDirectory(diagnosticsRunsDirectory, primary) -> {
                                reportIncluded = copyAndRecord(primary, "diagnostics/$id", id, SourceKind.DIAGNOSTIC)
                                // A partial failed normal-history deletion must not hide the
                                // independently retained incident files. Verified identical copies
                                // are not exported twice.
                                val retainedSeparately = retainedSealed && isContainedDirectory(historyRoot, retained) &&
                                    !retainedEvidenceMatches(primary, retained)
                                if (retainedSeparately) {
                                    copyAndRecord(retained, "incident_history/$id", id, SourceKind.RETAINED_INCIDENT)
                                } else if (retainedSealed && isContainedDirectory(historyRoot, retained)) {
                                    val snapshot = sourceSnapshot(retained, id, SourceKind.RETAINED_INCIDENT)
                                    val paths = retained.walkTopDown().filter(File::isFile)
                                        .map { it.relativeTo(retained).invariantSeparatorsPath }.toList()
                                    if (paths.any { it.startsWith("incidents/") } &&
                                        paths.all { it.startsWith("incidents/") || it == "diagnostic_manifest.json" || it == "incident_retention.json" } &&
                                        retainedEvidenceMatches(primary, retained) && fingerprint(retained) == snapshot.fingerprint) {
                                        exportedSources += snapshot
                                    }
                                }
                                val primaryStatus = if (reportIncluded) "诊断报告及运行记录已打包" else "诊断记录已打包，TXT 报告不存在"
                                if (retainedSeparately) "$primaryStatus；独立异常副本存在差异，已另行保留" else primaryStatus
                            }
                            retainedSealed && isContainedDirectory(historyRoot, retained) -> {
                                copyAndRecord(retained, "diagnostics/$id", id, SourceKind.RETAINED_INCIDENT)
                                "普通运行历史已淘汰或不可用；独立异常证据已打包"
                            }
                            id in excluded -> "运行尚未封存，本次未打包"
                            else -> "未找到已封存的诊断运行目录"
                        }
                        statuses[id] = status to reportIncluded
                        manifest.append("诊断运行=").append(id).append(" | ").append(status).append('\n')
                    }
                    archives.forEachIndexed { index, archive ->
                        val id = archive.diagnosticRunId?.takeIf(::safeDiagnosticId)
                        val (status, report) = id?.let { statuses[it] } ?: ("未关联诊断运行 ID" to false)
                        manifest.append(index + 1).append(". 采集运行=").append(archive.runId)
                            .append(" | 诊断运行=").append(id ?: "无")
                            .append(" | 采集文件=").append(sampleCounts[index])
                            .append(" | 诊断状态=").append(status)
                            .append(" | 报告已包含=").append(if (report) "是" else "否").append('\n')
                    }
                }
                // Readable UI projection is generated only on demand, not persisted by a second writer.
                recentLogText?.let { text ->
                    output.putNextEntry(ZipEntry("recent-display.txt"))
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.closeEntry()
                    manifest.append("\nrecent-display.txt 为最近展示记录，不是完整诊断事件；运行中的诊断目录不参与封存导出。\n")
                }
                output.putNextEntry(ZipEntry("EXPORT-MANIFEST.txt"))
                output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            check(destination.isFile && destination.length() > 0L) { "运行诊断与采集 ZIP 生成失败" }
            verifyZip(destination)
            return ExportReceipt(destination.inputStream().use(::sha256), exportedSources.toList())
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw error
        }
    }

    private fun safeDiagnosticId(id: String): Boolean =
        id.matches(Regex("[A-Za-z0-9._-]{1,120}")) && id != "." && id != ".." && !id.startsWith(".")

    private fun isContainedDirectory(root: File, directory: File): Boolean =
        directory.isDirectory && directory.canonicalFile.path.startsWith(root.canonicalPath + File.separator)

    private fun copyDiagnosticDirectory(output: ZipOutputStream, directory: File, prefix: String): Boolean {
        val canonicalRoot = directory.canonicalFile
        var reportIncluded = false
        val canonicalEventsExist = File(canonicalRoot, "events.jsonl").isFile
        canonicalRoot.walkTopDown().filter(File::isFile)
            .filterNot { file ->
                val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                relative == "events.jsonl.sealing" ||
                    (canonicalEventsExist && relative.startsWith("event_segments/"))
            }
            .sortedBy { it.relativeTo(canonicalRoot).invariantSeparatorsPath }
            .forEach { file ->
                check(file.canonicalPath.startsWith(canonicalRoot.path + File.separator)) { "诊断文件路径越界" }
                val relative = safeEntryPath(file.relativeTo(canonicalRoot).invariantSeparatorsPath)
                output.putNextEntry(ZipEntry("$prefix/$relative").apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
                if (relative == "diagnostic_report.txt") reportIncluded = true
            }
        return reportIncluded
    }

    private fun retainedEvidenceMatches(primary: File, retained: File): Boolean =
        retained.walkTopDown().filter(File::isFile)
            .filter { it.relativeTo(retained).invariantSeparatorsPath.startsWith("incidents/") }
            .all { file ->
                val original = File(primary, file.relativeTo(retained).invariantSeparatorsPath)
                file.canonicalPath.startsWith(retained.canonicalPath + File.separator) &&
                    original.isFile && original.canonicalPath.startsWith(primary.canonicalPath + File.separator) &&
                    original.length() == file.length() && original.readBytes().contentEquals(file.readBytes())
            }

    /** Writes one aggregate ZIP. Each source run is isolated under its own runId directory. */
    fun createBatchArchive(
        archives: List<SessionSampleArchive>,
        destination: File
    ) {
        require(archives.size >= 2) { "批量导出至少需要选择两条采集任务" }
        require(archives.all { it.file.isFile }) { "部分采集归档已不存在，请刷新列表" }
        destination.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "无法创建批量导出临时目录" }
        }
        if (destination.exists()) check(destination.delete()) { "无法覆盖临时导出文件" }

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->
                val manifest = StringBuilder()
                    .append("Freedom 会话采集批量导出\n")
                    .append("导出时间：")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    .append("\n采集任务数量：").append(archives.size).append("\n\n")
                val usedRunDirectoryNames = linkedSetOf<String>()
                archives.forEachIndexed { index, archive ->
                    val baseRunDirectoryName = safePathSegment(archive.runId)
                    var safeRunId = baseRunDirectoryName
                    var suffix = 2
                    while (!usedRunDirectoryNames.add(safeRunId)) {
                        safeRunId = "$baseRunDirectoryName-$suffix"
                        suffix += 1
                    }
                    manifest.append(index + 1).append(". ").append(archive.runId)
                        .append(" | ").append(archive.durationLabel())
                        .append(" | ").append(archive.sampleCountLabel())
                        .append(" | 完整性清单=").append(archive.archiveIntegrityStatus ?: "无")
                        .append(" | 中断恢复=").append(if (archive.recoveredAfterProcessRestart) "是" else "否")
                        .append(" | 采集告警=").append(archive.captureWarnings.joinToString(",").ifBlank { "无" })
                        .append('\n')
                    ZipFile(archive.file).use { source ->
                        val entries = source.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            val safeEntryName = safeEntryPath(entry.name)
                            val targetName = "$safeRunId/$safeEntryName"
                            output.putNextEntry(ZipEntry(targetName).apply {
                                if (entry.time > 0L) time = entry.time
                            })
                            source.getInputStream(entry).use { input -> input.copyTo(output) }
                            output.closeEntry()
                        }
                    }
                }
                output.putNextEntry(ZipEntry("EXPORT-MANIFEST.txt"))
                output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            check(destination.isFile && destination.length() > 0L) { "批量 ZIP 生成失败" }
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw error
        }
    }

    private fun jsonString(line: String, key: String): String? {
        val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(line)
        return match?.groupValues?.getOrNull(1)
    }

    private fun jsonLong(line: String, key: String): Long? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)").find(line)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun jsonInt(line: String, key: String): Int? = jsonLong(line, key)?.toInt()

    private fun jsonStringArray(json: String, key: String): List<String> {
        val body = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\[(.*?)\\]", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(json)?.groupValues?.getOrNull(1).orEmpty()
        return Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(body)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()
    }

    private fun safePathSegment(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "run"
    }

    private fun safeEntryPath(value: String): String {
        val normalized = value.replace('\\', '/')
        require(!normalized.startsWith('/')) { "采集归档包含非法绝对路径" }
        val segments = normalized.split('/')
        require(segments.none { it == ".." || it.isBlank() }) { "采集归档包含非法相对路径" }
        return normalized
    }
}
