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
    val savedAtEpochMs: Long = System.currentTimeMillis()
)

interface PrivateMessagesTraversalCheckpointRepository {
    fun load(nowEpochMs: Long = System.currentTimeMillis()): PrivateMessagesTraversalCheckpoint?
    fun save(checkpoint: PrivateMessagesTraversalCheckpoint)
    fun clear()
}
