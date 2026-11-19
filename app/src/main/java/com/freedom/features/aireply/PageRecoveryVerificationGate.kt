package com.freedom.features.aireply

/** Requires two equal fresh structural observations before recovery is released. */
internal class PageRecoveryVerificationGate(private val requiredStableSamples: Int = 2) {
    init { require(requiredStableSamples >= 2) }
    private var previousKey: String? = null
    var stableSamples: Int = 0
        private set
    fun observe(structuralKey: String): Boolean {
        if (structuralKey.isBlank()) { reset(); return false }
        stableSamples = if (structuralKey == previousKey) stableSamples + 1 else 1
        previousKey = structuralKey
        return stableSamples >= requiredStableSamples
    }
    fun reset() { previousKey = null; stableSamples = 0 }
}
