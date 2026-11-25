package com.freedom.adapters.accessibility.android

import android.os.SystemClock

internal data class AndroidDialogWindowObservation(
    val className: String,
    val windowId: Int,
    val observedAtUptimeMs: Long
)

/** Owns the current target-window Activity clue and dialog-window evidence. */
internal class AndroidWindowContextTracker(
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() }
) {
    private var activityClass: String = ""
    private var dialogWindowObservation: AndroidDialogWindowObservation? = null
    private var activityBinding: Pair<Int, Long>? = null
    private var recoveredFromUsage = false

    /** Last observed class is a diagnostic clue, not authorization for an arbitrary root. */
    @Synchronized
    fun currentActivityClass(): String = activityClass

    /**
     * Platform Activity evidence belongs to its event window, never to another root.
     * Ordinary window churn (IME, overlays, display changes) can advance the generation
     * without replacing that Activity window. Usage-derived evidence remains exact-generation.
     */
    @Synchronized
    fun activityForWindow(windowId: Int, generation: Long): String {
        val binding = activityBinding ?: return ""
        if (windowId < 0 || binding.first != windowId || generation < binding.second ||
            (recoveredFromUsage && generation != binding.second)
        ) return ""
        return activityClass
    }

    /** A recovered Activity is valid only for this exact live window and generation. */
    @Synchronized
    fun restoreActivityClass(value: String, windowId: Int, generation: Long): Boolean {
        if (windowId < 0 || generation < 0L || !isActivityWindowClassName(value) ||
            activityForWindow(windowId, generation).isNotBlank()
        ) return false
        activityClass = value
        activityBinding = windowId to generation
        recoveredFromUsage = true
        return true
    }

    /** Never retain a usage-derived Activity across any observed window transition. */
    @Synchronized
    fun invalidateRecoveredActivity() {
        if (recoveredFromUsage) {
            activityClass = ""
            activityBinding = null
            recoveredFromUsage = false
            dialogWindowObservation = null
        }
    }

    @Synchronized
    fun clearActivityClass() {
        activityClass = ""
        dialogWindowObservation = null
        activityBinding = null
        recoveredFromUsage = false
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
            recoveredFromUsage = false
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
