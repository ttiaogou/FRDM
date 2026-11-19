package com.freedom.features.recovery

/**
 * Single in-memory context for one page-recovery transaction.
 *
 * It is deliberately business-neutral: task and route identifiers are opaque, while adapters
 * remain responsible for the actual checkpoint data and page fingerprint calculation.
 */
class RecoveryContext {
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

    val pending: Boolean
        get() = sourceTaskId != null

    fun request(taskId: String, recoveryReason: String) {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(recoveryReason.isNotBlank()) { "recoveryReason must not be blank" }
        sourceTaskId = taskId
        targetRouteId = null
        reason = recoveryReason
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
        pauseDomain = PauseDomain.NONE
    }

    companion object {
        const val REQUIRED_STABLE_SAMPLES = 2
    }
}
