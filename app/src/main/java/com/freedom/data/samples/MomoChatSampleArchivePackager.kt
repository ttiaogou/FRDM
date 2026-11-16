package com.freedom.data.samples

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Seals a completed sample directory into a manifest-verified ZIP archive. */
internal object MomoChatSampleArchivePackager {
    fun packageVerifiedArchive(
        runDirectory: File,
        finishedAtEpochMs: Long,
        warnings: List<String>
    ): File {
        require(runDirectory.isDirectory) { "采集目录不存在" }
        val snapshot = SessionSampleArchiveIntegrity.writeManifest(
            runDirectory = runDirectory,
            runId = runDirectory.name,
            generatedAtEpochMs = finishedAtEpochMs,
            warnings = warnings
        )
        val directoryVerification = SessionSampleArchiveIntegrity.verifyDirectory(runDirectory, snapshot)
        check(directoryVerification.valid) {
            "采集目录完整性检查失败：${directoryVerification.errors.joinToString("；")}"
        }

        val zip = File(runDirectory.parentFile, "${runDirectory.name}.zip")
        if (zip.exists()) check(zip.delete()) { "无法清理上一次失败的临时 ZIP" }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
            runDirectory.walkTopDown().filter(File::isFile).forEach { file ->
                val entryName = file.relativeTo(runDirectory).invariantSeparatorsPath
                output.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        check(zip.isFile && zip.length() > 0L) { "样本 ZIP 为空" }
        val zipVerification = SessionSampleArchiveIntegrity.verifyZip(zip, snapshot)
        check(zipVerification.valid) {
            "采集 ZIP 完整性检查失败：${zipVerification.errors.joinToString("；")}"
        }
        return zip
    }
}
