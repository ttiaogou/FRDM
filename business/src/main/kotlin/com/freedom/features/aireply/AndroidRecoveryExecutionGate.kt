package com.freedom.features.aireply

import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TargetAppReadinessSnapshot
import com.freedom.core.runtime.TargetAppReadinessStatus
import com.freedom.features.aireply.ports.ChatPageSnapshot

/** Read-only adapter boundary; no gesture or reusable permission crosses into business. */
internal data class AndroidRecoveryAdmission(val allowed: Boolean, val reasonCode: String)

/**
 * A READY status is useful only when observed again for the current page and window.
 * The original checkpoint verifier remains responsible for the two fresh route snapshots.
 */
internal object AndroidRecoveryExecutionGate {
    fun assess(
        snapshot: ChatPageSnapshot,
        expectedPackage: String,
        evidence: TargetAppReadinessSnapshot?
    ): AndroidRecoveryAdmission {
        fun waitFor(reason: String) = AndroidRecoveryAdmission(false, reason)
        if (evidence == null) return waitFor("NO_FRESH_ANDROID_EVIDENCE")
        if (evidence.status != TargetAppReadinessStatus.READY) {
            return waitFor("ANDROID_" + evidence.status.name)
        }
        if (evidence.runMode != RunMode.AI_REPLY) return waitFor("WRONG_RUN_MODE")
        if (expectedPackage.isBlank() || snapshot.packageName != expectedPackage ||
            evidence.targetPackage != expectedPackage || evidence.foregroundPackage != expectedPackage
        ) return waitFor("TARGET_PACKAGE_CHANGED")
        if (evidence.windowId < 0 || evidence.pageGeneration < 0L ||
            evidence.activityClass.isBlank() || snapshot.activityClass.isBlank() ||
            evidence.activityClass != snapshot.activityClass
        ) return waitFor("ACTIVITY_OR_WINDOW_UNCONFIRMED")

        // Greetings-list snapshots intentionally contain no node target; the original
        // Momo adapter fingerprint still confirms that route. Do not invent an anchor.
        val anchor = snapshot.inputFieldTarget?.window
            ?: snapshot.messageListTarget?.window
            ?: snapshot.sessionListNavigationTarget?.nodeTarget?.window
            ?: snapshot.chatListCandidates.firstOrNull()?.rowTarget?.window
        if (anchor != null && (anchor.packageName != expectedPackage ||
                anchor.windowId != evidence.windowId ||
                anchor.generation != evidence.pageGeneration ||
                anchor.activityClass != evidence.activityClass)
        ) return waitFor("ACTIVE_WINDOW_IDENTITY_CHANGED")
        if (snapshot.windowGeneration > 0L &&
            snapshot.windowGeneration != evidence.pageGeneration
        ) return waitFor("WINDOW_GENERATION_CHANGED")
        return AndroidRecoveryAdmission(true, "READY")
    }
}
