package com.freedom.adapters.runtime.android

import com.freedom.adapters.accessibility.android.AndroidAccessibilityEventNormalizer
import com.freedom.adapters.accessibility.android.AccessibilityEventFormatter
import com.freedom.adapters.accessibility.android.AndroidAccountBrowseWorkflow
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode

/** Routes normalized lifecycle and accessibility events into the AccountBrowse Android adapter. */
internal class AccountBrowseModeRuntimeAdapter(
    private val workflow: () -> AndroidAccountBrowseWorkflow,
    private val acknowledgeManualReview: (String) -> Unit = {},
    private val onReadinessStarting: (RunMode, String) -> Unit = { _, _ -> },
    private val onReadinessInvalidated: (String) -> Unit = {}
) : ModeRuntimePort {
    override val mode: RunMode = RunMode.ACCOUNT_BROWSE
    private var awaitingServiceReconnect = false

    override fun onRunStarted() {
        onReadinessStarting(mode, "AccountBrowse 已启动；等待目标应用正式就绪")
        // A new run is an explicit user acknowledgement of a previously latched review hold.
        acknowledgeManualReview("显式新建动态浏览运行已通过UI命令确认")
        workflow().startAccountBrowseIfNeeded()
    }

    override fun onRunStopped(reason: String) {
        onReadinessInvalidated("AccountBrowse 已停止：$reason")
        workflow().resetAccountBrowseSession()
    }

    override fun onAccessibilityServiceConnected() {
        if (awaitingServiceReconnect) {
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

    override fun onExternalRefresh() = workflow().startIfIdle()

    override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) {
        val platformType = AndroidAccessibilityEventNormalizer.toAndroidEventType(event.type)
        workflow().onTargetAppEvent(platformType, event.eventTimeMillis)
        workflow().onRefreshVerificationEvent(platformType, event.className, event.eventTimeMillis)
    }

    override fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) {
        val platformType = AndroidAccessibilityEventNormalizer.toAndroidEventType(event.type)
        workflow().handleGlobalTouchEvent(platformType, event.packageName)
    }

    override fun onActivityTransition(activityClass: String) =
        workflow().handleMomoActivityTransition(activityClass)

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
        onReadinessInvalidated("无障碍服务销毁；目标应用就绪状态失效")
        workflow().cleanup()
    }

    override fun resetRuntime(reason: String) = workflow().resetAccountBrowseSession()

    fun isGestureBusyForCalibration(): Boolean = workflow().isGestureBusyForCalibration()
}
