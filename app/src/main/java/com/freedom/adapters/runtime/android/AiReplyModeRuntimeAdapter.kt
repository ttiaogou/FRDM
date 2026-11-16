package com.freedom.adapters.runtime.android

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.features.aireply.AiReplyObservationRuntime

/** Keeps AI page observation behind the application runtime contract. */
internal class AiReplyModeRuntimeAdapter(
    private val runtime: () -> AiReplyObservationRuntime,
    private val acknowledgeManualReview: (String) -> Unit = {},
    private val isPersistedManualReviewRequired: () -> Boolean = { false },
    private val onLog: (String) -> Unit = {},
    private val onReadinessStarting: (RunMode, String) -> Unit = { _, _ -> },
    private val onReadinessInvalidated: (String) -> Unit = {}
) : ModeRuntimePort {
    override val mode: RunMode = RunMode.AI_REPLY
    private var awaitingServiceReconnect = false

    override fun onRunStarted() {
        onReadinessStarting(mode, "AI Reply 已启动；等待目标应用正式就绪")
        if (isPersistedManualReviewRequired()) {
            awaitingServiceReconnect = true
            runtime().reset("恢复时存在未确认的输入来源复核锁")
            onLog("AI 会话管理：检测到持久化输入来源复核锁；本次运行请求不视为人工确认；保持自动控制暂停")
            return
        }
        // The UI command clears the persisted latch before requesting a new run. Therefore this
        // acknowledgement is safe here and does not turn process/service recovery into approval.
        acknowledgeManualReview("显式新运行已通过UI命令确认")
        awaitingServiceReconnect = false
        runtime().reset("开始新运行前清理")
        // AI Reply is the autonomous inbox-observation workflow. Manual priority is enforced
        // by AiReplyObservationRuntime; passive-only observation is not the run default.
        runtime().scheduleInitialObservation(passiveOnly = false)
    }

    override fun onRunStopped(reason: String) {
        onReadinessInvalidated("AI Reply 已停止：$reason")
        runtime().reset("运行停止：$reason")
    }

    override fun onAccessibilityServiceConnected() {
        if (awaitingServiceReconnect) {
            if (isPersistedManualReviewRequired()) {
                onLog("AI 会话管理：服务重连但输入来源仍待人工复核；保持暂停；不会自动恢复；请显式停止并重新启动运行")
                return
            }
            awaitingServiceReconnect = false
            onReadinessStarting(mode, "AI Reply 服务重连；重新等待目标应用正式就绪")
            runtime().scheduleInitialObservation(passiveOnly = false)
        }
    }

    override fun onAccessibilityServiceDisconnected() {
        onReadinessInvalidated("无障碍服务断开；目标应用就绪状态失效")
        awaitingServiceReconnect = true
        runtime().reset("无障碍服务断开连接")
    }

    override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = runtime().schedule(event)

    override fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) = runtime().onGlobalTouchEvent(event)

    override fun onPreemptiveTargetInteraction(event: AccessibilityEventEnvelope) =
        runtime().onPreemptiveTargetInteraction(event)

    override fun onAccessibilityServiceInterrupted(reason: String) {
        onReadinessInvalidated("无障碍服务中断；目标应用就绪状态失效：$reason")
        awaitingServiceReconnect = true
        runtime().reset("无障碍服务中断：$reason")
    }

    override fun onServiceDestroyed() {
        onReadinessInvalidated("无障碍服务销毁；目标应用就绪状态失效")
        runtime().reset("无障碍服务销毁")
    }

    override fun resetRuntime(reason: String) = runtime().reset("运行时外部重置：$reason")
}
