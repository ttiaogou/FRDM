package com.freedom.adapters.social.momo

/**
 * Sole owner of the received-greetings traversal ledger: processed identities, deferred rows,
 * bounded retry counters, and its privacy-preserving checkpoint.
 *
 * 本类集中管理收到招呼列表的遍历状态与断点；不持有 Android 对象，也不保存联系人显示名或消息正文。
 * Adapter 负责页面证据和动作编排，本类是遍历状态的唯一写入者。
 */
internal class MomoSayHiTraversalState(
    private val checkpointStore: MomoSayHiTraversalCheckpointStore,
    private val maxRememberedRows: Int = DEFAULT_MAX_REMEMBERED_ROWS,
    private val maxRetryBackoffStage: Int = DEFAULT_MAX_RETRY_BACKOFF_STAGE,
    private val safeTargetFailureReasonPrefix: String = DEFAULT_SAFE_TARGET_FAILURE_REASON_PREFIX
) {
    private val processedRowKeys = LinkedHashSet<String>()
    private val processedContactIdHashes = LinkedHashSet<String>()
    private val deferredRowKeys = LinkedHashSet<String>()
    private val retryCounts = LinkedHashMap<String, Int>()
    private val retryAfterEpochMs = LinkedHashMap<String, Long>()
    private val safeTargetValidationFailures = LinkedHashMap<String, Int>()

    var deferredReturnToTopActive: Boolean = false
        private set
    var deferredReplayStarted: Boolean = false
        private set

    val processedRowCount: Int get() = processedRowKeys.size
    val processedContactCount: Int get() = processedContactIdHashes.size
    val deferredRowCount: Int get() = deferredRowKeys.size
    val hasDeferredRows: Boolean get() = deferredRowKeys.isNotEmpty()

    data class RestoreSummary(
        val processedRows: Int,
        val deferredRows: Int,
        val processedContacts: Int
    )

    data class DeferralResult(
        val delayMs: Long,
        val backoffStage: Int,
        val safeTargetFailures: Int,
        val safeTargetRetryExhausted: Boolean
    )

    /** Restores only hashed/content-derived identities and retry metadata, never list position. */
    fun restore(): RestoreSummary? {
        val checkpoint = checkpointStore.load() ?: return null
        processedRowKeys.addAll(checkpoint.processedRowKeys)
        processedContactIdHashes.addAll(checkpoint.processedContactIdHashes)
        checkpoint.deferredRows.forEach { (key, value) ->
            if (key !in processedRowKeys) {
                deferredRowKeys += key
                retryCounts[key] = value.attempts
                retryAfterEpochMs[key] = value.retryAfterEpochMs
                if (value.safeTargetValidationFailures > 0) {
                    safeTargetValidationFailures[key] = value.safeTargetValidationFailures
                }
            }
        }
        // A platform scroll position is never restored; every run starts a fresh forward pass.
        deferredReturnToTopActive = false
        deferredReplayStarted = false
        return RestoreSummary(processedRowKeys.size, deferredRowKeys.size, processedContactIdHashes.size)
    }

    fun isProcessedRow(rowKey: String): Boolean = rowKey in processedRowKeys

    fun isProcessedContactHash(contactIdHash: String): Boolean =
        contactIdHash in processedContactIdHashes

    fun isDeferredRow(rowKey: String): Boolean = rowKey in deferredRowKeys

    fun shouldRetryDeferredRow(rowKey: String): Boolean =
        MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(
            safeTargetValidationFailures[rowKey] ?: 0
        )

    fun isDeferredRetryDue(rowKey: String, nowEpochMs: Long): Boolean =
        rowKey in deferredRowKeys && rowKey !in processedRowKeys &&
            shouldRetryDeferredRow(rowKey) &&
            nowEpochMs >= (retryAfterEpochMs[rowKey] ?: Long.MAX_VALUE)

    fun hasRetryableDeferredRows(): Boolean = deferredRowKeys.any(::shouldRetryDeferredRow)

    fun markProcessedRow(rowKey: String) {
        rememberBounded(processedRowKeys, rowKey)
        persist()
    }

    fun markProcessedContactHash(contactIdHash: String) {
        rememberBounded(processedContactIdHashes, contactIdHash)
        persist()
    }

    /** Records the existing bounded retry schedule and checkpoints it before returning. */
    fun deferRow(rowKey: String, reason: String, nowEpochMs: Long): DeferralResult {
        rememberBounded(deferredRowKeys, rowKey)
        val attempts = ((retryCounts[rowKey] ?: 0) + 1).coerceAtMost(maxRetryBackoffStage)
        retryCounts[rowKey] = attempts
        val safeTargetFailure = reason.startsWith(safeTargetFailureReasonPrefix)
        val safeFailures = if (safeTargetFailure) {
            MomoSayHiSafeTargetValidationRetryPolicy
                .recordFailure(safeTargetValidationFailures[rowKey] ?: 0)
                .also { safeTargetValidationFailures[rowKey] = it }
        } else {
            safeTargetValidationFailures[rowKey] ?: 0
        }
        val delayMs = when (attempts) {
            1 -> 1_500L
            2 -> 3_000L
            else -> 5_000L
        }
        retryAfterEpochMs[rowKey] = nowEpochMs + delayMs
        persist(nowEpochMs)
        return DeferralResult(
            delayMs = delayMs,
            backoffStage = attempts,
            safeTargetFailures = safeFailures,
            safeTargetRetryExhausted = safeTargetFailure &&
                !MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(safeFailures)
        )
    }

    fun clearDeferral(rowKey: String, nowEpochMs: Long = System.currentTimeMillis()) {
        deferredRowKeys.remove(rowKey)
        retryCounts.remove(rowKey)
        retryAfterEpochMs.remove(rowKey)
        safeTargetValidationFailures.remove(rowKey)
        persist(nowEpochMs)
    }

    fun setDeferredReturnToTopActive(value: Boolean) {
        deferredReturnToTopActive = value
        persist()
    }

    fun setDeferredReplayStarted(value: Boolean) {
        deferredReplayStarted = value
        persist()
    }

    fun persist(nowEpochMs: Long = System.currentTimeMillis()) {
        val deferred = LinkedHashMap<String, MomoSayHiDeferredRowCheckpoint>()
        deferredRowKeys.forEach { key ->
            if (key !in processedRowKeys) {
                deferred[key] = MomoSayHiDeferredRowCheckpoint(
                    attempts = retryCounts[key] ?: 0,
                    retryAfterEpochMs = retryAfterEpochMs[key] ?: 0L,
                    safeTargetValidationFailures = safeTargetValidationFailures[key] ?: 0
                )
            }
        }
        checkpointStore.save(
            MomoSayHiTraversalCheckpoint(
                processedRowKeys = processedRowKeys.toList().takeLast(maxRememberedRows).toSet(),
                processedContactIdHashes = processedContactIdHashes.toList()
                    .takeLast(maxRememberedRows).toSet(),
                deferredRows = deferred,
                deferredReturnToTopActive = deferredReturnToTopActive,
                deferredReplayStarted = deferredReplayStarted,
                savedAtEpochMs = nowEpochMs
            )
        )
    }

    /** Clears both in-memory traversal state and its persisted checkpoint after explicit exit. */
    fun clear() {
        processedRowKeys.clear()
        processedContactIdHashes.clear()
        deferredRowKeys.clear()
        retryCounts.clear()
        retryAfterEpochMs.clear()
        safeTargetValidationFailures.clear()
        deferredReturnToTopActive = false
        deferredReplayStarted = false
        checkpointStore.clear()
    }

    private fun rememberBounded(collection: LinkedHashSet<String>, key: String) {
        collection += key
        while (collection.size > maxRememberedRows) collection.remove(collection.first())
    }

    private companion object {
        const val DEFAULT_MAX_REMEMBERED_ROWS = 600
        const val DEFAULT_MAX_RETRY_BACKOFF_STAGE = 4
        const val DEFAULT_SAFE_TARGET_FAILURE_REASON_PREFIX = "ROW_SAFE_TAP_TARGET_SECOND_VALIDATION_FAILED"
    }
}
