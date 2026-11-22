package com.freedom.features.aireply.ai

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** State of one model-level message segment. Input 2-3 grapheme chunks are not segment tasks. */
enum class ReplySegmentTaskStatus {
    CREATED,
    DRAFT_APPLIED,
    PENDING_SEND,
    SENDING,
    SENT_UNCONFIRMED,
    SENT,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    UNKNOWN
}

data class ReplySegmentTaskRecord(
    val taskId: String,
    val requestId: String,
    val conversationKey: String,
    val segmentIndex: Int,
    val segmentCount: Int,
    val segmentContentHash: String,
    val segmentContent: String = "",
    val status: ReplySegmentTaskStatus,
    val createdAt: Long,
    val sentAt: Long? = null,
    val confirmationId: String? = null
) {
    init {
        require(taskId.isNotBlank())
        require(requestId.isNotBlank())
        require(conversationKey.isNotBlank())
        require(segmentIndex in 0 until segmentCount)
        require(segmentContentHash.isNotBlank())
    }
}

interface ReplySegmentTaskRepository {
    fun find(taskId: String): ReplySegmentTaskRecord?
    fun findByRequest(requestId: String): List<ReplySegmentTaskRecord>
    fun findByConversation(conversationKey: String): List<ReplySegmentTaskRecord>
    fun save(record: ReplySegmentTaskRecord)
    fun update(record: ReplySegmentTaskRecord): Boolean
}

object ReplySegmentTaskTransitions {
    private val allowed = mapOf(
        ReplySegmentTaskStatus.CREATED to setOf(ReplySegmentTaskStatus.DRAFT_APPLIED, ReplySegmentTaskStatus.FAILED_FINAL),
        ReplySegmentTaskStatus.DRAFT_APPLIED to setOf(ReplySegmentTaskStatus.PENDING_SEND, ReplySegmentTaskStatus.FAILED_RETRYABLE, ReplySegmentTaskStatus.FAILED_FINAL),
        ReplySegmentTaskStatus.PENDING_SEND to setOf(ReplySegmentTaskStatus.SENDING, ReplySegmentTaskStatus.FAILED_RETRYABLE, ReplySegmentTaskStatus.FAILED_FINAL),
        // Return to PENDING_SEND only after the adapter explicitly confirms NO gesture was submitted.
        ReplySegmentTaskStatus.SENDING to setOf(ReplySegmentTaskStatus.PENDING_SEND, ReplySegmentTaskStatus.SENT_UNCONFIRMED, ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.UNKNOWN, ReplySegmentTaskStatus.FAILED_RETRYABLE, ReplySegmentTaskStatus.FAILED_FINAL),
        ReplySegmentTaskStatus.SENT_UNCONFIRMED to setOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.UNKNOWN, ReplySegmentTaskStatus.FAILED_FINAL),
        ReplySegmentTaskStatus.UNKNOWN to setOf(ReplySegmentTaskStatus.DRAFT_APPLIED, ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.FAILED_RETRYABLE),
        ReplySegmentTaskStatus.FAILED_RETRYABLE to setOf(ReplySegmentTaskStatus.CREATED),
        ReplySegmentTaskStatus.FAILED_FINAL to emptySet(),
        ReplySegmentTaskStatus.SENT to emptySet()
    )

    fun isAllowed(from: ReplySegmentTaskStatus, to: ReplySegmentTaskStatus): Boolean =
        from == to || allowed[from].orEmpty().contains(to)
}

object ReplySegmentTaskFactory {
    fun create(
        requestId: String,
        conversationKey: String,
        messages: List<String>,
        createdAtMillis: Long
    ): List<ReplySegmentTaskRecord> {
        require(requestId.isNotBlank())
        require(conversationKey.isNotBlank())
        val segments = messages.map(String::trim).filter(String::isNotEmpty)
        val count = segments.size
        require(count > 0)
        return segments.mapIndexed { index, content ->
            ReplySegmentTaskRecord(
                taskId = "${requestId}:segment:$index",
                requestId = requestId,
                conversationKey = conversationKey,
                segmentIndex = index,
                segmentCount = count,
                segmentContentHash = sha256(content),
                segmentContent = content,
                status = ReplySegmentTaskStatus.CREATED,
                createdAt = createdAtMillis
            )
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
