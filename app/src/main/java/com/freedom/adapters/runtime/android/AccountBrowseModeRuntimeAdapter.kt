package com.freedom.adapters.runtime.android

import com.freedom.adapters.accessibility.android.AndroidAccessibilityEventNormalizer
import com.freedom.adapters.accessibility.android.AccessibilityEventFormatter
import com.freedom.adapters.accessibility.android.AndroidAccountBrowseWorkflow
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskLease

/** Routes normalized lifecycle and accessibility events into the AccountBrowse Android adapter. */
internal class AccountBrowseModeRuntimeAdapter(
    private val workflow: () -> AndroidAccountBrowseWorkflow,
    private val acknowledgeManualReview: (String) -> Unit = {},
    private val onReadinessStarting: (RunMode, String) -> Unit = { _, _ -> },
    private val onReadinessInvalidated: (String) -> Unit = {},
    private val taskCoordinator: TaskCoordinator,
    private val currentRunGeneration: () -> Long,
    private val onLog: (String) -> Unit = {}
) : ModeRuntimePort {
    override val mode: RunMode = RunMode.ACCOUNT_BROWSE
    private var awaitingServiceReconnect = false
    private var taskLease: TaskLease? = null

    override fun onRunStarted() {
        if (!ensureTaskOwnership("自动浏览启动")) {
            throw IllegalStateException("AccountBrowse could not acquire the exclusive UI operation lease")
        }
        try {
            onReadinessStarting(mode, "AccountBrowse 已启动；等待目标应用正式就绪")
            // A new run is an explicit user acknowledgement of a previously latched review hold.
            acknowledgeManualReview("显式新建动态浏览运行已通过UI命令确认")
            workflow().startAccountBrowseIfNeeded()
        } catch (error: Throwable) {
            releaseTaskOwnership("自动浏览启动失败")
            throw error
        }
    }

    override fun onRunStopped(reason: String) {
        try {
            onReadinessInvalidated("AccountBrowse 已停止：$reason")
            workflow().resetAccountBrowseSession()
        } finally {
            releaseTaskOwnership("自动浏览停止：$reason")
        }
    }

    override fun onAccessibilityServiceConnected() {
        if (awaitingServiceReconnect) {
            if (!ensureTaskOwnership("无障碍服务重连")) return
            awaitingServiceReconnect = false
            onReadinessStarting(mode, "AccountBrowse 服务重连；重新等待目标应用正式就绪")
            workflow().startAccountBrowseIfNeeded()
        }
    }

    override fun onAccessibilityServiceDisconnected() {
        onReadinessInvalidated("无障碍服务断开；目标应用就绪状态失效")
        awaitingServiceReconnect = true
        workflow().cancelScheduledStep()
    }

    override fun onExternalRefresh() {
        if (ensureTaskOwnership("外部刷新")) workflow().startIfIdle()
    }

    override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) {
        if (!ensureTaskOwnership("目标应用事件")) return
        val platformType = AndroidAccessibilityEventNormalizer.toAndroidEventType(event.type)
        workflow().onTargetAppEvent(platformType, event.eventTimeMillis)
        workflow().onRefreshVerificationEvent(platformType, event.className, event.eventTimeMillis)
    }

    override fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) {
        val platformType = AndroidAccessibilityEventNormalizer.toAndroidEventType(event.type)
        workflow().handleGlobalTouchEvent(platformType, event.packageName)
    }

    override fun onActivityTransition(activityClass: String) {
        if (ensureTaskOwnership("Activity 页面转换")) {
            workflow().handleMomoActivityTransition(activityClass)
        }
    }

    override fun isTrackedInteractionEvent(event: AccessibilityEventEnvelope): Boolean =
        workflow().isTrackedInteractionEvent(event.type)

    override fun interactionDiagnostics(
        event: AccessibilityEventEnvelope,
        nowMillis: Long
    ): List<String> {
        if (!workflow().isTrackedInteractionEvent(event.type)) return emptyList()
        val activeWorkflow = workflow()
        val evidence = activeWorkflow.classifyInteractionEventEvidence(event, nowMillis)
        return listOf(
            "操作来源判断=${activeWorkflow.classifyInteractionEventOrigin(event.type, nowMillis, event.className)}",
            "事件证据分类=${evidence.category}；${evidence.description}",
            "当前 Activity=${activeWorkflow.currentActivityForInteractionLog()}",
            "自动浏览阶段=${activeWorkflow.currentPhaseForInteractionLog()}",
            AccessibilityEventFormatter.sourceDetails(event.source)
        )
    }

    override fun handleManualInteractionFallback(event: AccessibilityEventEnvelope) =
        workflow().handleManualInteractionFallback(event)

    override fun onAccessibilityServiceInterrupted(reason: String) {
        onReadinessInvalidated("无障碍服务中断；目标应用就绪状态失效：$reason")
        awaitingServiceReconnect = true
        workflow().pauseAccountBrowse(reason)
    }

    override fun onServiceDestroyed() {
        try {
            onReadinessInvalidated("无障碍服务销毁；目标应用就绪状态失效")
            workflow().cleanup()
        } finally {
            releaseTaskOwnership("无障碍服务销毁")
        }
    }

    override fun resetRuntime(reason: String) {
        try {
            workflow().resetAccountBrowseSession()
        } finally {
            releaseTaskOwnership("运行时重置：$reason")
        }
    }

    private fun ensureTaskOwnership(reason: String): Boolean {
        taskLease?.let { current ->
            if (taskCoordinator.isCurrent(current)) return true
        }
        val lease = taskCoordinator.tryAcquire(TASK_ID, currentRunGeneration())
        if (lease == null) {
            onLog(
                "任务协调[REJECTED]：task=$TASK_ID；reason=$reason；" +
                    "activeTask=${taskCoordinator.activeTaskId() ?: "NONE"}；拒绝自动浏览操作"
            )
            return false
        }
        taskLease = lease
        onLog("任务协调[ACQUIRED]：task=$TASK_ID；reason=$reason；runGeneration=${lease.runGeneration}；已取得 UI 操作权")
        return true
    }

    private fun releaseTaskOwnership(reason: String) {
        val lease = taskLease ?: return
        val released = taskCoordinator.release(lease)
        taskLease = null
        onLog(
            if (released) {
                "任务协调[RELEASED]：task=$TASK_ID；reason=$reason；已释放 UI 操作权"
            } else {
                "任务协调[STALE_RELEASE_IGNORED]：task=$TASK_ID；reason=$reason；租约已过期或已转交"
            }
        )
    }

    private companion object {
        const val TASK_ID = "ACCOUNT_BROWSE"
    }

    fun isGestureBusyForCalibration(): Boolean = workflow().isGestureBusyForCalibration()
}
