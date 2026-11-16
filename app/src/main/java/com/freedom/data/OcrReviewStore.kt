package com.freedom.data

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import com.freedom.application.ports.OcrReviewRepository
import com.freedom.application.ports.OcrReviewEntry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local, app-private evidence store for manual OCR review. Each OCR batch keeps one source
 * screenshot and one original/annotated crop plus its recognized text for every attempted row.
 */
class OcrReviewStore(context: Context) : OcrReviewRepository {
    private val appContext = context.applicationContext
    private val rootDirectory = File(
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir,
        "FreedomOcrReview"
    )



    @Synchronized
    override fun beginBatch(sourceScreenshotPng: ByteArray, captureDescription: String): String? {
        val batchId = "ocr_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val directory = File(rootDirectory, batchId)
        return runCatching {
            check(directory.mkdirs() || directory.isDirectory) { "无法创建 OCR 核验批次目录" }
            check(savePng(File(directory, SOURCE_IMAGE_FILE), sourceScreenshotPng)) { "无法保存 OCR 原始截图" }
            val metadata = JSONObject()
                .put("batchId", batchId)
                .put("capturedAtEpochMs", System.currentTimeMillis())
                .put("captureDescription", captureDescription)
            File(directory, BATCH_METADATA_FILE).writeText(metadata.toString(), Charsets.UTF_8)
            batchId
        }.getOrElse {
            runCatching { directory.deleteRecursively() }
            null
        }
    }

    @Synchronized
    override fun saveEntry(
        batchId: String,
        rowIndex: Int,
        originalCropPng: ByteArray?,
        annotatedCropPng: ByteArray?,
        recognizedText: String,
        confidence: Float?,
        lineCount: Int,
        failureReason: String?
    ): Boolean {
        if (!isSafeId(batchId) || rowIndex < 0) return false
        val batchDirectory = File(rootDirectory, batchId)
        if (!batchDirectory.isDirectory || !File(batchDirectory, SOURCE_IMAGE_FILE).isFile) return false
        val entryId = "row_%03d".format(rowIndex)
        val entryDirectory = File(File(batchDirectory, ENTRIES_DIRECTORY), entryId)
        return runCatching {
            check(entryDirectory.mkdirs() || entryDirectory.isDirectory) { "无法创建 OCR 样本目录" }
            val originalFile = originalCropPng?.let { bytes ->
                File(entryDirectory, ORIGINAL_CROP_FILE).takeIf { savePng(it, bytes) }
            }
            val annotatedFile = annotatedCropPng?.let { bytes ->
                File(entryDirectory, ANNOTATED_IMAGE_FILE).takeIf { savePng(it, bytes) }
            }
            val record = JSONObject()
                .put("batchId", batchId)
                .put("entryId", entryId)
                .put("capturedAtEpochMs", System.currentTimeMillis())
                .put("rowIndex", rowIndex)
                .put("recognizedText", recognizedText)
                .put("confidence", confidence ?: JSONObject.NULL)
                .put("lineCount", lineCount)
                .put("failureReason", failureReason ?: JSONObject.NULL)
                .put("reviewStatus", OcrReviewRepository.REVIEW_PENDING)
                .put("correctedText", "")
                .put("originalCropSaved", originalFile != null)
                .put("annotatedImageSaved", annotatedFile != null)
            File(entryDirectory, ENTRY_METADATA_FILE).writeText(record.toString(), Charsets.UTF_8)
            true
        }.getOrElse {
            runCatching { entryDirectory.deleteRecursively() }
            false
        }
    }

