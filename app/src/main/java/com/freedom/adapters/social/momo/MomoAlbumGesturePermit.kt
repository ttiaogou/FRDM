package com.freedom.adapters.social.momo

import com.freedom.core.runtime.RunMode
import com.freedom.core.accessibility.api.WindowRef

/** Explicit, short-lived capability supplied only by the media transaction caller.
 * The caller must revalidate ownership and window freshness at dispatch.
 */
internal class MomoAlbumGesturePermit(
    private val transactionId: String,
    private val expectedWindow: WindowRef,
    private val currentWindow: () -> WindowRef?,
    private val ownsTransaction: () -> Boolean,
    private val commandId: String = transactionId,
    /** Reacquires the exact action-specific target; no Android node survives a read. */
    private val targetStillCurrent: () -> Boolean = { false },
    /** Only the existing request-bound Freedom directory name action can supply this proof. */
    val directoryChildAction: Boolean = false
) {
    fun confirmsCurrentTarget(): Boolean = runCatching(targetStillCurrent).getOrDefault(false)

    /** No ordinary gesture or read-only readiness probe inherits this request capability. */
    fun matchesCommand(candidate: String?): Boolean =
        commandId.isNotBlank() && candidate == commandId

    fun permits(mode: RunMode, packageName: String, windowId: Int, activity: String): Boolean =
        runCatching {
            mode == RunMode.AI_REPLY && transactionId.isNotBlank() &&
                packageName == "com.immomo.momo" && expectedWindow.packageName == packageName &&
                windowId >= 0 && expectedWindow.windowId == windowId &&
                expectedWindow.activityClass == activity &&
                ownsTransaction() && currentWindow() == expectedWindow
        }.getOrDefault(false)
}
