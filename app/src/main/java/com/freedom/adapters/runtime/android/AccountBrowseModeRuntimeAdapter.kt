package com.freedom.adapters.runtime.android

import com.freedom.adapters.accessibility.android.AndroidAccessibilityEventNormalizer
import com.freedom.adapters.accessibility.android.AccessibilityEventFormatter
import com.freedom.adapters.accessibility.android.AndroidAccountBrowseWorkflow
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskExecutionController
import com.freedom.features.accountbrowse.AccountBrowsePhase

/** Routes normalized lifecycle and accessibility events into the AccountBrowse Android adapter. */
internal class AccountBrowseModeRuntimeAdapter(
    private val workflow: () -> AndroidAccountBrowseWorkflow,
    private val onReadinessStarting: (RunMode, String) -> Unit = { _, _ -> },
    private val onReadinessInvalidated: (String) -> Unit = {},
    private val taskCoordinator: TaskCoordinator,
    private val currentRunGeneration: () -> Long,
    private val onLog: (String) -> Unit = {}
) : ModeRuntimePort {
    override val mode: RunMode = RunMode.ACCOUNT_BROWSE

    private var awaitingServiceReconnect = false
    private val taskExecutionController = TaskExecutionController(
        coordinator = taskCoordinator,
        currentRunGeneration = currentRunGeneration,
        onLog = onLog
    )

    override fun onRunStarted(taskId: String, retry: Boolean): Boolean {
        require(taskId == TASK_ID)
        if (retry) {
            // Retry preparation only, without reloading checkpoints or resetting the workflow.
            if (!ensureTaskOwnership("动态浏览启动复核")) return false
            if (workflow().phase == AccountBrowsePhase.IDLE) workflow().startIfIdle()
            return workflow().phase != AccountBrowsePhase.IDLE
        }
        taskExecutionController.beginRun(currentRunGeneration())
        if (!ensureTaskOwnership("自动浏览启动")) {
            throw IllegalStateException("AccountBrowse could not acquire the exclusive UI operation lease")
        }
        try {
            onReadinessStarting(mode, "AccountBrowse 已启动；等待目标应用正式就绪")
            // Selected-page startup must verify persisted browse evidence even on cold service creation.
            awaitingServiceReconnect = false
            workflow().restoreRecoveryAfterServiceReconnect()
            workflow().startIfIdle()
            return workflow().phase != AccountBrowsePhase.IDLE
        } catch (error: Throwable) {
            releaseTaskOwnership("自动浏览启动失败")
            throw error
        }
    }

    override fun saveProgressBeforeExit(reason: String, serviceBoundary: Boolean) {
        workflow().saveCheckpointBeforePageExit()
    }

    override fun onRunPaused(reason: String) {
        onReadinessInvalidated("AccountBrowse 页面任务暂停：$reason")
        awaitingServiceReconnect = false
        try {
            workflow().suspendForPageExit()
        } finally {
            releaseTaskOwnership("页面任务暂停；撤销旧 UI 操作租约")
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
            taskExecutionController.beginRun(currentRunGeneration())
            if (!ensureTaskOwnership("无障碍服务重连")) return
            awaitingServiceReconnect = false
            onReadinessStarting(mode, "AccountBrowse 服务重连；重新等待目标应用正式就绪")
            workflow().restoreRecoveryAfterServiceReconnect()
            workflow().startIfIdle()
        }
    }

    override fun onAccessibilityServiceDisconnected() {
        onReadinessInvalidated("无障碍服务断开；目标应用就绪状态失效")
        awaitingServiceReconnect = true
        try {
            workflow().suspendForPageExit()
        } finally {
            releaseTaskOwnership("无障碍服务断开；撤销旧 UI 操作租约")
        }
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
            "操作来源判断=${if (evidence.category == "SELF_CLICK_LEDGER_MATCH") evidence.description else activeWorkflow.classifyInteractionEventOrigin(event.type, nowMillis, event.className)}",
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
        try {
            workflow().suspendForPageExit()
        } finally {
            releaseTaskOwnership("无障碍服务中断；撤销旧 UI 操作租约")
        }
    }

    override fun onServiceDestroyed() {
        try {
            onReadinessInvalidated("无障碍服务销毁；目标应用就绪状态失效")
            workflow().suspendForPageExit()
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

    private fun ensureTaskOwnership(reason: String): Boolean =
        taskExecutionController.claim(TASK_ID, reason)

    private fun releaseTaskOwnership(reason: String) {
        taskExecutionController.release(TASK_ID, reason)
    }

    private companion object {
        const val TASK_ID = "ACCOUNT_BROWSE"
    }

    fun isGestureBusyForCalibration(): Boolean = workflow().isGestureBusyForCalibration()
}
