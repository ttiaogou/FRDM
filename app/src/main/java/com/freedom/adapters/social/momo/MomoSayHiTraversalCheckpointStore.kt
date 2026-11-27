package com.freedom.adapters.social.momo

import com.freedom.features.aireply.ports.MomoSayHiDeferredRowCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository

/** Adapter-facing checkpoint API; Android persistence details live in the Data layer. */
internal class MomoSayHiTraversalCheckpointStore(
    private val repository: MomoSayHiTraversalCheckpointRepository
) {

    @Synchronized
    fun load(nowEpochMs: Long = System.currentTimeMillis()): MomoSayHiTraversalCheckpoint? =
        repository.load(nowEpochMs)?.let { record ->
            MomoSayHiTraversalCheckpoint(
                processedRowKeys = record.processedRowKeys,
                processedContactIdHashes = record.processedContactIdHashes,
                deferredRows = record.deferredRows.mapValues { (_, value) ->
                    MomoSayHiDeferredRowCheckpoint(
                        attempts = value.attempts,
                        retryAfterEpochMs = value.retryAfterEpochMs,
                        safeTargetValidationFailures = value.safeTargetValidationFailures,
                        lastDeferralReason = value.lastDeferralReason,
                        failedSafeTargetEvidenceHash = value.failedSafeTargetEvidenceHash
                    )
                },
                deferredReturnToTopActive = record.deferredReturnToTopActive,
                deferredReplayStarted = record.deferredReplayStarted,
                savedAtEpochMs = record.savedAtEpochMs,
                lastVisibleRowKeys = record.lastVisibleRowKeys,
                reanchorToTopPending = record.reanchorToTopPending
            )
        }

    @Synchronized
    fun save(checkpoint: MomoSayHiTraversalCheckpoint) {
        repository.save(
            MomoSayHiTraversalCheckpointRecord(
                processedRowKeys = checkpoint.processedRowKeys,
                processedContactIdHashes = checkpoint.processedContactIdHashes,
                deferredRows = checkpoint.deferredRows.mapValues { (_, value) ->
                    MomoSayHiDeferredRowCheckpointRecord(
                        attempts = value.attempts,
                        retryAfterEpochMs = value.retryAfterEpochMs,
                        safeTargetValidationFailures = value.safeTargetValidationFailures,
                        lastDeferralReason = value.lastDeferralReason,
                        failedSafeTargetEvidenceHash = value.failedSafeTargetEvidenceHash
                    )
                },
                deferredReturnToTopActive = checkpoint.deferredReturnToTopActive,
                deferredReplayStarted = checkpoint.deferredReplayStarted,
                lastVisibleRowKeys = checkpoint.lastVisibleRowKeys,
                reanchorToTopPending = checkpoint.reanchorToTopPending
            )
        )
    }

    @Synchronized
    fun clear() = repository.clear()
}

internal data class MomoSayHiTraversalCheckpoint(
    val processedRowKeys: Set<String>,
    val processedContactIdHashes: Set<String>,
    val deferredRows: Map<String, MomoSayHiDeferredRowCheckpoint>,
    val deferredReturnToTopActive: Boolean,
    val deferredReplayStarted: Boolean,
    val savedAtEpochMs: Long,
    val lastVisibleRowKeys: List<String> = emptyList(),
    val reanchorToTopPending: Boolean = false
)

internal data class MomoSayHiDeferredRowCheckpoint(
    val attempts: Int,
    val retryAfterEpochMs: Long,
    val safeTargetValidationFailures: Int = 0,
    val lastDeferralReason: String = "",
    val failedSafeTargetEvidenceHash: String = ""
)


/** Retry budget for failures that specifically invalidate the row's safe click region. */
internal object MomoSayHiSafeTargetValidationRetryPolicy {
    const val MAX_FAILURES = 3

    fun recordFailure(previousFailures: Int): Int =
        (previousFailures + 1).coerceAtMost(MAX_FAILURES)

    fun shouldRetry(failureCount: Int): Boolean = failureCount.coerceAtLeast(0) < MAX_FAILURES
}
