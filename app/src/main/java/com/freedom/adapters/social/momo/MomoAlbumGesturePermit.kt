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
    private val ownsTransaction: () -> Boolean
) {
    fun permits(mode: RunMode, packageName: String, windowId: Int, activity: String): Boolean =
        runCatching {
            mode == RunMode.AI_REPLY && transactionId.isNotBlank() &&
                packageName == "com.immomo.momo" && expectedWindow.packageName == packageName &&
                windowId >= 0 && expectedWindow.windowId == windowId &&
                expectedWindow.activityClass == activity &&
                ownsTransaction() && currentWindow() == expectedWindow
        }.getOrDefault(false)
}
