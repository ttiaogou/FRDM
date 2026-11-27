package com.freedom.features.aireply.ports

/** Privacy-preserving durable state for the received-greetings traversal. */
data class MomoSayHiDeferredRowCheckpointRecord(
    val attempts: Int,
    val retryAfterEpochMs: Long,
    val safeTargetValidationFailures: Int = 0,
    /** Diagnostic code only: no contact name, original ID or chat text. */
    val lastDeferralReason: String = "",
    /** SHA-256 of failed row's safe target geometry/structure. */
    val failedSafeTargetEvidenceHash: String = ""
)

data class MomoSayHiTraversalCheckpointRecord(
    val processedRowKeys: Set<String>,
    val processedContactIdHashes: Set<String>,
    val deferredRows: Map<String, MomoSayHiDeferredRowCheckpointRecord>,
    val deferredReturnToTopActive: Boolean,
    val deferredReplayStarted: Boolean,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    /** Ordered, unique SHA-256 row keys; comparison only, never node targets. */
    val lastVisibleRowKeys: List<String> = emptyList(),
    val reanchorToTopPending: Boolean = false
)

/** Port for bounded, expiring traversal checkpoints; Data owns their serialization. */
interface MomoSayHiTraversalCheckpointRepository {
    fun load(nowEpochMs: Long = System.currentTimeMillis()): MomoSayHiTraversalCheckpointRecord?
    fun save(checkpoint: MomoSayHiTraversalCheckpointRecord)
    fun clear()
}
