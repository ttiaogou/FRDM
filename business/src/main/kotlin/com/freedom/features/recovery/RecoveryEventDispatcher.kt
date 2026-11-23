package com.freedom.features.recovery

/**
 * Single dispatch boundary for recovery events. Events from an older task, route, run,
 * checkpoint, or page observation are ignored and reported instead of changing state.
 */
class RecoveryEventDispatcher(
    private val transaction: UnifiedRecoveryTransactionStateMachine,
    private val onLog: (String) -> Unit = {},
    private val sharedManager: UnifiedRecoveryTransactionManager? = null,
    private val managerLaneId: String = UnifiedRecoveryTransactionManager.PRIMARY_LANE
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
            // P7: a production recovery event without task/checkpoint identity cannot
            // bypass the process manager by mutating the local reducer directly.
            if (sharedManager != null) {
                sharedManager.onUnboundEvent(event)
                onLog("recoveryType=unbound_dispatch; event=${event.javaClass.simpleName}; accepted=false")
                return transaction.state
            }
            // Isolated reducer fixtures retain their original no-manager behavior.
            onLog("recoveryType=isolated_dispatch; event=${event.javaClass.simpleName}; accepted=true")
            return transaction.dispatch(event)
        }
        val current = activeIdentity
        if (current != null && !isCurrent(current, eventIdentity)) {
            onLog(eventIdentity.logFields("recovery_event", "stale_discarded") +
                "; activeRunGeneration=${current.runGeneration};" +
                " activeObservationGeneration=${current.observationGeneration}")
            return transaction.state
        }
        val before = transaction.state
        val envelope = RecoveryTransactionEventEnvelope(eventIdentity, event)
        val result = sharedManager?.dispatch(
            eventIdentity, event, before, managerLaneId
        ) {
            transaction.dispatch(envelope)
        }
        val after = result?.state ?: transaction.dispatch(envelope)
        val accepted = result?.accepted ?: true
        if (accepted) activeIdentity = eventIdentity
        onLog(eventIdentity.logFields("recovery_event", event.javaClass.simpleName) +
            "; stateBefore=$before; stateAfter=$after; accepted=$accepted")
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

    /**
     * The original two-fingerprint reducer remains authoritative, but an identified
     * production observation must cross the same manager gate as explicit events.
     * No raw fingerprint leaves this dispatcher or enters manager diagnostics.
     */
    fun observeFingerprint(fingerprint: String): RecoveryTransactionState {
        val manager = sharedManager ?: return transaction.observeFingerprint(fingerprint)
        val identity = activeIdentity
        if (identity == null) {
            manager.onUnboundEvent(RecoveryTransactionEvent.VerificationSample(stable = false))
            onLog("recoveryType=unbound_fingerprint; accepted=false")
            return transaction.state
        }
        val event = RecoveryTransactionEvent.VerificationSample(stable = fingerprint.isNotBlank())
        val result = manager.dispatch(identity, event, transaction.state, managerLaneId) {
            transaction.observeFingerprint(fingerprint)
        }
        onLog(identity.logFields("recovery_fingerprint", if (result.accepted) "admitted" else "stale_discarded"))
        return result.state
    }

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
