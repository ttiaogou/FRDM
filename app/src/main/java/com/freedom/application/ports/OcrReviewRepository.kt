package com.freedom.application.ports

/** Platform-neutral OCR review record exposed to the UI. Image content remains in Data storage. */
data class OcrReviewEntry(
    val batchId: String,
    val entryId: String,
    val capturedAtEpochMs: Long,
    val rowIndex: Int,
    val recognizedText: String,
    val confidence: Float?,
    val lineCount: Int,
    val failureReason: String?,
    val reviewStatus: String,
    val correctedText: String,
    val sourceImagePath: String,
    val originalCropPath: String?,
    val annotatedImagePath: String?
)

/** Local evidence persistence contract consumed by the OCR adapter and review UI. */
interface OcrReviewRepository {
    fun beginBatch(sourceScreenshotPng: ByteArray, captureDescription: String): String?
    fun saveEntry(
        batchId: String,
        rowIndex: Int,
        originalCropPng: ByteArray?,
        annotatedCropPng: ByteArray?,
        recognizedText: String,
        confidence: Float?,
        lineCount: Int,
        failureReason: String? = null
    ): Boolean
    fun listEntries(): List<OcrReviewEntry>
    fun markReviewed(batchId: String, entryId: String, status: String, correctedText: String = ""): Boolean
    fun deleteEntry(batchId: String, entryId: String): Boolean
    fun deleteAll(): Boolean

    companion object {
        const val REVIEW_PENDING = "PENDING"
        const val REVIEW_CORRECT = "CORRECT"
        const val REVIEW_INCORRECT = "INCORRECT"
        const val REVIEW_UNSURE = "UNSURE"
    }
}
