package com.freedom.features.recovery

/**
 * Durable, page-data-only checkpoint shared by recovery routes.
 *
 * It intentionally excludes Android nodes, coordinates, gestures and screenshots. Feature
 * adapters keep their richer, private cursor data in their own stores and map it to this
 * contract when joining unified recovery.
 */
data class RecoveryCheckpoint(
    val checkpointId: String,
    val taskId: String,
    val routeId: String,
    val runGeneration: Long,
    val executionPhase: String,
    val targetPackage: String,
    val pageType: String,
    val pageFingerprint: String,
    val targetIdentityFingerprint: String?,
    val businessCursor: String?,
    val completedRecordKeys: Set<String>,
    val recoveryReason: String,
    val savedAtEpochMs: Long
) {
    init {
        require(checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(runGeneration >= 0L) { "runGeneration must not be negative" }
        require(executionPhase.isNotBlank()) { "executionPhase must not be blank" }
        require(targetPackage.isNotBlank()) { "targetPackage must not be blank" }
        require(pageType.isNotBlank()) { "pageType must not be blank" }
        require(pageFingerprint.isNotBlank()) { "pageFingerprint must not be blank" }
        require(recoveryReason.isNotBlank()) { "recoveryReason must not be blank" }
        require(savedAtEpochMs >= 0L) { "savedAtEpochMs must not be negative" }
    }
}

/** Port owned by a feature's data adapter; concrete persistence remains outside recovery logic. */
interface RecoveryCheckpointRepository {
    fun load(): RecoveryCheckpoint?
    fun save(checkpoint: RecoveryCheckpoint)
    fun clear()
}

enum class RecoveryCheckpointRestoreSource {
    SERVICE_RECONNECT,
    PROCESS_RESTART,
    /** An active task saved a checkpoint after a fresh page-route change or UNKNOWN. */
    PAGE_OBSERVATION
}

/** Fresh, value-only evidence captured after recovery has begun. */
data class RecoveryVerificationEvidence(
    val packageName: String,
    val pageType: String,
    val pageFingerprint: String,
    val targetIdentityFingerprint: String?,
    val runGeneration: Long,
    val observationGeneration: Long
) {
    init {
        require(observationGeneration >= 0L) { "observationGeneration must not be negative" }
    }
}
