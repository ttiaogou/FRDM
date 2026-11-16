package com.freedom.adapters.accessibility.android

import com.freedom.features.accountbrowse.ports.PopupEvidence
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.NodeTarget

/** Stateless safe-popup action selection; the Feature-owned popup counters remain outside. */
internal class AccountBrowsePopupExecutor {
    sealed interface Decision {
        data object Critical : Decision
        data class Wait(val delayMs: Long) : Decision
        data object NoSafeAction : Decision
        data object AttemptsExhausted : Decision
        data class SystemDismiss(val target: NodeTarget) : Decision
        data class CoordinateTap(
            val preferredBounds: ScreenRect,
            val targetBounds: ScreenRect
        ) : Decision
    }

    fun decide(
        popup: PopupEvidence,
        nowEpochMs: Long,
        nextAttemptAtEpochMs: Long,
        actionAttempts: Int,
        maxAttempts: Int,
        minimumPollMs: Long
    ): Decision {
        if (popup.critical) return Decision.Critical
        if (nowEpochMs < nextAttemptAtEpochMs) {
            return Decision.Wait((nextAttemptAtEpochMs - nowEpochMs).coerceAtLeast(minimumPollMs))
        }
        val dismissTarget = popup.dismissTarget
        val hasSafeDismiss = popup.dismissable && dismissTarget != null
        val closeBounds = popup.closeBounds?.takeUnless { it.isEmpty }
        val closeTargetBounds = popup.closeTargetBounds?.takeUnless { it.isEmpty }
        if (!hasSafeDismiss && (closeBounds == null || closeTargetBounds == null)) {
            return Decision.NoSafeAction
        }
        if (actionAttempts >= maxAttempts) return Decision.AttemptsExhausted
        return if (hasSafeDismiss) {
            Decision.SystemDismiss(dismissTarget!!)
        } else {
            Decision.CoordinateTap(closeBounds!!, closeTargetBounds!!)
        }
    }
}
