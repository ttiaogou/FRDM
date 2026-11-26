package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.adapters.accessibility.android.AndroidActiveWindowRootAccess
import com.freedom.adapters.accessibility.android.AndroidDialogWindowObservation
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TargetAppPageReadiness

/**
 * Performs the final, read-only Momo page/safety inspection before an autonomous gesture.
 * It owns no workflow state: the current SayHi profile-scroll permit is supplied as a snapshot.
 */
internal class MomoGesturePageReadinessInspector(
    private val rootAccess: AndroidActiveWindowRootAccess,
    private val targetPackage: String,
    private val dp: (Int) -> Int,
    private val onLog: (String) -> Unit,
    private val popupInspector: MomoPopupInspector,
    private val popupSafetyRetryPolicy: MomoPopupSafetyRetryPolicy,
    private val latestDialogWindowObservation: () -> AndroidDialogWindowObservation?,
    private val pageInspector: MomoPageInspector,
    private val chatPageInspector: MomoChatPageInspector,
    private val sayHiAccessibilityInspector: MomoSayHiAccessibilityInspector,
    private val isSayHiProfileScrollPending: (windowId: Int) -> Boolean,
    private val sayHiProfileScrollWindowId: () -> Int
) {
    fun inspect(
        root: AccessibilityNodeInfo,
        currentActivity: String,
        mode: RunMode,
        albumPermit: MomoAlbumGesturePermit? = null
    ): TargetAppPageReadiness {
        val packageName = safeString { root.packageName?.toString().orEmpty() }
        if (packageName != targetPackage) {
            return TargetAppPageReadiness(false, "当前活动窗口包名不匹配：$packageName")
        }

        val rootWindowId = safeInt { root.windowId }
        val safetyWindowKey = "$currentActivity:$rootWindowId"
        val safetyInspection = popupInspector.inspectBlockingOverlay(
            root = root,
            currentActivity = currentActivity
        )
        if (currentActivity == MomoSayHiPageContract.CARD_ACTIVITY) {
            onLog(
                "页面安全检查[PROFILE_CARD_SCOPE]：visited=${safetyInspection.visitedNodes}；" +
                    "popupCandidates=${safetyInspection.popupCandidateCount}；" +
                    "expectedCardDeckCandidatesIgnored=${safetyInspection.expectedCardDeckCandidatesIgnored}；" +
                    "complete=${safetyInspection.complete}；stopReason=${safetyInspection.stopReason ?: "none"}"
            )
        }
        if (!safetyInspection.complete) {
            val attempt = popupSafetyRetryPolicy.onIncomplete(
                safetyWindowKey,
                SystemClock.uptimeMillis()
            )
            val detail =
                "安全快照不完整；stopReason=${safetyInspection.stopReason ?: "UNKNOWN"}；" +
                    "visited=${safetyInspection.visitedNodes}；elapsedMs=${safetyInspection.elapsedMs}；" +
                    "attempt=${attempt.number}/${attempt.maximum}"
            onLog(
                "页面安全检查[RETRY_WAIT]：$detail；累计次数已封顶但不终止任务；" +
                    "保留待执行状态，等待遮挡消失或取得完整新鲜快照；不派发手势"
            )
            return TargetAppPageReadiness(
                ready = false,
                reason = "页面安全检查尚未完整；持续等待新鲜快照重试；$detail",
                retryable = true
            )
        }
        popupSafetyRetryPolicy.onComplete(safetyWindowKey, SystemClock.uptimeMillis())
        val observedDialogWindowId = latestDialogWindowObservation()?.windowId
        if (observedDialogWindowId != null && observedDialogWindowId >= 0 &&
            observedDialogWindowId != rootWindowId &&
            rootAccess.isTargetWindowPresent(observedDialogWindowId) == true
        ) {
            return TargetAppPageReadiness(
                ready = false,
                reason = "独立弹窗 windowId=$observedDialogWindowId 遮挡目标窗口；页面身份可保留，但等待弹窗消失后重新取快照",
                retryable = true
            )
        }
        val blockingOverlay = safetyInspection.blockingReason
        if (!blockingOverlay.isNullOrBlank()) {
            onLog(
                "页面安全检查[OVERLAY_WAIT]：$blockingOverlay；保留当前业务进度，" +
                    "不点击/滚动/OCR/BACK，等待弹层消失后重新识别"
            )
            return TargetAppPageReadiness(
                ready = false,
                reason = "检测到弹层或需要用户处理的窗口；当前操作暂缓但任务保持可恢复：$blockingOverlay",
                blockingReason = blockingOverlay,
                retryable = true
            )
        }
        if (safetyInspection.visitedNodes >= POPUP_SAFETY_SCAN_DIAGNOSTIC_NODE_THRESHOLD ||
            safetyInspection.elapsedMs >= POPUP_SAFETY_SCAN_DIAGNOSTIC_DURATION_MS
        ) {
            onLog(
                "页面安全检查[PASS]：单次有限范围扫描完成；visited=${safetyInspection.visitedNodes}；" +
                    "elapsedMs=${safetyInspection.elapsedMs}；Activity=$currentActivity"
            )
        }

        // Explicit media capability only; never grant generic AI_REPLY album access.
        // All existing popup and independent-window checks above still apply.
        if (albumPermit != null) {
            val permitted = albumPermit.permits(mode, packageName, rootWindowId, currentActivity) &&
                MomoAlbumSelectorInspector.inspect(root, targetPackage) != null
            return TargetAppPageReadiness(
                permitted,
                if (permitted) "AI Reply: verified media transaction album selector"
                else "Media transaction album permission not confirmed",
                retryable = !permitted
            )
        }

        if (mode == RunMode.AI_REPLY && MomoAlbumArrivalInspector.isConfirmed(root, targetPackage)) {
            return TargetAppPageReadiness(false,
                "Media transaction album requires an exact command permit", retryable = true)
        }

        if (mode == RunMode.AI_REPLY && isSayHiProfileScrollPending(rootWindowId)) {
            // The adapter has prepared the swipe from a fresh profile window; require
            // the exact in-flight transaction window again after the popup checks.
            if (!MomoSayHiProfileScrollPolicy.permitsIdentityProfileScroll(
                    mode = mode,
                    pendingOnCurrentWindow = isSayHiProfileScrollPending(rootWindowId),
                    activeWindowId = rootWindowId,
                    expectedWindowId = sayHiProfileScrollWindowId()
                )
            ) return TargetAppPageReadiness(
                ready = false, reason = "资料页上滑暂缓：当前窗口与身份采集事务不一致", retryable = true
            )
            val dialogObservation = latestDialogWindowObservation()
            if (dialogObservation != null) {
                val dialogStillPresent = rootAccess.isTargetWindowPresent(dialogObservation.windowId)
                if (dialogStillPresent == true || dialogStillPresent == null) {
                    onLog(
                        "资料页上滑准入拒绝：dialog.h 窗口仍存在；" +
                            "windowId=${dialogObservation.windowId}；先完成独立弹层安全处理"
                    )
                    return TargetAppPageReadiness(
                        ready = false,
                        reason = "资料页身份上滑期间检测到 dialog.h 窗口；等待安全关闭并重新取快照",
                        retryable = true
                    )
                }
            }
            return TargetAppPageReadiness(
                true,
                "AI Reply：身份采集事务的一次性资料页上滑许可；windowId=${sayHiProfileScrollWindowId()}"
            )
        }

        return when (mode) {
            RunMode.AI_REPLY -> inspectAiReplyPage(root, currentActivity)
            RunMode.ACCOUNT_BROWSE -> inspectAccountBrowsePage(root, currentActivity)
        }
    }

    private fun inspectAiReplyPage(
        root: AccessibilityNodeInfo,
        currentActivity: String
    ): TargetAppPageReadiness {
        if (sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(root, targetPackage)) {
            return TargetAppPageReadiness(true, "AI Reply：收到招呼列表结构指纹已确认（唯一外层列表与招呼标题/行结构）")
        }
        if (currentActivity == MomoSayHiPageContract.CARD_ACTIVITY &&
            hasVisibleViewId(root, setOf(MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, MomoSayHiPageContract.CARD_CLOSE_VIEW_ID))
        ) {
            return TargetAppPageReadiness(true, "AI Reply：收到招呼资料卡的 Activity 与专属内容锚点同时确认")
        }

        val inspection = chatPageInspector.inspectRoot(
            root = AccessibilityNodeInfo.obtain(root),
            targetPackage = targetPackage,
            activityClass = currentActivity
        )
        val ready = inspection.pageType == MomoChatPageType.CHAT_LIST ||
            inspection.pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE
        return TargetAppPageReadiness(
            ready,
            if (ready) inspection.summary() else
                "AI Reply 页面尚未达到可操作条件：${inspection.summary()}"
        )
    }

    private fun inspectAccountBrowsePage(
        root: AccessibilityNodeInfo,
        currentActivity: String
    ): TargetAppPageReadiness {
        val feed = pageInspector.inspectMomoFeedReadiness(root)
        if (feed.fingerprint != null) return TargetAppPageReadiness(true, feed.reason)

        val detail = pageInspector.inspectCurrentDynamicDetailIdentity(
            root = root,
            currentActivity = currentActivity
        )
        if (detail.identityConfirmed) return TargetAppPageReadiness(true, detail.reason)

        if (pageInspector.hasMomoHomePageNavigationFingerprint(root)) {
            val home = pageInspector.inspectHomeBottomNav(root)
            return TargetAppPageReadiness(
                true,
                "动态浏览首页结构已确认；可在顶部动态/附近/AI小陌标签中定位目标入口；${home?.diagnostic.orEmpty()}"
            )
        }
        if (currentActivity == MomoAppDescriptor.FEED_IMAGE_BROWSER_ACTIVITY_CLASS) {
            val hasContent = hasVisibleContent(root)
            return TargetAppPageReadiness(
                hasContent,
                if (hasContent) "图片浏览器 Activity 与可见内容同时确认（该页面尚未提供专属布局指纹）"
                else "图片浏览页面尚无可见内容"
            )
        }
        return TargetAppPageReadiness(
            false,
            "陌陌当前页面未匹配消息/招呼/动态列表/动态详情的结构指纹：" +
                "feed=${feed.reason}；detail=${detail.reason}；Activity=${currentActivity.ifBlank { "UNKNOWN" }}"
        )
    }

    fun hasVisibleViewId(root: AccessibilityNodeInfo, expectedIds: Set<String>): Boolean {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                try {
                    val viewId = safeString { node.viewIdResourceName.orEmpty() }
                    if (node.isVisibleToUser && viewId in expectedIds) return true
                    val childCount = safeInt { node.childCount }
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= PAGE_FINGERPRINT_MAX_NODES) break
                        safeNode { node.getChild(index) }?.let(queue::add)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
        return false
    }

    private fun hasVisibleContent(root: AccessibilityNodeInfo): Boolean {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    if (node.isVisibleToUser && bounds.width() > dp(24) && bounds.height() > dp(24)) return true
                    val childCount = safeInt { node.childCount }
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= PAGE_FINGERPRINT_MAX_NODES) break
                        safeNode { node.getChild(index) }?.let(queue::add)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
        return false
    }

    private inline fun safeString(block: () -> String): String = runCatching(block).getOrDefault("")
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    private companion object {
        const val PAGE_FINGERPRINT_MAX_NODES = 500
        const val POPUP_SAFETY_SCAN_DIAGNOSTIC_NODE_THRESHOLD = 350
        const val POPUP_SAFETY_SCAN_DIAGNOSTIC_DURATION_MS = 300L
    }
}
