package com.freedom.features.recovery

/**
 * Single in-memory context for one page-recovery transaction.
 *
 * It is deliberately business-neutral: task and route identifiers are opaque, while adapters
 * remain responsible for the actual checkpoint data and page fingerprint calculation.
 */
class RecoveryContext {
    /** Lifecycle view used by callers instead of combining several legacy flags. */
    enum class State {
        IDLE,
        REQUESTED,
        CHECKPOINT_SAVED,
        CHECKPOINT_LOADED,
        VERIFYING,
        VERIFIED,
        UI_OWNERSHIP_TRANSFERRED
    }

    enum class PauseDomain {
        NONE,
        BUSINESS_SAFETY,
        RECOVERY_TRANSACTION
    }

    var pauseDomain: PauseDomain = PauseDomain.NONE
        private set
    var sourceTaskId: String? = null
        private set
    var targetRouteId: String? = null
        private set
    var reason: String? = null
        private set
    var checkpointSaved: Boolean = false
        private set
    var checkpointLoaded: Boolean = false
        private set
    var verificationSamples: Int = 0
        private set
    private var lastVerificationKey: String? = null
    var fingerprintVerified: Boolean = false
        private set
    var uiOwnershipTransferred: Boolean = false
        private set
    var runGeneration: Long = 0L
        private set
    var checkpointId: String? = null
        private set
    var observationGeneration: Long = 0L
        private set

    val pending: Boolean
        get() = sourceTaskId != null

    /** Stable route naming for new callers; the old property remains source-compatible. */
    val routeId: String?
        get() = targetRouteId

    val state: State
        get() = when {
            sourceTaskId == null -> State.IDLE
            uiOwnershipTransferred -> State.UI_OWNERSHIP_TRANSFERRED
            fingerprintVerified -> State.VERIFIED
            verificationSamples > 0 -> State.VERIFYING
            checkpointLoaded -> State.CHECKPOINT_LOADED
            checkpointSaved -> State.CHECKPOINT_SAVED
            else -> State.REQUESTED
        }

    /** Immutable view for logging, event creation and tests without exposing mutable ownership. */
    fun snapshot(): Snapshot = Snapshot(
        state = state,
        taskId = sourceTaskId,
        routeId = routeId,
        reason = reason,
        checkpointId = checkpointId,
        runGeneration = runGeneration,
        observationGeneration = observationGeneration,
        checkpointSaved = checkpointSaved,
        checkpointLoaded = checkpointLoaded,
        verificationSamples = verificationSamples,
        fingerprintVerified = fingerprintVerified,
        uiOwnershipTransferred = uiOwnershipTransferred
    )

    /** Checks event identity without changing the context or accepting stale work. */
    fun matches(identity: RecoveryEventIdentity): Boolean =
        sourceTaskId == identity.taskId &&
            targetRouteId == identity.routeId &&
            runGeneration == identity.runGeneration &&
            checkpointId == identity.checkpointId &&
            identity.observationGeneration >= observationGeneration

    fun request(taskId: String, recoveryReason: String) {
        request(taskId, recoveryReason, "UNKNOWN_ROUTE", 0L, "$taskId:$recoveryReason", 0L)
    }

    fun request(
        taskId: String,
        recoveryReason: String,
        routeId: String,
        generation: Long,
        recoveryCheckpointId: String,
        pageObservationGeneration: Long
    ) {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(recoveryReason.isNotBlank()) { "recoveryReason must not be blank" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(recoveryCheckpointId.isNotBlank()) { "checkpointId must not be blank" }
        require(generation >= 0L) { "runGeneration must not be negative" }
        require(pageObservationGeneration >= 0L) { "observationGeneration must not be negative" }
        sourceTaskId = taskId
        targetRouteId = routeId
        reason = recoveryReason
        runGeneration = generation
        checkpointId = recoveryCheckpointId
        observationGeneration = pageObservationGeneration
        checkpointSaved = false
        checkpointLoaded = false
        verificationSamples = 0
        lastVerificationKey = null
        fingerprintVerified = false
        uiOwnershipTransferred = false
        pauseDomain = PauseDomain.RECOVERY_TRANSACTION
    }

    /** Records the coordinator's effective pause domain without changing recovery lifecycle. */
    fun recordPauseState(isPaused: Boolean) {
        pauseDomain = when {
            isPaused && pending -> PauseDomain.RECOVERY_TRANSACTION
            isPaused -> PauseDomain.BUSINESS_SAFETY
            else -> PauseDomain.NONE
        }
    }

    fun markCheckpointSaved() {
        check(pending) { "cannot save checkpoint without a recovery request" }
        checkpointSaved = true
    }

    fun markCheckpointLoaded() {
        check(pending) { "cannot load checkpoint without a recovery request" }
        checkpointLoaded = true
    }

    fun observeTarget(routeId: String, fingerprintKey: String): Boolean {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(fingerprintKey.isNotBlank()) { "fingerprintKey must not be blank" }
        targetRouteId = routeId
        verificationSamples = if (lastVerificationKey == fingerprintKey) {
            verificationSamples + 1
        } else {
            1
        }
        lastVerificationKey = fingerprintKey
        fingerprintVerified = verificationSamples >= REQUIRED_STABLE_SAMPLES
        return fingerprintVerified
    }

    /**
     * Records the shared checkpoint verifier's actual resume proof instead of running a second
     * stable-sample decision. UI ownership is still checked and transferred by the task owner.
     */
    fun acknowledgeVerifiedCheckpoint(
        routeId: String,
        proof: RecoveryCheckpointVerificationResult.ResumeReady
    ) {
        check(pending) { "cannot accept recovery proof without a pending transaction" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(proof.executionPhase.isNotBlank()) { "verified execution phase must not be blank" }
        targetRouteId = routeId
        verificationSamples = REQUIRED_STABLE_SAMPLES
        fingerprintVerified = true
    }

    fun markUiOwnershipTransferred() {
        check(fingerprintVerified) { "UI ownership cannot transfer before fingerprint verification" }
        uiOwnershipTransferred = true
    }

    fun clear() {
        sourceTaskId = null
        targetRouteId = null
        reason = null
        checkpointSaved = false
        checkpointLoaded = false
        verificationSamples = 0
        lastVerificationKey = null
        fingerprintVerified = false
        uiOwnershipTransferred = false
        runGeneration = 0L
        checkpointId = null
        observationGeneration = 0L
        pauseDomain = PauseDomain.NONE
    }

    companion object {
        const val REQUIRED_STABLE_SAMPLES = 2
    }

    data class Snapshot(
        val state: State,
        val taskId: String?,
        val routeId: String?,
        val reason: String?,
        val checkpointId: String?,
        val runGeneration: Long,
        val observationGeneration: Long,
        val checkpointSaved: Boolean,
        val checkpointLoaded: Boolean,
        val verificationSamples: Int,
        val fingerprintVerified: Boolean,
        val uiOwnershipTransferred: Boolean
    )
}
