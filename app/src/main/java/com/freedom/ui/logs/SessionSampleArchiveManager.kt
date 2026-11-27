package com.freedom.ui.logs

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
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
        destination: File
    ) {
        require(archives.isNotEmpty()) { "至少需要选择一条已封存采集任务" }
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
                val copiedDiagnosticIds = linkedSetOf<String>()
                val copiedDiagnosticReports = linkedMapOf<String, Boolean>()

                archives.forEachIndexed { index, archive ->
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
                            val targetName = "samples/$sampleName/$entryName"
                            output.putNextEntry(ZipEntry(targetName).apply {
                                if (entry.time > 0L) time = entry.time
                            })
                            source.getInputStream(entry).use { input -> input.copyTo(output) }
                            output.closeEntry()
                            sampleEntries++
                        }
                    }

                    val diagnosticId = archive.diagnosticRunId
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,120}")) && it != "." && it != ".." }
                    var reportIncluded = false
                    var diagnosticStatus = "未关联诊断运行 ID"
                    if (diagnosticId != null) {
                        val primary = File(diagnosticsRunsDirectory, diagnosticId)
                        val historyRoot = File(diagnosticsRunsDirectory.parentFile, "incident_history")
                        val retained = File(historyRoot, diagnosticId)
                        val useRetained = !File(primary, "diagnostic_report.json").isFile &&
                            !File(primary, "diagnostic_report.txt").isFile &&
                            File(retained, "incident_retention.json").isFile
                        val diagnosticDirectory = if (useRetained) retained else primary
                        val canonicalRoot = (if (useRetained) historyRoot else diagnosticsRunsDirectory).canonicalFile
                        val canonicalDirectory = diagnosticDirectory.canonicalFile
                        if (canonicalDirectory.path.startsWith(canonicalRoot.path + File.separator) &&
                            canonicalDirectory.isDirectory
                        ) {
                            if (copiedDiagnosticIds.add(diagnosticId)) {
                                val canonicalRunRoot = canonicalDirectory
                                canonicalDirectory.walkTopDown()
                                    .filter { it.isFile }
                                    .sortedBy { it.relativeTo(canonicalDirectory).invariantSeparatorsPath }
                                    .forEach { file ->
                                        val canonicalFile = file.canonicalFile
                                        if (!canonicalFile.path.startsWith(canonicalRunRoot.path + File.separator)) return@forEach
                                        val relative = safeEntryPath(file.relativeTo(canonicalDirectory).invariantSeparatorsPath)
                                        val targetName = "diagnostics/$diagnosticId/$relative"
                                        output.putNextEntry(ZipEntry(targetName).apply {
                                            time = file.lastModified()
                                        })
                                        file.inputStream().use { it.copyTo(output) }
                                        output.closeEntry()
                                        if (relative == "diagnostic_report.txt") reportIncluded = true
                                    }
                                copiedDiagnosticReports[diagnosticId] = reportIncluded
                            } else {
                                // The same diagnostic run is included once for safety and size.
                                reportIncluded = copiedDiagnosticReports[diagnosticId] == true
                                diagnosticStatus = if (reportIncluded) {
                                    "与前一采集任务共享诊断运行，诊断报告已在前项中打包"
                                } else {
                                    "与前一采集任务共享诊断运行，但前项未包含报告"
                                }
                            }
                            if (diagnosticStatus == "未关联诊断运行 ID") {
                                diagnosticStatus = when {
                                    reportIncluded -> "诊断报告及运行记录已打包"
                                    useRetained -> "普通运行历史已淘汰；独立异常证据已打包"
                                    File(canonicalDirectory, "diagnostic_report.txt").isFile -> "诊断报告存在但未能安全读取或打包"
                                    else -> "诊断目录存在，但报告文件尚未生成"
                                }
                            }
                        } else {
                            diagnosticStatus = "未找到匹配的诊断运行目录"
                        }
                    }
                    manifest.append(index + 1).append(". 采集运行=").append(archive.runId)
                        .append(" | 诊断运行=").append(diagnosticId ?: "无")
                        .append(" | 采集文件=").append(sampleEntries)
                        .append(" | 诊断状态=").append(diagnosticStatus)
                        .append(" | 报告已包含=").append(if (reportIncluded) "是" else "否")
                        .append('\n')
                }
                output.putNextEntry(ZipEntry("EXPORT-MANIFEST.txt"))
                output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            check(destination.isFile && destination.length() > 0L) { "运行诊断与采集 ZIP 生成失败" }
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw error
        }
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
