package com.freedom.features.aireply.ports

/** Durable, privacy-preserving checkpoint for the ordinary message-list traversal. */
data class PrivateMessagesTraversalCheckpoint(
    val attemptedSessionKeys: Set<String>,
    val lockedUnreadSessionKeys: Set<String>,
    val ambiguousSessionIdentityKeys: Set<String>,
    val lastStableVisibleKeys: List<String>,
    val phase: String,
    val atTop: Boolean,
    val initialInboxPositionCheckCompleted: Boolean,
    val initialInboxTopPositioningCompleted: Boolean,
    val initialUnreadGateSwipeCompleted: Boolean,
    val lockedUnreadSetFinalized: Boolean,
    val normalInboxHadUnread: Boolean,
    val normalInboxUnreadStateUnknown: Boolean,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    /** Best-effort re-anchor identity; never contains node paths or coordinates. */
    val resumeSessionKey: String? = null,
    val resumeSessionName: String? = null,
    val runGeneration: Long = 0L,
    val lastSuccessfulTaskId: String? = null,
    val nextCursorKey: String? = null,
    val traversalDirection: String = "BOTTOM_TO_TOP",
    /** Opaque keys of still-unresolved, previously eligible UNKNOWN private rows. */
    val unresolvedUnknownUnreadKeys: Set<String> = emptySet(),
    /** Identity + geometry of the viewport that originally produced the UNKNOWN block. */
    val unknownUnreadViewportKeys: List<String> = emptyList(),
    /** True only for a timed full-inbox polling round; absent in older checkpoints. */
    val fullListPollingRoundRequired: Boolean = false
)

interface PrivateMessagesTraversalCheckpointRepository {
    fun load(nowEpochMs: Long = System.currentTimeMillis()): PrivateMessagesTraversalCheckpoint?
    fun save(checkpoint: PrivateMessagesTraversalCheckpoint)
    fun clear()
}
