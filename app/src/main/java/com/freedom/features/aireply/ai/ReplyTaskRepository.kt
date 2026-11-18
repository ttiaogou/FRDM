package com.freedom.features.aireply.ai

enum class ReplyTaskStatus {
    REQUESTED,
    MODEL_COMPLETED,
    DRAFT_APPLIED,
    /** Draft is ready and is waiting for the sending phase. */
    PENDING_SEND,
    /** A send attempt has been admitted and must not be duplicated. */
    SENDING,
    /** The platform accepted the action, but message visibility is not confirmed yet. */
    SENT_UNCONFIRMED,
    SENT,
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
    val updatedAtMillis: Long,
    /** Required memory-buffer identity fields; defaults preserve old call sites and ledgers. */
    val userId: String = "",
    val contentHash: String = "",
    val createdAtMillis: Long = updatedAtMillis,
    val cooldownUntilMillis: Long = 0L,
    val resultCode: String = ""
)

interface ReplyTaskRepository {
    fun find(requestKey: String): ReplyTaskRecord?
    fun save(record: ReplyTaskRecord)
    fun tryClaim(record: ReplyTaskRecord, requestTimeoutMillis: Long): Boolean
    fun updateStatus(requestKey: String, requestId: String, status: ReplyTaskStatus, updatedAtMillis: Long): Boolean
}

/** Single source of truth for durable reply-task state transitions. */
object ReplyTaskStatusTransitions {
    private val allowed = mapOf(
        ReplyTaskStatus.REQUESTED to setOf(
            ReplyTaskStatus.MODEL_COMPLETED,
            ReplyTaskStatus.UNKNOWN,
            ReplyTaskStatus.FAILED_RETRYABLE,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.MODEL_COMPLETED to setOf(
            ReplyTaskStatus.DRAFT_APPLIED,
            ReplyTaskStatus.UNKNOWN,
            ReplyTaskStatus.FAILED_RETRYABLE,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.DRAFT_APPLIED to setOf(
            ReplyTaskStatus.PENDING_SEND,
            ReplyTaskStatus.FAILED_RETRYABLE,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.PENDING_SEND to setOf(
            ReplyTaskStatus.SENDING,
            ReplyTaskStatus.FAILED_RETRYABLE,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.SENDING to setOf(
            ReplyTaskStatus.SENT_UNCONFIRMED,
            ReplyTaskStatus.SENT,
            ReplyTaskStatus.UNKNOWN,
            ReplyTaskStatus.FAILED_RETRYABLE,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.SENT_UNCONFIRMED to setOf(
            ReplyTaskStatus.SENT,
            ReplyTaskStatus.UNKNOWN,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.UNKNOWN to setOf(
            ReplyTaskStatus.SENT,
            ReplyTaskStatus.FAILED_FINAL
        ),
        ReplyTaskStatus.FAILED_RETRYABLE to setOf(ReplyTaskStatus.REQUESTED),
        ReplyTaskStatus.FAILED_FINAL to emptySet(),
        ReplyTaskStatus.SENT to emptySet(),
        ReplyTaskStatus.COMPLETED to emptySet()
    )

    fun isAllowed(from: ReplyTaskStatus, to: ReplyTaskStatus): Boolean =
        from == to || allowed[from].orEmpty().contains(to)
}
