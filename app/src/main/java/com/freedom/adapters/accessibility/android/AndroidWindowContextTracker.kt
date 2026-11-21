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
    private var restoredActivityBinding: Pair<Int, Long>? = null

    fun currentActivityClass(): String = activityClass

    fun updateActivityClass(value: String) {
        activityClass = value
        // A new platform window-state event supersedes provisional usage-event proof.
        restoredActivityBinding = null
    }

    /** A recovered Activity is valid only for this exact live window and generation. */
    fun restoreActivityClass(value: String, windowId: Int, generation: Long): Boolean {
        if (activityClass.isNotBlank() || windowId < 0 || generation < 0L ||
            !isActivityWindowClassName(value)
        ) return false
        activityClass = value
        restoredActivityBinding = windowId to generation
        return true
    }

    fun recoveredActivityMatches(windowId: Int, generation: Long): Boolean {
        val binding = restoredActivityBinding ?: return true
        return binding.first == windowId && binding.second == generation
    }

    /** Never retain a usage-derived Activity across any observed window transition. */
    fun invalidateRecoveredActivity() {
        if (restoredActivityBinding != null) {
            activityClass = ""
            restoredActivityBinding = null
            dialogWindowObservation = null
        }
    }

    fun clearActivityClass() {
        activityClass = ""
        dialogWindowObservation = null
        restoredActivityBinding = null
    }

    /**
     * Retains only the app-specific `dialog.h` window clue. It deliberately does not replace the
     * page Activity clue, because a dialog window is not the underlying profile page. The clue is
     * cleared by the next real Activity transition and otherwise checked against live window IDs.
     */
    fun observeWindowStateEvent(className: String, windowId: Int) {
        val isMomoDialogH = className.substringAfterLast('.') == "h" &&
            className.contains(".dialog.", ignoreCase = true)
        if (isMomoDialogH && windowId >= 0) {
            dialogWindowObservation = AndroidDialogWindowObservation(className, windowId, uptimeMillis())
        } else if (isActivityWindowClassName(className)) {
            activityClass = className
            restoredActivityBinding = null
            dialogWindowObservation = null
        }
    }

    /**
     * Kept until a real Activity transition clears it. Callers must verify the observed window ID
     * against the current AccessibilityWindows collection before acting; elapsed time alone must
     * not make a still-visible dialog invisible to the safety gate.
     */
    fun currentDialogWindowObservation(): AndroidDialogWindowObservation? = dialogWindowObservation
}
