package com.freedom.adapters.social.momo

import com.freedom.features.aireply.policy.ConversationFilterSettings

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
    // Filter skips belong only to this scan, never to the durable processed/deferred ledger.
    private val filterSkippedRowKeys = LinkedHashSet<String>()
    private var filterSettings: ConversationFilterSettings? = null
    private val retryCounts = LinkedHashMap<String, Int>()
    private val retryAfterEpochMs = LinkedHashMap<String, Long>()
    private val safeTargetValidationFailures = LinkedHashMap<String, Int>()
    private val lastDeferralReasons = LinkedHashMap<String, String>()
    private val failedSafeTargetEvidenceHashes = LinkedHashMap<String, String>()
    /** Only short-lived read-only validation data; never a cached Android target. */
    private data class RecoveryEvidenceCandidate(
        val evidenceHash: String,
        val windowKey: String,
        val capturedAtEpochMs: Long
    )
    private val recoveryEvidenceCandidates = LinkedHashMap<String, RecoveryEvidenceCandidate>()

    var deferredReturnToTopActive: Boolean = false
        private set
    var deferredReplayStarted: Boolean = false
        private set

    // Durable logical cursor; window identity, geometry and boundary samples are never persisted.
    private var lastVisibleRowKeys: List<String> = emptyList()
    private var resumeViewportPending = false
    var reanchorToTopPending: Boolean = false
        private set
    val isReverseTraversal: Boolean get() = reanchorToTopPending || deferredReturnToTopActive

    enum class ViewportResume { CONTINUE, REANCHOR_TO_TOP, FRESH_SCAN }

    val processedRowCount: Int get() = processedRowKeys.size
    val processedContactCount: Int get() = processedContactIdHashes.size
    val deferredRowCount: Int get() = deferredRowKeys.size
    val hasDeferredRows: Boolean get() = deferredRowKeys.isNotEmpty()
    var hasRestorableCheckpoint: Boolean = false
        private set

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

    /** Restore logical progress only; every resumed viewport must be independently reacquired. */
    fun restore(): RestoreSummary? {
        val checkpoint = checkpointStore.load() ?: run {
            hasRestorableCheckpoint = false
            return null
        }
        hasRestorableCheckpoint = true
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
                if (value.lastDeferralReason.isNotBlank()) lastDeferralReasons[key] = value.lastDeferralReason
                if (value.failedSafeTargetEvidenceHash.matches(Regex("[0-9a-f]{64}"))) {
                    failedSafeTargetEvidenceHashes[key] = value.failedSafeTargetEvidenceHash
                }
            }
        }
        recoveryEvidenceCandidates.clear()
        lastVisibleRowKeys = validViewportKeys(checkpoint.lastVisibleRowKeys)
        reanchorToTopPending = checkpoint.reanchorToTopPending
        // A legacy record without an anchor cannot prove continuity or reverse direction.
        deferredReturnToTopActive = lastVisibleRowKeys.isNotEmpty() && checkpoint.deferredReturnToTopActive
        deferredReplayStarted = lastVisibleRowKeys.isNotEmpty() && checkpoint.deferredReplayStarted
        resumeViewportPending = true
        return RestoreSummary(processedRowKeys.size, deferredRowKeys.size, processedContactIdHashes.size)
    }

    private fun validViewportKeys(keys: List<String>): List<String> =
        keys.takeIf { it.size <= 64 && it.distinct().size == it.size &&
            it.all { key -> key.matches(Regex("[0-9a-f]{64}")) } }.orEmpty()

    /** Reentry invalidates old evidence even when navigation was initiated manually. */
    fun prepareForListReentry() {
        recoveryEvidenceCandidates.clear()
        resumeViewportPending = lastVisibleRowKeys.isNotEmpty() || reanchorToTopPending ||
            processedRowKeys.isNotEmpty() || deferredRowKeys.isNotEmpty()
    }

    /**
     * Suspend business progress without retaining any window-scoped observation.
     * Exhaustion starts a new scan later, but does not erase processed or deferred work.
     */
    fun suspendTraversal(preserveTraversal: Boolean) {
        recoveryEvidenceCandidates.clear()
        if (!preserveTraversal) {
            lastVisibleRowKeys = emptyList()
            reanchorToTopPending = false
            deferredReturnToTopActive = false
            deferredReplayStarted = false
            resetFilterSkips()
        }
        resumeViewportPending = true
        persist()
    }

    /** Reentry must compare fresh ordered row identities, not an old position or coordinate. */
    fun observeVerifiedViewport(rowKeys: List<String>): ViewportResume {
        val fresh = validViewportKeys(rowKeys)
        val previousKeys = lastVisibleRowKeys
        val previousResumePending = resumeViewportPending
        val previousReanchor = reanchorToTopPending
        val previousReverse = deferredReturnToTopActive
        val previousReplay = deferredReplayStarted
        val result = if (!resumeViewportPending) ViewportResume.CONTINUE else {
            resumeViewportPending = false
            when {
                reanchorToTopPending -> ViewportResume.REANCHOR_TO_TOP
                lastVisibleRowKeys.isNotEmpty() && lastVisibleRowKeys == fresh -> ViewportResume.CONTINUE
                lastVisibleRowKeys.isEmpty() && processedRowKeys.isEmpty() && deferredRowKeys.isEmpty() ->
                    ViewportResume.FRESH_SCAN
                else -> {
                    // Missing, reordered or ambiguous anchor: reuse the safe reverse sweep.
                    // Keep all row obligations; no old boundary samples are carried over.
                    reanchorToTopPending = true
                    deferredReturnToTopActive = false
                    deferredReplayStarted = false
                    ViewportResume.REANCHOR_TO_TOP
                }
            }
        }
        if (fresh != lastVisibleRowKeys || result != ViewportResume.CONTINUE) {
            lastVisibleRowKeys = fresh
            try {
                persist()
            } catch (failure: Throwable) {
                lastVisibleRowKeys = previousKeys
                resumeViewportPending = previousResumePending
                reanchorToTopPending = previousReanchor
                deferredReturnToTopActive = previousReverse
                deferredReplayStarted = previousReplay
                throw failure
            }
        }
        return result
    }

    /** Called only by the existing confirmed boundary controller, never by a timer. */
    fun completeTopReanchor(): Boolean {
        if (!reanchorToTopPending) return false
        val previousReverse = deferredReturnToTopActive
        val previousReplay = deferredReplayStarted
        reanchorToTopPending = false
        deferredReturnToTopActive = false
        deferredReplayStarted = false
        try {
            persist()
        } catch (failure: Throwable) {
            reanchorToTopPending = true
            deferredReturnToTopActive = previousReverse
            deferredReplayStarted = previousReplay
            throw failure
        }
        return true
    }

    fun updateFilterSettings(settings: ConversationFilterSettings) {
        if (settings != filterSettings) {
            filterSkippedRowKeys.clear()
            filterSettings = settings
        }
    }

    fun isFilterSkipped(rowKey: String): Boolean =
        rowKey in filterSkippedRowKeys && !isDeferredRow(rowKey)

    /** Never suppress a recovery row. This is intentionally not a checkpoint write. */
    fun markFilterSkipped(rowKey: String): Boolean =
        filterSettings?.hasEnabledConditions == true && !isDeferredRow(rowKey) &&
            filterSkippedRowKeys.add(rowKey)

    fun resetFilterSkips() {
        filterSkippedRowKeys.clear()
        filterSettings = null
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

    val hasExhaustedSafeTargetRows: Boolean get() = deferredRowKeys.any { key ->
        !shouldRetryDeferredRow(key) && key !in processedRowKeys
    }

    enum class SafeEvidenceRecovery {
        NOT_READY, BASELINE_RECORDED, WAIT_SECOND_SNAPSHOT, REARMED
    }

    /**
     * The finite click retry budget remains exhausted for the same failed evidence.
     * A different, newly safe geometry must be independently observed twice in the
     * same window generation before a new finite budget is granted.
     * The adapter must independently verify each snapshot before calling this method.
     */
    fun observeExhaustedSafeTargetEvidence(
        rowKey: String,
        evidenceHash: String,
        windowKey: String,
        capturedAtEpochMs: Long,
        nowEpochMs: Long
    ): SafeEvidenceRecovery {
        if (rowKey !in deferredRowKeys || rowKey in processedRowKeys ||
            shouldRetryDeferredRow(rowKey) ||
            nowEpochMs < (retryAfterEpochMs[rowKey] ?: Long.MAX_VALUE) ||
            !evidenceHash.matches(Regex("[0-9a-f]{64}")) ||
            windowKey.isBlank() || capturedAtEpochMs <= 0L || capturedAtEpochMs > nowEpochMs
        ) return SafeEvidenceRecovery.NOT_READY
        val failed = failedSafeTargetEvidenceHashes[rowKey]
        if (failed == null) {
            // Legacy checkpoint cannot prove the prior failure scene changed.
            failedSafeTargetEvidenceHashes[rowKey] = evidenceHash
            recoveryEvidenceCandidates.remove(rowKey)
            persist(nowEpochMs)
            return SafeEvidenceRecovery.BASELINE_RECORDED
        }
        if (failed == evidenceHash) {
            recoveryEvidenceCandidates.remove(rowKey)
            return SafeEvidenceRecovery.NOT_READY
        }
        val prior = recoveryEvidenceCandidates[rowKey]
        if (prior == null || prior.evidenceHash != evidenceHash ||
            prior.windowKey != windowKey || capturedAtEpochMs <= prior.capturedAtEpochMs
        ) {
            recoveryEvidenceCandidates[rowKey] =
                RecoveryEvidenceCandidate(evidenceHash, windowKey, capturedAtEpochMs)
            return SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT
        }
        safeTargetValidationFailures.remove(rowKey)
        recoveryEvidenceCandidates.remove(rowKey)
        retryAfterEpochMs[rowKey] = nowEpochMs + RECOVERED_EVIDENCE_SETTLE_MS
        persist(nowEpochMs)
        return SafeEvidenceRecovery.REARMED
    }

    fun markProcessedRow(rowKey: String) {
        rememberBounded(processedRowKeys, rowKey)
        persist()
    }

    fun markProcessedContactHash(contactIdHash: String) {
        rememberBounded(processedContactIdHashes, contactIdHash)
        persist()
    }

    /** Records the existing bounded retry schedule and checkpoints it before returning. */
    fun deferRow(
        rowKey: String, reason: String, nowEpochMs: Long,
        failureEvidenceHash: String? = null
    ): DeferralResult {
        rememberBounded(deferredRowKeys, rowKey)
        val attempts = ((retryCounts[rowKey] ?: 0) + 1).coerceAtMost(maxRetryBackoffStage)
        retryCounts[rowKey] = attempts
        lastDeferralReasons[rowKey] = reason.take(100)
        recoveryEvidenceCandidates.remove(rowKey)
        val safeTargetFailure = reason.startsWith(safeTargetFailureReasonPrefix)
        if (safeTargetFailure && failureEvidenceHash?.matches(Regex("[0-9a-f]{64}")) == true) {
            failedSafeTargetEvidenceHashes[rowKey] = failureEvidenceHash
        }
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
        retryAfterEpochMs[rowKey] = nowEpochMs +
            if (safeTargetFailure && !MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(safeFailures))
                EXHAUSTED_EVIDENCE_READ_ONLY_DELAY_MS else delayMs
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
        lastDeferralReasons.remove(rowKey)
        failedSafeTargetEvidenceHashes.remove(rowKey)
        recoveryEvidenceCandidates.remove(rowKey)
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
                    safeTargetValidationFailures = safeTargetValidationFailures[key] ?: 0,
                    lastDeferralReason = lastDeferralReasons[key].orEmpty(),
                    failedSafeTargetEvidenceHash = failedSafeTargetEvidenceHashes[key].orEmpty()
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
                savedAtEpochMs = nowEpochMs,
                lastVisibleRowKeys = lastVisibleRowKeys,
                reanchorToTopPending = reanchorToTopPending
            )
        )
        hasRestorableCheckpoint = true
    }

    /** Clears both in-memory traversal state and its persisted checkpoint after explicit exit. */
    fun clear() {
        resetFilterSkips()
        processedRowKeys.clear()
        processedContactIdHashes.clear()
        deferredRowKeys.clear()
        retryCounts.clear()
        retryAfterEpochMs.clear()
        safeTargetValidationFailures.clear()
        lastDeferralReasons.clear()
        failedSafeTargetEvidenceHashes.clear()
        recoveryEvidenceCandidates.clear()
        deferredReturnToTopActive = false
        deferredReplayStarted = false
        lastVisibleRowKeys = emptyList()
        reanchorToTopPending = false
        resumeViewportPending = false
        hasRestorableCheckpoint = false
        checkpointStore.clear()
    }

    private fun rememberBounded(collection: LinkedHashSet<String>, key: String) {
        collection += key
        while (collection.size > maxRememberedRows) collection.remove(collection.first())
    }

    private companion object {
        const val EXHAUSTED_EVIDENCE_READ_ONLY_DELAY_MS = 30_000L
        const val RECOVERED_EVIDENCE_SETTLE_MS = 1_500L
        const val DEFAULT_MAX_REMEMBERED_ROWS = 600
        const val DEFAULT_MAX_RETRY_BACKOFF_STAGE = 4
        const val DEFAULT_SAFE_TARGET_FAILURE_REASON_PREFIX = "ROW_SAFE_TAP_TARGET_SECOND_VALIDATION_FAILED"
    }
}
