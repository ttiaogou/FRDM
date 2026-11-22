package com.freedom.features.recovery

/**
 * P6 process-owned recovery transaction admission point.
 *
 * All identified UNKNOWN / recoverable events from AI Reply, Received Greetings and
 * AccountBrowse pass through this manager before their existing route-specific reducer.
 * The manager owns transaction identity and supersession; route-specific verifiers and
 * persisted read-only schedulers keep their existing independent state and timing.
 *
 * It never acquires task/UI leases, inspects Android nodes, or declares a page verified.
 */
class UnifiedRecoveryTransactionManager(
    private val currentRunGeneration: () -> Long,
    private val onLog: (String) -> Unit = {}
) {
    data class TransactionSnapshot(
        val taskId: String,
        val routeId: String,
        val runGeneration: Long,
        val checkpointId: String,
        val observationGeneration: Long,
        val state: RecoveryTransactionState
    )

    data class DispatchResult(val state: RecoveryTransactionState, val accepted: Boolean)

    private data class LaneKey(val taskId: String, val laneId: String)
    private val transactions = LinkedHashMap<LaneKey, TransactionSnapshot>()

    /**
     * Serializes admission and the *actual* state-machine event. A superseded callback
     * cannot mutate the old reducer, even when its Android observation arrives late.
     */
    @Synchronized
    fun dispatch(
        identity: RecoveryEventIdentity,
        event: RecoveryTransactionEvent,
        existingState: RecoveryTransactionState,
        laneId: String = PRIMARY_LANE,
        apply: () -> RecoveryTransactionState
    ): DispatchResult {
        val generation = currentRunGeneration()
        if (identity.runGeneration != generation) {
            onLog("recoveryManager=stale_generation;taskId=${identity.taskId};routeId=${identity.routeId};" +
                "event=${event.javaClass.simpleName};runGeneration=${identity.runGeneration};currentRunGeneration=$generation")
            return DispatchResult(existingState, false)
        }

        require(laneId.isNotBlank()) { "recovery laneId must not be blank" }
        val key = LaneKey(identity.taskId, laneId)
        val old = transactions[key]?.takeIf { it.runGeneration == generation }
        val same = old != null &&
            old.routeId == identity.routeId &&
            old.checkpointId == identity.checkpointId
        val beginning = event is RecoveryTransactionEvent.UnknownPage ||
            event is RecoveryTransactionEvent.CheckpointLoaded
        if (old != null && (!same || identity.observationGeneration < old.observationGeneration)) {
            // No event, including another UNKNOWN, may supersede an unresolved
            // checkpoint within the same run. A new run generation or verified
            // completion is required first.
            onLog("recoveryManager=stale_event;taskId=${identity.taskId};routeId=${identity.routeId};" +
                "checkpointId=${identity.checkpointId};event=${event.javaClass.simpleName}")
            return DispatchResult(existingState, false)
        }

        val next = apply()
        when {
            event is RecoveryTransactionEvent.StoppedByUser ->
                transactions.remove(key)
            event == RecoveryTransactionEvent.ResumeCompleted && next == RecoveryTransactionState.ACTIVE ->
                transactions.remove(key)
            beginning || old != null -> transactions[key] = TransactionSnapshot(
                taskId = identity.taskId,
                routeId = identity.routeId,
                runGeneration = generation,
                checkpointId = identity.checkpointId,
                observationGeneration = identity.observationGeneration,
                state = next
            )
        }
        return DispatchResult(next, true)
    }

    /**
     * An unattributed pre-run observation is diagnostic evidence, not permission
     * to fabricate a task ID or checkpoint. The original fail-closed page gate remains.
     */
    fun onUnboundEvent(event: RecoveryTransactionEvent) {
        if (event is RecoveryTransactionEvent.UnknownPage ||
            event is RecoveryTransactionEvent.RetryScheduled
        ) onLog("recoveryManager=unbound_observation;event=${event.javaClass.simpleName};taskLease=false")
    }

    /** Called after the original route verifier and UI lease transfer both succeed. */
    @Synchronized
    fun completeVerified(identity: RecoveryEventIdentity): Boolean {
        if (identity.runGeneration != currentRunGeneration()) return false
        val keys = transactions.filter { (key, state) ->
            key.taskId == identity.taskId &&
                state.runGeneration == identity.runGeneration &&
                state.checkpointId == identity.checkpointId
        }.keys.toList()
        keys.forEach { transactions.remove(it) }
        return keys.isNotEmpty()
    }

    @Synchronized
    fun snapshot(taskId: String, laneId: String = PRIMARY_LANE): TransactionSnapshot? =
        transactions[LaneKey(taskId, laneId)]?.takeIf {
            it.runGeneration == currentRunGeneration()
        }

    @Synchronized
    fun trackedTaskIds(): Set<String> =
        transactions.values.filter { it.runGeneration == currentRunGeneration() }
            .mapTo(linkedSetOf()) { it.taskId }

    companion object {
        const val PRIMARY_LANE = "primary"
    }
}
