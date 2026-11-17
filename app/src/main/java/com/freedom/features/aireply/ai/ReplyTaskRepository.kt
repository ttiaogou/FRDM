package com.freedom.features.aireply.ai

enum class ReplyTaskStatus {
    REQUESTED,
    MODEL_COMPLETED,
    DRAFT_APPLIED,
    /** Legacy value retained so old ledgers remain readable. */
    COMPLETED,
    UNKNOWN,
    FAILED_RETRYABLE,
    FAILED_FINAL
}

data class ReplyTaskRecord(
    val requestKey: String,
    val conversationKey: String,
    val logicalMessageId: String,
    val contentRevision: String,
    val status: ReplyTaskStatus,
    val requestId: String,
    val updatedAtMillis: Long
)

interface ReplyTaskRepository {
    fun find(requestKey: String): ReplyTaskRecord?
    fun save(record: ReplyTaskRecord)
    fun tryClaim(record: ReplyTaskRecord, requestTimeoutMillis: Long): Boolean
    fun updateStatus(requestKey: String, requestId: String, status: ReplyTaskStatus, updatedAtMillis: Long): Boolean
}
