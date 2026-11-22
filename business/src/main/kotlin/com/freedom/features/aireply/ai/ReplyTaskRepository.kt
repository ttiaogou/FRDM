package com.freedom.features.aireply.ai

enum class ReplyTaskStatus {
    REQUESTED,
    MODEL_COMPLETED,
    DRAFT_APPLIED,
    /** 草稿已就绪，正在等待发送阶段。 */
    PENDING_SEND,
    /** 发送尝试已获得准入许可，不得重复执行。 */
    SENDING,
    /** 平台已接受发送动作，但尚未确认消息可见。 */
    SENT_UNCONFIRMED,
    SENT,
    /** 保留旧版状态值，以兼容读取历史任务账本。 */
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
    /** 内存缓冲区所需的身份字段；默认值用于兼容旧调用方和历史账本。 */
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

/** 持久化回复任务状态转移的唯一事实来源。 */
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
        // 只有在派发手势前确认发送控件缺失时，才允许回退 PENDING_SEND 状态。
        ReplyTaskStatus.SENDING to setOf(
            ReplyTaskStatus.PENDING_SEND,
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
            ReplyTaskStatus.FAILED_RETRYABLE
        ),
        ReplyTaskStatus.FAILED_RETRYABLE to setOf(ReplyTaskStatus.REQUESTED),
        ReplyTaskStatus.FAILED_FINAL to emptySet(),
        ReplyTaskStatus.SENT to emptySet(),
        ReplyTaskStatus.COMPLETED to emptySet()
    )

    fun isAllowed(from: ReplyTaskStatus, to: ReplyTaskStatus): Boolean =
        from == to || allowed[from].orEmpty().contains(to)
}
