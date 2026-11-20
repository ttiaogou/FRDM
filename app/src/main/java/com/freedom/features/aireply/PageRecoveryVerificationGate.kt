package com.freedom.features.aireply

/** Requires two equal fresh structural observations before recovery is released. */
internal class PageRecoveryVerificationGate(private val requiredStableSamples: Int = 2) {
    init { require(requiredStableSamples >= 2) }
    private data class RouteState(
        var previousKey: String? = null,
        var stableSamples: Int = 0
    )

    private val routeStates = LinkedHashMap<String, RouteState>()

    /** Compatibility view for legacy callers that used one implicit route. */
    var stableSamples: Int
        get() = stableSamples(DEFAULT_ROUTE_ID)
        private set(value) {
            stateFor(DEFAULT_ROUTE_ID).stableSamples = value
        }

    /** Observes an independent stability sequence for the supplied route. */
    fun observe(routeId: String, structuralKey: String): Boolean {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        val state = stateFor(routeId)
        if (structuralKey.isBlank()) {
            reset(routeId)
            return false
        }
        state.stableSamples = if (structuralKey == state.previousKey) {
            state.stableSamples + 1
        } else {
            1
        }
        state.previousKey = structuralKey
        return state.stableSamples >= requiredStableSamples
    }

    /** Legacy single-route entry point retained while callers migrate to route IDs. */
    fun observe(structuralKey: String): Boolean = observe(DEFAULT_ROUTE_ID, structuralKey)

    fun stableSamples(routeId: String): Int = stateFor(routeId).stableSamples

    fun reset(routeId: String) {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        routeStates.remove(routeId)
    }

    fun reset() = routeStates.clear()

    private fun stateFor(routeId: String): RouteState =
        routeStates.getOrPut(routeId) { RouteState() }

    private companion object {
        const val DEFAULT_ROUTE_ID = "__default__"
    }
}