    @Synchronized
    override fun listEntries(): List<OcrReviewEntry> {
        if (!rootDirectory.isDirectory) return emptyList()
        return rootDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && isSafeId(it.name) }
            .flatMap { batchDirectory ->
                val source = File(batchDirectory, SOURCE_IMAGE_FILE)
                val entriesDirectory = File(batchDirectory, ENTRIES_DIRECTORY)
                val batchEntries = entriesDirectory.listFiles().orEmpty().mapNotNull { entryDirectory ->
                    if (!entryDirectory.isDirectory || !isSafeId(entryDirectory.name)) return@mapNotNull null
                    runCatching {
                        val json = JSONObject(File(entryDirectory, ENTRY_METADATA_FILE).readText(Charsets.UTF_8))
                        OcrReviewEntry(
                            batchId = batchDirectory.name,
                            entryId = json.optString("entryId", entryDirectory.name),
                            capturedAtEpochMs = json.optLong("capturedAtEpochMs", 0L),
                            rowIndex = json.optInt("rowIndex", -1),
                            recognizedText = json.optString("recognizedText"),
                            confidence = if (json.has("confidence") && !json.isNull("confidence")) {
                                json.optDouble("confidence").toFloat().takeIf { it.isFinite() }
                            } else null,
                            lineCount = json.optInt("lineCount", 0),
                            failureReason = json.optString("failureReason").takeIf { it.isNotBlank() && it != "null" },
                            reviewStatus = json.optString("reviewStatus", OcrReviewRepository.REVIEW_PENDING),
                            correctedText = json.optString("correctedText"),
                            sourceImagePath = source.absolutePath,
                            originalCropPath = File(entryDirectory, ORIGINAL_CROP_FILE)
                                .takeIf { it.isFile }?.absolutePath,
                            annotatedImagePath = File(entryDirectory, ANNOTATED_IMAGE_FILE)
                                .takeIf { it.isFile }?.absolutePath
                        ).takeIf { source.isFile && it.rowIndex >= 0 && it.entryId == entryDirectory.name }
                    }.getOrNull()
                }
                batchEntries
            }
            .sortedByDescending { it.capturedAtEpochMs }
    }

    @Synchronized
    override fun markReviewed(batchId: String, entryId: String, status: String, correctedText: String): Boolean {
        if (!isSafeId(batchId) || !isSafeId(entryId) || status !in REVIEW_STATUSES) return false
        val metadata = File(File(File(rootDirectory, batchId), ENTRIES_DIRECTORY), "$entryId/$ENTRY_METADATA_FILE")
        if (!metadata.isFile) return false
        return runCatching {
            val json = JSONObject(metadata.readText(Charsets.UTF_8))
            json.put("reviewStatus", status)
                .put("correctedText", correctedText.take(MAX_CORRECTED_TEXT_LENGTH))
                .put("reviewedAtEpochMs", System.currentTimeMillis())
            metadata.writeText(json.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    override fun deleteEntry(batchId: String, entryId: String): Boolean {
        if (!isSafeId(batchId) || !isSafeId(entryId)) return false
        val batchDirectory = File(rootDirectory, batchId)
        val entryDirectory = File(File(batchDirectory, ENTRIES_DIRECTORY), entryId)
        if (!entryDirectory.exists()) return false
        val deleted = runCatching { entryDirectory.deleteRecursively() }.getOrDefault(false)
        if (!deleted || entryDirectory.exists()) return false
        val remainingEntries = File(batchDirectory, ENTRIES_DIRECTORY).listFiles().orEmpty()
        if (remainingEntries.none { it.isDirectory }) {
            val batchDeleted = runCatching { batchDirectory.deleteRecursively() }.getOrDefault(false)
            return batchDeleted && !batchDirectory.exists()
        }
        return true
    }

    @Synchronized
    override fun deleteAll(): Boolean {
        if (!rootDirectory.exists()) return true
        val children = rootDirectory.listFiles() ?: return false
        val outcomes = children.map { child ->
            runCatching { child.deleteRecursively() }.getOrDefault(false)
        }
        return outcomes.all { it } && rootDirectory.listFiles().orEmpty().isEmpty()
    }

    private fun savePng(file: File, bytes: ByteArray): Boolean = runCatching {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (file.exists()) check(file.delete()) { "无法替换图片文件" }
        check(temporary.renameTo(file)) { "无法发布图片文件" }
        true
    }.getOrElse {
        runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
        false
    }

    private fun isSafeId(value: String): Boolean =
        value.isNotBlank() && value.length <= 96 && value.matches(Regex("[A-Za-z0-9_-]+"))

    companion object {
        private val REVIEW_STATUSES = setOf(
            OcrReviewRepository.REVIEW_PENDING, OcrReviewRepository.REVIEW_CORRECT,
            OcrReviewRepository.REVIEW_INCORRECT, OcrReviewRepository.REVIEW_UNSURE
        )
        private const val SOURCE_IMAGE_FILE = "source_screenshot.png"
        private const val ENTRIES_DIRECTORY = "entries"
        private const val ORIGINAL_CROP_FILE = "ocr_original_crop.png"
        private const val ANNOTATED_IMAGE_FILE = "ocr_annotated.png"
        private const val ENTRY_METADATA_FILE = "entry.json"
        private const val BATCH_METADATA_FILE = "batch.json"
        private const val MAX_CORRECTED_TEXT_LENGTH = 4_000
    }
}
