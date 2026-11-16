package com.freedom.data.samples

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Builds and verifies the file-level inventory for one sealed AI observation run.
 * The manifest intentionally excludes its own hash to avoid a recursive checksum.
 */
internal object SessionSampleArchiveIntegrity {
    const val MANIFEST_NAME = "archive_manifest.json"

    data class FileRecord(
        val path: String,
        val sizeBytes: Long,
        val sha256: String,
        val category: String
    )

    data class Snapshot(
        val runId: String,
        val generatedAtEpochMs: Long,
        val warnings: List<String>,
        val files: List<FileRecord>,
        val manifestSizeBytes: Long,
        val manifestSha256: String
    )

    data class Verification(
        val valid: Boolean,
        val checkedFileCount: Int,
        val errors: List<String>
    )

    fun writeManifest(
        runDirectory: File,
        runId: String,
        generatedAtEpochMs: Long,
        warnings: List<String>
    ): Snapshot {
        require(runDirectory.isDirectory) { "采集目录不存在" }
        val canonicalRoot = runDirectory.canonicalFile
        val records = runDirectory.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.relativeTo(runDirectory).invariantSeparatorsPath in setOf(MANIFEST_NAME, "$MANIFEST_NAME.tmp") }
            .map { file ->
                val canonicalFile = file.canonicalFile
                require(canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
                    "采集文件越界：${file.name}"
                }
                val relativePath = file.relativeTo(runDirectory).invariantSeparatorsPath
                require(isSafeRelativePath(relativePath)) { "采集文件路径非法：$relativePath" }
                FileRecord(
                    path = relativePath,
                    sizeBytes = file.length(),
                    sha256 = sha256(file),
                    category = categoryOf(relativePath)
                )
            }
            .sortedBy { it.path }
            .toList()
        val snapshot = Snapshot(
            runId = runId,
            generatedAtEpochMs = generatedAtEpochMs,
            warnings = warnings.distinct().sorted(),
            files = records,
            manifestSizeBytes = 0L,
            manifestSha256 = ""
        )
        val manifest = File(runDirectory, MANIFEST_NAME)
        val temporary = File(runDirectory, "$MANIFEST_NAME.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(toJson(snapshot).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (manifest.exists() && !manifest.delete()) {
            temporary.delete()
            throw IllegalStateException("无法替换归档完整性清单")
        }
        if (!temporary.renameTo(manifest)) {
            temporary.delete()
            throw IllegalStateException("无法写入归档完整性清单")
        }
        return snapshot.copy(
            manifestSizeBytes = manifest.length(),
            manifestSha256 = sha256(manifest)
        )
    }

    fun verifyDirectory(runDirectory: File, snapshot: Snapshot): Verification {
        val errors = ArrayList<String>()
        val expected = snapshot.files.associateBy { it.path }
        val actual = runDirectory.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.relativeTo(runDirectory).invariantSeparatorsPath == MANIFEST_NAME }
            .associateBy { it.relativeTo(runDirectory).invariantSeparatorsPath }
        if (actual.keys != expected.keys) {
            (expected.keys - actual.keys).sorted().forEach { errors += "缺少文件：$it" }
            (actual.keys - expected.keys).sorted().forEach { errors += "未登记文件：$it" }
        }
        expected.values.forEach { record ->
            val file = actual[record.path] ?: return@forEach
            if (file.length() != record.sizeBytes) errors += "文件长度不一致：${record.path}"
            if (!runCatching { sha256(file) == record.sha256 }.getOrDefault(false)) {
                errors += "SHA-256 不一致：${record.path}"
            }
        }
        val manifest = File(runDirectory, MANIFEST_NAME)
        if (!manifest.isFile) {
            errors += "缺少归档完整性清单"
        } else {
            if (manifest.length() != snapshot.manifestSizeBytes) errors += "归档完整性清单长度不一致"
            if (!runCatching { sha256(manifest) == snapshot.manifestSha256 }.getOrDefault(false)) {
                errors += "归档完整性清单 SHA-256 不一致"
            }
        }
        return Verification(errors.isEmpty(), expected.size + 1, errors)
    }

    /** Verifies a previously published ZIP using the inventory embedded inside that ZIP. */
    fun verifyZipAgainstEmbeddedManifest(zipFile: File): Verification {
        if (!zipFile.isFile) return Verification(false, 0, listOf("ZIP 文件不存在"))
        return runCatching {
            ZipFile(zipFile).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_NAME)
                    ?: return Verification(false, 0, listOf("ZIP 缺少归档完整性清单"))
                val manifestText = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val declaredCount = Regex("""\"fileCount\"\s*:\s*(\d+)""")
                    .find(manifestText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return Verification(false, 0, listOf("归档清单缺少有效 fileCount"))
                val filesStart = manifestText.indexOf("\"files\"")
                if (filesStart < 0) return Verification(false, 0, listOf("归档清单缺少 files 列表"))
                val filesSection = manifestText.substring(filesStart)
                val recordPattern = Regex(
                    """\{\s*\"path\"\s*:\s*\"((?:\\.|[^\"\\])*)\"\s*,\s*\"sizeBytes\"\s*:\s*(\d+)\s*,\s*\"sha256\"\s*:\s*\"([0-9a-fA-F]{64})\"\s*,\s*\"category\"\s*:\s*\"((?:\\.|[^\"\\])*)\"\s*\}"""
                )
                val records = recordPattern.findAll(filesSection).map { match ->
                    FileRecord(
                        path = unescapeJson(match.groupValues[1]),
                        sizeBytes = match.groupValues[2].toLong(),
                        sha256 = match.groupValues[3].lowercase(),
                        category = unescapeJson(match.groupValues[4])
                    )
                }.toList()
                if (records.size != declaredCount) {
                    return Verification(false, records.size, listOf("归档清单 fileCount 与文件记录数量不一致"))
                }
                val manifestDigest = MessageDigest.getInstance("SHA-256")
                var manifestBytes = 0L
                zip.getInputStream(manifestEntry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        manifestDigest.update(buffer, 0, read)
                        manifestBytes += read
                    }
                }
                val snapshot = Snapshot(
                    runId = "embedded-manifest",
                    generatedAtEpochMs = 0L,
                    warnings = emptyList(),
                    files = records,
                    manifestSizeBytes = manifestBytes,
                    manifestSha256 = manifestDigest.digest().joinToString("") { "%02x".format(it) }
                )
                verifyZip(zipFile, snapshot)
            }
        }.getOrElse { error ->
            Verification(false, 0, listOf("无法解析 ZIP 内归档清单：${error.javaClass.simpleName}"))
        }
    }

    private fun unescapeJson(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch != '\\' || index >= value.length) {
                append(ch)
                continue
            }
            when (val escaped = value[index++]) {
                '"' -> append('"')
                '\\' -> append('\\')
                '/' -> append('/')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'u' -> {
                    if (index + 4 <= value.length) {
                        append(value.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    } else append('u')
                }
                else -> append(escaped)
            }
        }
    }

    fun verifyZip(zipFile: File, snapshot: Snapshot): Verification {
        val errors = ArrayList<String>()
        if (!zipFile.isFile) return Verification(false, 0, listOf("ZIP 文件不存在"))
        runCatching {
            ZipFile(zipFile).use { zip ->
                val actualNames = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .toSet()
                val expectedNames = snapshot.files.map { it.path }.toSet() + MANIFEST_NAME
                if (actualNames != expectedNames) {
                    (expectedNames - actualNames).sorted().forEach { errors += "ZIP 缺少文件：$it" }
                    (actualNames - expectedNames).sorted().forEach { errors += "ZIP 存在未登记文件：$it" }
                }
                snapshot.files.forEach { record ->
                    val entry = zip.getEntry(record.path) ?: return@forEach
                    val digest = MessageDigest.getInstance("SHA-256")
                    var byteCount = 0L
                    zip.getInputStream(entry).use { raw ->
                        BufferedInputStream(raw).use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                                byteCount += read
                            }
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (byteCount != record.sizeBytes) errors += "ZIP 文件长度不一致：${record.path}"
                    if (actualHash != record.sha256) errors += "ZIP 文件 SHA-256 不一致：${record.path}"
                }
                val manifestEntry = zip.getEntry(MANIFEST_NAME)
                if (manifestEntry == null || manifestEntry.size <= 0L) {
                    errors += "ZIP 归档完整性清单缺失或为空"
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var byteCount = 0L
                    zip.getInputStream(manifestEntry).use { raw ->
                        BufferedInputStream(raw).use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                                byteCount += read
                            }
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (byteCount != snapshot.manifestSizeBytes) errors += "ZIP 归档完整性清单长度不一致"
                    if (actualHash != snapshot.manifestSha256) errors += "ZIP 归档完整性清单 SHA-256 不一致"
                }
            }
        }.onFailure { error ->
            errors += "ZIP 无法读取：${error.javaClass.simpleName}"
        }
        return Verification(errors.isEmpty(), snapshot.files.size, errors)
    }

    private fun toJson(snapshot: Snapshot): String {
        val categories = listOf(
            "runtime_logs", "accessibility_events", "node_search", "node_trees",
            "chat_samples", "screenshots", "metadata", "other"
        )
        val categoryJson = categories.joinToString(",") { category ->
            val count = snapshot.files.count { it.category == category }
            "${quote(category)}:{\"status\":${quote(if (count > 0) "present" else "not_captured")},\"fileCount\":$count}"
        }
        val filesJson = snapshot.files.joinToString(",") { record ->
            "{" +
                "\"path\":${quote(record.path)}," +
                "\"sizeBytes\":${record.sizeBytes}," +
                "\"sha256\":${quote(record.sha256)}," +
                "\"category\":${quote(record.category)}" +
                "}"
        }
        val warningsJson = snapshot.warnings.joinToString(",") { quote(it) }
        val status = if (snapshot.warnings.isEmpty()) "HASH_INVENTORY_CREATED" else "HASH_INVENTORY_CREATED_WITH_CAPTURE_WARNINGS"
        return buildString {
            append("{\n")
            append("  \"schemaVersion\":1,\n")
            append("  \"archivePurpose\":\"complete_ai_session_observation\",\n")
            append("  \"runId\":${quote(snapshot.runId)},\n")
            append("  \"generatedAtEpochMs\":${snapshot.generatedAtEpochMs},\n")
            append("  \"integrityStatus\":${quote(status)},\n")
            append("  \"manifestHashPolicy\":\"self_excluded_from_file_hash_list\",\n")
            append("  \"fileCount\":${snapshot.files.size},\n")
            append("  \"coverage\":{$categoryJson},\n")
            append("  \"captureWarnings\":[$warningsJson],\n")
            append("  \"files\":[$filesJson]\n")
            append("}\n")
        }
    }

    private fun categoryOf(path: String): String = when {
        path == "runtime_logs.txt" || path == "runtime_logs.jsonl" -> "runtime_logs"
        path == "event_timeline.jsonl" -> "accessibility_events"
        path == "node_search.jsonl" -> "node_search"
        path.startsWith("node_trees/") -> "node_trees"
        path == "chat_samples.jsonl" || path == "session_lists.jsonl" ||
            path == "session_navigation.jsonl" || path.startsWith("conversation_message_index") -> "chat_samples"
        path.startsWith("screenshots/") || path.startsWith("message_crops/") || path.endsWith("_screenshots.jsonl") -> "screenshots"
        path == "run.jsonl" || path == "run_manifest.json" || path == "run_recovery.json" -> "metadata"
        else -> "other"
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        val normalized = path.replace('\\', '/')
        return normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
