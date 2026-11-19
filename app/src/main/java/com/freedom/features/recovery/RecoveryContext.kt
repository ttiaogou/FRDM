package com.freedom.features.recovery

/**
 * Single in-memory context for one page-recovery transaction.
 *
 * It is deliberately business-neutral: task and route identifiers are opaque, while adapters
 * remain responsible for the actual checkpoint data and page fingerprint calculation.
 */
class RecoveryContext {
    var sourceTaskId: String? = null
        private set
    var targetRouteId: String? = null
        private set
    var reason: String? = null
        private set
    var checkpointSaved: Boolean = false
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
        verificationSamples = 0
        lastVerificationKey = null
        fingerprintVerified = false
        uiOwnershipTransferred = false
    }

    fun markCheckpointSaved() {
        check(pending) { "cannot save checkpoint without a recovery request" }
        checkpointSaved = true
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
        verificationSamples = 0
        lastVerificationKey = null
        fingerprintVerified = false
        uiOwnershipTransferred = false
    }

    companion object {
        const val REQUIRED_STABLE_SAMPLES = 2
    }
}
