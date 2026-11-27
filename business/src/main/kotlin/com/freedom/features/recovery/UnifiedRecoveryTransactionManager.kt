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
            // A fresh verification-only feed observation also belongs to this manager.
            // Only a reducer-confirmed non-ACTIVE state creates that initial slot.
            beginning || old != null || next != RecoveryTransactionState.ACTIVE -> transactions[key] = TransactionSnapshot(
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

    /**
     * A new fresh fingerprint invalidates an unfinished checkpoint *verification lane*
     * without replacing the owning task's primary UNKNOWN transaction or persisted
     * business checkpoint. The original route verifier must start from zero samples.
     */
    @Synchronized
    fun restartVerificationLane(identity: RecoveryEventIdentity, laneId: String): Boolean {
        if (laneId == PRIMARY_LANE || laneId.isBlank() ||
            identity.runGeneration != currentRunGeneration()
        ) return false
        val key = LaneKey(identity.taskId, laneId)
        val old = transactions[key] ?: return false
        if (old.routeId != identity.routeId ||
            old.runGeneration != identity.runGeneration ||
            old.checkpointId != identity.checkpointId ||
            identity.observationGeneration < old.observationGeneration
        ) return false
        transactions.remove(key)
        onLog("recoveryManager=verification_lane_restarted;taskId=${identity.taskId};" +
            "routeId=${identity.routeId};laneId=$laneId")
        return true
    }

    /**
     * Change only the execution destination of an unresolved checkpoint after the
     * feature has independently identified a fresh page. Never complete recovery
     * or grant ownership here: the new route starts with zero verification samples.
     */
    @Synchronized
    fun rebindPendingRoute(previous: RecoveryEventIdentity, next: RecoveryEventIdentity): Boolean {
        val generation = currentRunGeneration()
        if (previous.runGeneration != generation || next.runGeneration != generation ||
            previous.taskId != next.taskId || previous.checkpointId != next.checkpointId ||
            previous.routeId == next.routeId ||
            next.observationGeneration <= previous.observationGeneration
        ) return false
        val key = LaneKey(previous.taskId, PRIMARY_LANE)
        val old = transactions[key] ?: return false
        if (old.routeId != previous.routeId || old.checkpointId != previous.checkpointId ||
            old.runGeneration != generation ||
            previous.observationGeneration < old.observationGeneration ||
            old.state == RecoveryTransactionState.ACTIVE ||
            old.state == RecoveryTransactionState.STOPPED_BY_USER
        ) return false
        transactions[key] = old.copy(
            routeId = next.routeId,
            observationGeneration = next.observationGeneration,
            state = RecoveryTransactionState.RECOVERING
        )
        onLog("recoveryManager=route_rebound;taskId=${next.taskId};" +
            "oldRouteId=${previous.routeId};routeId=${next.routeId};checkpointId=${next.checkpointId}")
        return true
    }

    /** Called after the original route verifier and UI lease transfer both succeed. */
    @Synchronized
    fun completeVerified(identity: RecoveryEventIdentity): Boolean {
        if (identity.runGeneration != currentRunGeneration()) return false
        val primary = transactions[LaneKey(identity.taskId, PRIMARY_LANE)]
        if (primary != null && (primary.routeId != identity.routeId ||
                primary.checkpointId != identity.checkpointId ||
                identity.observationGeneration < primary.observationGeneration)
        ) return false
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
