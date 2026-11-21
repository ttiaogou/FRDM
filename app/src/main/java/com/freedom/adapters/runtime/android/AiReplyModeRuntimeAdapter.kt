package com.freedom.adapters.runtime.android

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.features.aireply.AiReplyObservationRuntime

/** Keeps AI page observation behind the application runtime contract. */
internal class AiReplyModeRuntimeAdapter(
    private val runtime: () -> AiReplyObservationRuntime,
    private val onLog: (String) -> Unit = {},
    private val onReadinessStarting: (RunMode, String) -> Unit = { _, _ -> },
    private val onReadinessInvalidated: (String) -> Unit = {}
) : ModeRuntimePort {
    override val mode: RunMode = RunMode.AI_REPLY
    private var awaitingServiceReconnect = false

    override fun onRunStarted() {
        onReadinessStarting(mode, "AI Reply 已启动；等待目标应用正式就绪")
        awaitingServiceReconnect = false
        runtime().reset("开始新运行前清理")
        runtime().scheduleInitialObservation()
    }

    override fun onRunStopped(reason: String) {
        onReadinessInvalidated("AI Reply 已停止：$reason")
        val activeRuntime = runtime()
        try {
            activeRuntime.reset("运行停止：$reason")
        } finally {
            activeRuntime.clearRecoverySchedulesAfterRunStop()
        }
    }

    override fun onAccessibilityServiceConnected() {
        if (awaitingServiceReconnect) {
            // Recreate the run-local recovery gates before binding persisted schedules.
            // A later scheduleInitialObservation() would otherwise erase the newly rebound proof.
            awaitingServiceReconnect = false
            onReadinessStarting(mode, "AI Reply 服务重连；重新等待目标应用正式就绪")
            runtime().scheduleInitialObservation()
        }
        val messageCheckpointLoaded = runCatching {
            runtime().reloadPrivateMessagesCheckpointAfterServiceReconnect()
        }.getOrDefault(false)
        onLog(
            if (messageCheckpointLoaded) {
                "消息列表服务重连：检查点已读取；当前列表结构仍需统一页面双快照确认"
            } else {
                "消息列表服务重连：检查点已尝试读取；当前列表结构未确认，等待后续页面事件复核"
            }
        )
        val checkpointPageVerified = runCatching {
            runtime().reloadReceivedGreetingsCheckpointAfterServiceReconnect()
        }.getOrDefault(false)
        onLog(
            if (checkpointPageVerified) {
                "收到招呼服务重连：断点已读取且当前页面指纹已确认；继续等待运行时协调"
            } else {
                "收到招呼服务重连：断点已尝试读取；当前页面未确认，保持只读等待并由后续页面事件复核"
            }
        )
    }

    override fun onAccessibilityServiceDisconnected() {
        onReadinessInvalidated("无障碍服务断开；目标应用就绪状态失效")
        awaitingServiceReconnect = true
        val activeRuntime = runtime()
        activeRuntime.persistCheckpointBeforeServiceInterruption("SERVICE_DISCONNECTED")
        activeRuntime.reset("无障碍服务断开连接")
    }

    override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = runtime().schedule(event)

    override fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) = runtime().onGlobalTouchEvent(event)

    override fun onAccessibilityServiceInterrupted(reason: String) {
        onReadinessInvalidated("无障碍服务中断；目标应用就绪状态失效：$reason")
        awaitingServiceReconnect = true
        val activeRuntime = runtime()
        activeRuntime.persistCheckpointBeforeServiceInterruption("SERVICE_INTERRUPTED")
        activeRuntime.reset("无障碍服务中断：$reason")
    }

    override fun onServiceDestroyed() {
        onReadinessInvalidated("无障碍服务销毁；目标应用就绪状态失效")
        val activeRuntime = runtime()
        activeRuntime.persistCheckpointBeforeServiceInterruption("SERVICE_DESTROYED")
        activeRuntime.reset("无障碍服务销毁")
    }

    override fun resetRuntime(reason: String) = runtime().reset("运行时外部重置：$reason")
}
