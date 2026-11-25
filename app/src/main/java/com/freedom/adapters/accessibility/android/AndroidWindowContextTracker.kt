package com.freedom.adapters.accessibility.android

import android.os.SystemClock

internal data class AndroidDialogWindowObservation(
    val className: String,
    val windowId: Int,
    val observedAtUptimeMs: Long
)

/** A real platform event, retained as a recovery candidate only, never an action permit. */
internal data class PlatformActivityWindowEvidence(
    val activityClass: String,
    val windowId: Int,
    val eventGeneration: Long
)

/** Owns the current target-window Activity clue and dialog-window evidence. */
internal class AndroidWindowContextTracker(
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() }
) {
    private var activityClass: String = ""
    private var dialogWindowObservation: AndroidDialogWindowObservation? = null
    private var activityBinding: Pair<Int, Long>? = null
    private var recoveredFromReadOnly = false
    // Retain only the two most recently confirmed distinct platform windows (source/destination).
    // Historical evidence is never returned by activityForWindow until independently revalidated.
    private val platformWindowEvidence = linkedMapOf<Int, PlatformActivityWindowEvidence>()

    /** Last observed class is a diagnostic clue, not authorization for an arbitrary root. */
    @Synchronized
    fun currentActivityClass(): String = activityClass

    /**
     * Platform Activity evidence belongs to its event window, never to another root.
     * Ordinary window churn (IME, overlays, display changes) can advance the generation
     * without replacing that Activity window. Read-only recovered evidence remains exact-generation.
     */
    @Synchronized
    fun activityForWindow(windowId: Int, generation: Long): String {
        val binding = activityBinding ?: return ""
        if (windowId < 0 || binding.first != windowId || generation < binding.second ||
            (recoveredFromReadOnly && generation != binding.second)
        ) return ""
        return activityClass
    }

    /** Only an earlier real event for this exact window can be proposed for read-only recovery. */
    @Synchronized
    fun platformRecoveryCandidate(windowId: Int, generation: Long): PlatformActivityWindowEvidence? {
        if (windowId < 0 || generation < 0L || activityForWindow(windowId, generation).isNotBlank()) return null
        return platformWindowEvidence[windowId]?.takeIf { it.eventGeneration <= generation }
    }

    /** Called only after the existing recovery coordinator has accepted two live-window samples. */
    @Synchronized
    fun restorePlatformActivity(evidence: ActivityReadOnlyEvidence): Boolean {
        val eventGeneration = evidence.platformEventGeneration ?: return false
        if (evidence.activityResumedAtEpochMs != null) return false
        val candidate = platformRecoveryCandidate(evidence.windowId, evidence.windowGeneration) ?: return false
        if (candidate.activityClass != evidence.activityClass || candidate.eventGeneration != eventGeneration) return false
        if (!restoreActivityClass(candidate.activityClass, evidence.windowId, evidence.windowGeneration)) return false
        rememberPlatformWindow(candidate)
        return true
    }

    private fun rememberPlatformWindow(evidence: PlatformActivityWindowEvidence) {
        platformWindowEvidence.remove(evidence.windowId)
        platformWindowEvidence[evidence.windowId] = evidence
        while (platformWindowEvidence.size > 2) {
            platformWindowEvidence.remove(platformWindowEvidence.keys.first())
        }
    }

    /** Interruption must not leave historical candidates or a recovered binding usable. */
    @Synchronized
    fun discardPlatformRecoveryEvidence() {
        platformWindowEvidence.clear()
        invalidateRecoveredActivity()
    }

    /** A recovered Activity is valid only for this exact live window and generation. */
    @Synchronized
    fun restoreActivityClass(value: String, windowId: Int, generation: Long): Boolean {
        if (windowId < 0 || generation < 0L || !isActivityWindowClassName(value) ||
            activityForWindow(windowId, generation).isNotBlank()
        ) return false
        activityClass = value
        activityBinding = windowId to generation
        recoveredFromReadOnly = true
        return true
    }

    /** Never retain a read-only recovered Activity across any observed window transition. */
    @Synchronized
    fun invalidateRecoveredActivity() {
        if (recoveredFromReadOnly) {
            activityClass = ""
            activityBinding = null
            recoveredFromReadOnly = false
            dialogWindowObservation = null
        }
    }

    @Synchronized
    fun clearActivityClass() {
        platformWindowEvidence.clear()
        activityClass = ""
        dialogWindowObservation = null
        activityBinding = null
        recoveredFromReadOnly = false
    }

    /**
     * Retains only the app-specific `dialog.h` window clue. It deliberately does not replace the
     * page Activity clue, because a dialog window is not the underlying profile page. The clue is
     * cleared by the next real Activity transition and otherwise checked against live window IDs.
     */
    @Synchronized
    fun observeWindowStateEvent(className: String, windowId: Int, generation: Long) {
        val isMomoDialogH = className.substringAfterLast('.') == "h" &&
            className.contains(".dialog.", ignoreCase = true)
        if (isMomoDialogH && windowId >= 0) {
            dialogWindowObservation = AndroidDialogWindowObservation(className, windowId, uptimeMillis())
        } else if (isActivityWindowClassName(className)) {
            // An Activity event without a window identity cannot authorize a live root.
            activityClass = className.takeIf { windowId >= 0 && generation >= 0L }.orEmpty()
            activityBinding = if (activityClass.isNotBlank()) windowId to generation else null
            if (activityClass.isNotBlank()) {
                rememberPlatformWindow(PlatformActivityWindowEvidence(activityClass, windowId, generation))
            } else {
                // An unbound Activity event cannot establish which former window is still valid.
                platformWindowEvidence.clear()
            }
            recoveredFromReadOnly = false
            dialogWindowObservation = null
        }
    }

    /**
     * Kept until a real Activity transition clears it. Callers must verify the observed window ID
     * against the current AccessibilityWindows collection before acting; elapsed time alone must
     * not make a still-visible dialog invisible to the safety gate.
     */
    @Synchronized
    fun currentDialogWindowObservation(): AndroidDialogWindowObservation? = dialogWindowObservation
}
