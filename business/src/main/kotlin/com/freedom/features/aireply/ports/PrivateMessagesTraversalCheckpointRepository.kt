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
    /** Legacy serialized name; records confirmed-top unread admission, not a mandatory gesture. */
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
    /** Legacy serialized name; retained for exceptional same-round incomplete-row relocation. */
    val fullListPollingRoundRequired: Boolean = false,
    /** Opaque, retryable incomplete unread row hints; never equivalent to a blocked identity. */
    val unresolvedIncompleteUnreadKeys: Set<String> = emptySet(),
    /** An interrupted scan must independently prove the physical bottom before continuing. */
    val scanContinuityRecoveryPending: Boolean = false,
    /** Verified detail entries whose business outcome has not yet been established. */
    val unfinishedSessions: List<UnfinishedPrivateSession> = emptyList()
)

/** Opaque recovery hints only: never a node, coordinate, raw contact ID or message. */
data class UnfinishedPrivateSession(
    val sourceKey: String,
    val titleKey: String,
    val verifiedKey: String?,
    val conversationKey: String,
    val openedAtMillis: Long,
    val disposition: UnfinishedPrivateSessionDisposition = UnfinishedPrivateSessionDisposition.INTERRUPTED,
    /** Opaque SHA-256 request key linking this verified visit to its existing reply journal. */
    val replyRequestKey: String? = null
)

/** A submitted send is an outstanding obligation, not a completed visit or a round-wide hold. */
enum class UnfinishedPrivateSessionDisposition {
    INTERRUPTED,
    SEND_DEFERRED_THIS_ROUND,
    SEND_REVISIT_ELIGIBLE
}

interface PrivateMessagesTraversalCheckpointRepository {
    fun load(nowEpochMs: Long = System.currentTimeMillis()): PrivateMessagesTraversalCheckpoint?
    fun save(checkpoint: PrivateMessagesTraversalCheckpoint)
    fun clear()
}
