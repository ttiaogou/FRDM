package com.freedom.adapters.social.momo

/**
 * Owns the navigation checkpoint between the received-greetings entry, its foreground profile
 * card, and the actual received-greetings message list. A list Activity seen before the card's
 * explicit "列表" control is tapped is only an intermediate Activity and must not be scanned.
 */
internal enum class MomoSayHiNavigationPhase {
    IDLE,
    WAITING_FOR_PROFILE_CARD,
    STABILIZING_PROFILE_CARD,
    WAITING_FOR_RECEIVED_LIST,
    STABILIZING_MANUAL_RECEIVED_LIST,
    RECEIVED_LIST_CONFIRMED
}

internal class MomoSayHiNavigationFlowController(
    private val requiredStableSamples: Int = 2,
    private val minimumStableSampleIntervalMs: Long = 250L
) {
    var phase: MomoSayHiNavigationPhase = MomoSayHiNavigationPhase.IDLE
        private set

    private var stableWindowKey: String = ""
    private var stableTargetSignature: String = ""
    private var stableSampleCount: Int = 0
    private var lastStableSampleAtMs: Long = 0L

    init {
        require(requiredStableSamples >= 2)
        require(minimumStableSampleIntervalMs >= 0L)
    }

    @Synchronized
    fun beginEntryNavigation() {
        clearStableEvidence()
        phase = MomoSayHiNavigationPhase.WAITING_FOR_PROFILE_CARD
    }

    /** Returns true only after the same exact card target was observed in spaced snapshots. */
    @Synchronized
    fun observeStableCardTarget(
        windowKey: String,
        targetSignature: String,
        capturedAtMs: Long
    ): Boolean {
        if (phase == MomoSayHiNavigationPhase.WAITING_FOR_PROFILE_CARD) {
            phase = MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD
        }
        if (phase != MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD) return false
        return recordStableSample(windowKey, targetSignature, capturedAtMs)
    }

    /**
     * Moves to the list-wait phase only after a stable card target has been confirmed and its
     * validated tap is about to be dispatched.
     */
    @Synchronized
    fun markCardListTapSubmitted(): Boolean {
        if (phase != MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD ||
            stableSampleCount < requiredStableSamples
        ) return false
        clearStableEvidence()
        phase = MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST
        return true
    }

    @Synchronized
    fun markCardListTapFailed() {
        if (phase == MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST) {
            phase = MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD
        }
        clearStableEvidence()
    }

    /** A confirmed final list requires two stable, complete list-container snapshots. */
    @Synchronized
    fun observeStableReceivedList(
        windowKey: String,
        listSignature: String,
        capturedAtMs: Long
    ): Boolean {
        if (phase == MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED) return true
        if (phase != MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST) return false
        val stable = recordStableSample(windowKey, listSignature, capturedAtMs)
        if (stable) phase = MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED
        return stable
    }

    /**
     * Confirms a list reached through user navigation. Unlike the automated path, this does not
     * require a preceding profile-card/list-button transition; the same complete list evidence
     * and spaced same-window snapshots are still mandatory.
     */
    @Synchronized
    fun observeStableManuallyOpenedReceivedList(
        windowKey: String,
        listSignature: String,
        capturedAtMs: Long
    ): Boolean {
        if (phase == MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED) return true
        if (phase == MomoSayHiNavigationPhase.IDLE) {
            clearStableEvidence()
            phase = MomoSayHiNavigationPhase.STABILIZING_MANUAL_RECEIVED_LIST
        }
        if (phase != MomoSayHiNavigationPhase.STABILIZING_MANUAL_RECEIVED_LIST) return false
        val stable = recordStableSample(windowKey, listSignature, capturedAtMs)
        if (stable) phase = MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED
        return stable
    }

    @Synchronized
    fun resetCardStability() {
        if (phase == MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD) clearStableEvidence()
    }

    @Synchronized
    fun resetListStability() {
        if (phase == MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST) clearStableEvidence()
    }

    @Synchronized
    fun resetManualListStability() {
        if (phase == MomoSayHiNavigationPhase.STABILIZING_MANUAL_RECEIVED_LIST) {
            clearStableEvidence()
            phase = MomoSayHiNavigationPhase.IDLE
        }
    }

    @Synchronized
    fun reset() {
        clearStableEvidence()
        phase = MomoSayHiNavigationPhase.IDLE
    }

    @Synchronized
    fun isWaitingForCard(): Boolean =
        phase == MomoSayHiNavigationPhase.WAITING_FOR_PROFILE_CARD ||
            phase == MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD

    /**
     * The entry tap is asynchronous: until the card Activity arrives, transient Activity values
     * must not be treated as a failed main-chat-list identity check. The caller applies the
     * existing bounded navigation timeout before deciding whether to pause the workflow.
     */
    @Synchronized
    fun shouldAwaitProfileCardActivity(
        currentActivityClass: String,
        profileCardActivityClass: String,
        entryTapPending: Boolean
    ): Boolean =
        entryTapPending &&
            phase == MomoSayHiNavigationPhase.WAITING_FOR_PROFILE_CARD &&
            currentActivityClass != profileCardActivityClass

    @Synchronized
    fun isWaitingForReceivedList(): Boolean =
        phase == MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST

    @Synchronized
    fun isReceivedListConfirmed(): Boolean =
        phase == MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED

    @Synchronized
    fun isManuallyStabilizingReceivedList(): Boolean =
        phase == MomoSayHiNavigationPhase.STABILIZING_MANUAL_RECEIVED_LIST

    /** The final greetings list is inspected by its dedicated listview pipeline, never the
     * generic chat-page root walker, which could race RecyclerView animations during swipes. */
    @Synchronized
    fun shouldUseDedicatedReceivedListInspector(isReceivedListSurface: Boolean): Boolean =
        isReceivedListSurface &&
            (phase == MomoSayHiNavigationPhase.WAITING_FOR_RECEIVED_LIST ||
                phase == MomoSayHiNavigationPhase.STABILIZING_MANUAL_RECEIVED_LIST ||
                phase == MomoSayHiNavigationPhase.RECEIVED_LIST_CONFIRMED)

    private fun recordStableSample(
        windowKey: String,
        signature: String,
        capturedAtMs: Long
    ): Boolean {
        if (windowKey.isBlank() || signature.isBlank() || capturedAtMs <= 0L) {
            clearStableEvidence()
            return false
        }
        if (windowKey != stableWindowKey || signature != stableTargetSignature) {
            stableWindowKey = windowKey
            stableTargetSignature = signature
            stableSampleCount = 1
            lastStableSampleAtMs = capturedAtMs
            return false
        }
        if (capturedAtMs < lastStableSampleAtMs) {
            clearStableEvidence()
            stableWindowKey = windowKey
            stableTargetSignature = signature
            stableSampleCount = 1
            lastStableSampleAtMs = capturedAtMs
            return false
        }
        if (capturedAtMs - lastStableSampleAtMs >= minimumStableSampleIntervalMs) {
            stableSampleCount++
            lastStableSampleAtMs = capturedAtMs
        }
        return stableSampleCount >= requiredStableSamples
    }

    private fun clearStableEvidence() {
        stableWindowKey = ""
        stableTargetSignature = ""
        stableSampleCount = 0
        lastStableSampleAtMs = 0L
    }
}
