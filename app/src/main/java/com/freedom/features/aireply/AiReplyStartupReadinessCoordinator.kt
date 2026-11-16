package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * Feature-owned startup readiness gate for the verified Message work area.
 * It owns only startup gate state/tasks. Session traversal state remains in AiReplyObservationRuntime.
 */
internal class AiReplyStartupReadinessCoordinator(
    private val scheduler: TaskScheduler,
    private val chatPagePort: ChatPagePort,
    private val targetPackage: () -> String,
    private val isAiReplyActive: () -> Boolean,
    private val isSessionRunStarted: () -> Boolean,
    private val isAutomationYieldedToUser: () -> Boolean,
    private val isUserControlIdle: () -> Boolean,
    private val isManualReviewRequired: () -> Boolean,
    private val inspectFreshPageSnapshot: () -> ChatPageSnapshot?,
    private val isMessageViewportPositionedAgainstTab: (ChatPageSnapshot, ScreenRect, ScreenRect) -> Boolean,
    private val hasRelevantMessageStructureDelta: (ChatPageSnapshot, ChatPageSnapshot, ScreenRect, ScreenRect) -> Boolean,
    private val onConfirmed: (ChatPageSnapshot) -> Unit,
    private val onTimedOut: () -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val MAX_STARTUP_MESSAGE_TAB_RETRIES = 75
        private const val MAX_STARTUP_MESSAGE_TAB_TAPS = 1
        private const val STARTUP_MESSAGE_TAB_RETRY_DELAY_MS = 400L
        private const val USER_CONTROL_NAVIGATION_VERIFY_DELAY_MS = 900L
        private const val USER_CONTROL_STABILITY_CONFIRM_DELAY_MS = 300L
        private const val REQUIRED_USER_CONTROL_STABLE_SAMPLES = 3
    }

    private var task: ScheduledTask? = null
    private var generation = 0L
    private var retryCount = 0
    private var tapAttempts = 0
    private var beforeTap: ChatPageSnapshot? = null
    private var stableSamples = 0
    private var lastStableSnapshot: ChatPageSnapshot? = null
    var timedOut: Boolean = false
        private set
    var workAreaConfirmed: Boolean = false
        private set
    var initializationPending: Boolean = false
        private set

    fun resetForRun(sessionStarted: Boolean) {
        cancelRecovery()
        resetState(sessionStarted)
    }

    fun resetState(sessionStarted: Boolean) {
        initializationPending = sessionStarted
        workAreaConfirmed = !sessionStarted
        retryCount = 0
        tapAttempts = 0
        timedOut = false
    }

    fun markInitializationComplete(reason: String) {
        if (!initializationPending) return
        initializationPending = false
        workAreaConfirmed = true
        cancelRecovery()
        onLog("启动仲裁[INITIALIZATION_COMPLETE]：$reason；首个自主手势已收到 Android 完成回调；UNKNOWN按独立行为累计，达到阈值后才进入分级保护")
    }

    fun markWorkAreaConfirmed() {
        workAreaConfirmed = true
        cancelRecovery()
    }

    fun markReceivedGreetingsWorkAreaConfirmed() {
        initializationPending = false
        workAreaConfirmed = true
        cancelRecovery()
    }

    fun cancelRecovery() {
        task?.cancel()
        task = null
        generation += 1L
        beforeTap = null
        stableSamples = 0
        lastStableSnapshot = null
    }

    /** Starts bounded Activity readiness and Message-tab verification. */
    fun beginVerification() {
        retryCount = 0
        tapAttempts = 0
        timedOut = false
        workAreaConfirmed = false
        beforeTap = null
        stableSamples = 0
        lastStableSnapshot = null
        onLog(
            "AI 启动导航[WAITING]：进入有限次消息工作区就绪识别；" +
                "Activity未就绪时等待，识别到主界面后再验证消息标签和列表视口"
        )
        runProbe(generation)
    }

    private fun runProbe(expectedGeneration: Long) {
        if (expectedGeneration != generation || timedOut || workAreaConfirmed ||
            !initializationPending || !isSessionRunStarted() || !isAiReplyActive() ||
            isAutomationYieldedToUser() || !isUserControlIdle() || isManualReviewRequired()
        ) return

        val fresh = inspectFreshPageSnapshot()
        if (fresh == null || fresh.packageName != targetPackage() ||
            !chatPagePort.supportsTargetPackage(fresh.packageName)
        ) {
            scheduleRetry(expectedGeneration, "目标应用窗口/Activity尚未就绪")
            return
        }

        val tabBounds = fresh.sessionListNavigationTarget?.bounds
        val displayBounds = fresh.screenBounds
        val anchorKey = fresh.messageTabAnchorPositionKey()
        if (tabBounds == null || tabBounds.isEmpty || displayBounds == null || displayBounds.isEmpty ||
            anchorKey == null
        ) {
            scheduleRetry(
                expectedGeneration,
                "尚未取得可验证的底部消息标签和屏幕边界；Activity=${fresh.activityClass.substringAfterLast('.') }"
            )
            return
        }

        if (fresh.pageKind == ChatPageKind.CHAT_LIST) {
            val hasPositionProof = fresh.messageTabPositionKey() != null &&
                isMessageViewportPositionedAgainstTab(fresh, tabBounds, displayBounds)
            if (hasPositionProof) {
                onLog("AI 启动导航[INBOX_ALREADY_VISIBLE]：当前已是消息会话列表；不重复点击标签，开始新鲜视口稳定复核")
                beginListStability(fresh, expectedGeneration, stableSamples = 1)
            } else {
                scheduleRetry(expectedGeneration, "已识别消息列表，但视口尚未与底部消息标签形成有效位置关系")
            }
            return
        }

        if (fresh.pageKind != ChatPageKind.NON_CHAT) {
            scheduleRetry(expectedGeneration, "当前页面类型=${fresh.pageKind.name}；尚不能安全确认消息工作区入口")
            return
        }

        if (tapAttempts >= MAX_STARTUP_MESSAGE_TAB_TAPS) {
            scheduleRetry(expectedGeneration, "消息标签导航点击次数已达上限；等待页面自行完成切换")
            return
        }

        val submitted = runCatching { chatPagePort.ensureMessageWorkArea() }.getOrDefault(false)
        if (!submitted) {
            scheduleRetry(expectedGeneration, "主界面已出现，但消息标签目标尚未通过适配器的双快照校验")
            return
        }

        tapAttempts++
        beforeTap = fresh
        onLog(
            "AI 启动导航[TAP_SUBMITTED]：已提交消息标签导航；" +
                "等待手势完成和新鲜页面快照；tap=$tapAttempts/$MAX_STARTUP_MESSAGE_TAB_TAPS"
        )
        task = scheduler.schedule(USER_CONTROL_NAVIGATION_VERIFY_DELAY_MS) {
            task = null
            if (expectedGeneration == generation) verifyAfterTap(expectedGeneration)
        }
    }

    private fun verifyAfterTap(expectedGeneration: Long) {
        if (expectedGeneration != generation || workAreaConfirmed || timedOut ||
            !isAiReplyActive() || !initializationPending || isAutomationYieldedToUser() ||
            !isUserControlIdle()
        ) return

        val before = beforeTap
        val after = inspectFreshPageSnapshot()
        if (before == null || after == null || after.packageName != targetPackage()) {
            scheduleRetry(expectedGeneration, "点击后未能取得目标应用的新鲜页面快照")
            return
        }

        val beforeAnchor = before.messageTabAnchorPositionKey()
        val afterAnchor = after.messageTabAnchorPositionKey()
        val sameNavigationContext = before.messageTabContextPositionKey() != null &&
            before.messageTabContextPositionKey() == after.messageTabContextPositionKey() &&
            beforeAnchor != null && beforeAnchor == afterAnchor
        val tabBounds = before.sessionListNavigationTarget?.bounds
        val displayBounds = before.screenBounds
        val positionCrossValidated = tabBounds != null && displayBounds != null &&
            isMessageViewportPositionedAgainstTab(after, tabBounds, displayBounds)
        val sameExistingInboxPosition = before.pageKind == ChatPageKind.CHAT_LIST &&
            after.pageKind == ChatPageKind.CHAT_LIST &&
            before.messageTabPositionKey() != null &&
            before.messageTabPositionKey() == after.messageTabPositionKey()
        val cleanNonChatStart = before.pageKind == ChatPageKind.NON_CHAT &&
            before.chatListViewport?.isEmpty != false && before.messageListCount == 0 &&
            before.messageListTarget == null && before.chatListCandidates.isEmpty()
        val navigatedIntoInbox = cleanNonChatStart && after.pageKind == ChatPageKind.CHAT_LIST &&
            tabBounds != null && displayBounds != null &&
            hasRelevantMessageStructureDelta(before, after, tabBounds, displayBounds)

        if (!sameNavigationContext || !positionCrossValidated ||
            !(sameExistingInboxPosition || navigatedIntoInbox)
        ) {
            onLog(
                "AI 启动导航[POST_TAP_NOT_CONFIRMED]：点击后页面尚未同时满足消息页身份、" +
                    "稳定导航锚点和列表/标签空间位置关系；page=${after.pageKind.name}；" +
                    "sameContext=$sameNavigationContext；positionCrossValidated=$positionCrossValidated；" +
                    "sameInboxPosition=$sameExistingInboxPosition；navigatedIntoInbox=$navigatedIntoInbox"
            )
            scheduleRetry(expectedGeneration, "点击后位置/结构证据尚未确认")
            return
        }

        onLog("AI 启动导航[POSITION_CONFIRMED]：新快照确认消息会话列表视口与底部消息标签位置一致；开始连续稳定复核")
        beforeTap = null
        beginListStability(after, expectedGeneration, stableSamples = 1)
    }

    private fun beginListStability(
        snapshot: ChatPageSnapshot,
        expectedGeneration: Long,
        stableSamples: Int
    ) {
        lastStableSnapshot = snapshot
        this.stableSamples = stableSamples
        scheduleListStabilitySample(expectedGeneration)
    }

    private fun scheduleListStabilitySample(expectedGeneration: Long) {
        if (task != null || expectedGeneration != generation) return
        task = scheduler.schedule(USER_CONTROL_STABILITY_CONFIRM_DELAY_MS) {
            task = null
            verifyListStability(expectedGeneration)
        }
    }

    private fun verifyListStability(expectedGeneration: Long) {
        if (expectedGeneration != generation || workAreaConfirmed || timedOut ||
            !isAiReplyActive() || !initializationPending || isAutomationYieldedToUser() ||
            !isUserControlIdle()
        ) return

        val previous = lastStableSnapshot
        val fresh = inspectFreshPageSnapshot()
        val tabBounds = fresh?.sessionListNavigationTarget?.bounds
        val displayBounds = fresh?.screenBounds
        val freshPositionKey = fresh?.messageTabPositionKey()
        val previousPositionKey = previous?.messageTabPositionKey()
        val isStable = fresh != null && previous != null &&
            fresh.packageName == targetPackage() && fresh.pageKind == ChatPageKind.CHAT_LIST &&
            freshPositionKey != null && freshPositionKey == previousPositionKey &&
            fresh.messageTabAnchorPositionKey() == previous.messageTabAnchorPositionKey() &&
            fresh.navigationStructureKey() == previous.navigationStructureKey() &&
            tabBounds != null && displayBounds != null &&
            isMessageViewportPositionedAgainstTab(fresh, tabBounds, displayBounds)

        if (!isStable) {
            onLog(
                "AI 启动导航[STABILITY_RECHECK]：消息列表结构/位置尚未连续稳定；" +
                    "previous=${previous?.pageKind?.name ?: "NONE"}；fresh=${fresh?.pageKind?.name ?: "UNAVAILABLE"}；" +
                    "stableSamples=$stableSamples/$REQUIRED_USER_CONTROL_STABLE_SAMPLES；" +
                    "重新以新快照建立稳定基准，不使用旧节点或旧坐标"
            )
            if (fresh != null && fresh.packageName == targetPackage() &&
                fresh.pageKind == ChatPageKind.CHAT_LIST && freshPositionKey != null &&
                tabBounds != null && displayBounds != null &&
                isMessageViewportPositionedAgainstTab(fresh, tabBounds, displayBounds)
            ) {
                beginListStability(fresh, expectedGeneration, stableSamples = 1)
            } else {
                scheduleRetry(expectedGeneration, "稳定复核期间消息页面或位置证据暂时丢失")
            }
            return
        }

        val stableSnapshot = fresh ?: return
        stableSamples++
        lastStableSnapshot = stableSnapshot
        if (stableSamples >= REQUIRED_USER_CONTROL_STABLE_SAMPLES) {
            confirmWorkArea(stableSnapshot, expectedGeneration)
        } else {
            onLog(
                "AI 启动导航[STABILITY_SAMPLE]：消息列表身份、结构和视口位置连续稳定" +
                    "$stableSamples/$REQUIRED_USER_CONTROL_STABLE_SAMPLES"
            )
            scheduleListStabilitySample(expectedGeneration)
        }
    }

    private fun confirmWorkArea(snapshot: ChatPageSnapshot, expectedGeneration: Long) {
        if (expectedGeneration != generation || workAreaConfirmed) return
        workAreaConfirmed = true
        retryCount = 0
        tapAttempts = 0
        timedOut = false
        cancelRecovery()
        onConfirmed(snapshot)
    }

    private fun scheduleRetry(expectedGeneration: Long, reason: String) {
        if (expectedGeneration != generation || task != null || workAreaConfirmed || timedOut) return
        if (retryCount >= MAX_STARTUP_MESSAGE_TAB_RETRIES) {
            timedOut = true
            onTimedOut()
            onLog(
                "AI 启动导航[TIMEOUT]：${MAX_STARTUP_MESSAGE_TAB_RETRIES} 次新鲜页面复核仍未确认消息工作区域；" +
                    "本次启动安全暂停；不执行滑动或会话点击；最后原因=$reason"
            )
            return
        }
        retryCount++
        onLog(
            "AI 启动导航[RETRY]：$reason；retry=$retryCount/$MAX_STARTUP_MESSAGE_TAB_RETRIES；" +
                "${STARTUP_MESSAGE_TAB_RETRY_DELAY_MS}ms 后重新采集页面，不复用旧快照"
        )
        task = scheduler.schedule(STARTUP_MESSAGE_TAB_RETRY_DELAY_MS) {
            task = null
            if (expectedGeneration == generation) runProbe(expectedGeneration)
        }
    }
}
