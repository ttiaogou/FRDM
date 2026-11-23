package com.freedom.features.aireply

/** Requires two equal fresh structural observations before recovery is released. */
internal class PageRecoveryVerificationGate(private val requiredStableSamples: Int = 2) {
    init { require(requiredStableSamples >= 2) }
    private data class RouteState(
        var previousKey: String? = null,
        var stableSamples: Int = 0
    )

    private val routeStates = LinkedHashMap<String, RouteState>()

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

    fun stableSamples(routeId: String): Int = stateFor(routeId).stableSamples

    fun reset(routeId: String) {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        routeStates.remove(routeId)
    }

    fun reset() = routeStates.clear()

    private fun stateFor(routeId: String): RouteState =
        routeStates.getOrPut(routeId) { RouteState() }

}
