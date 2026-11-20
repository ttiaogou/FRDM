package com.freedom.features.recovery

/**
 * Single dispatch boundary for recovery events. Events from an older task, route, run,
 * checkpoint, or page observation are ignored and reported instead of changing state.
 */
class RecoveryEventDispatcher(
    private val transaction: UnifiedRecoveryTransactionStateMachine,
    private val onLog: (String) -> Unit = {}
) {
    private var activeIdentity: RecoveryEventIdentity? = null

    fun activate(identity: RecoveryEventIdentity) {
        activeIdentity = identity
        onLog(identity.logFields("recovery_identity", "activated"))
    }

    fun dispatch(
        event: RecoveryTransactionEvent,
        identity: RecoveryEventIdentity? = activeIdentity
    ): RecoveryTransactionState {
        val eventIdentity = identity
        if (eventIdentity == null) {
            onLog("recoveryType=legacy_dispatch; event=${event.javaClass.simpleName}; accepted=true")
            return transaction.dispatch(event)
        }
        val current = activeIdentity
        if (current != null && !isCurrent(current, eventIdentity)) {
            onLog(eventIdentity.logFields("recovery_event", "stale_discarded") +
                "; activeRunGeneration=${current.runGeneration};" +
                " activeObservationGeneration=${current.observationGeneration}")
            return transaction.state
        }
        activeIdentity = eventIdentity
        val before = transaction.state
        val after = transaction.dispatch(RecoveryTransactionEventEnvelope(eventIdentity, event))
        onLog(eventIdentity.logFields("recovery_event", event.javaClass.simpleName) +
            "; stateBefore=$before; stateAfter=$after; accepted=true")
        return after
    }

    fun clear() {
        activeIdentity = null
    }

    fun currentIdentity(): RecoveryEventIdentity? = activeIdentity

    val state: RecoveryTransactionState
        get() = transaction.state

    val stableVerificationSamples: Int
        get() = transaction.stableVerificationSamples

    fun observeFingerprint(fingerprint: String): RecoveryTransactionState =
        transaction.observeFingerprint(fingerprint)

    fun reset() {
        transaction.reset()
        clear()
    }

    private fun isCurrent(active: RecoveryEventIdentity, incoming: RecoveryEventIdentity): Boolean =
        active.taskId == incoming.taskId &&
            active.routeId == incoming.routeId &&
            active.runGeneration == incoming.runGeneration &&
            active.checkpointId == incoming.checkpointId &&
            incoming.observationGeneration >= active.observationGeneration

    private fun RecoveryEventIdentity.logFields(type: String, outcome: String): String =
        "recoveryType=$type; outcome=$outcome; taskId=$taskId; routeId=$routeId;" +
            " runGeneration=$runGeneration; checkpointId=$checkpointId;" +
            " observationGeneration=$observationGeneration; unknownReason=${unknownReason ?: ""}"
}
