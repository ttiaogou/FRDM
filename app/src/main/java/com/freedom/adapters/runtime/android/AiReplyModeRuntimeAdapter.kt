package com.freedom.adapters.runtime.android

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.PageRuntimeSessionState
import com.freedom.core.runtime.RunMode
import com.freedom.features.aireply.AiReplyObservationRuntime

/** 通过应用运行时契约封装 AI 页面观察能力。 */
internal class AiReplyModeRuntimeAdapter(
    private val runtime: () -> AiReplyObservationRuntime,
    private val onLog: (String) -> Unit = {},
    private val onReadinessStarting: (RunMode, String) -> Unit = { _, _ -> },
    private val onReadinessInvalidated: (String) -> Unit = {},
    private val currentRunGeneration: () -> Long = { 0L }
) : ModeRuntimePort {
    override val mode: RunMode = RunMode.AI_REPLY

    private var awaitingServiceReconnect = false

    override fun onRunStarted(taskId: String, retry: Boolean): Boolean {
        if (!retry) {
            onReadinessStarting(mode, "AI Reply 页面运行时启动；等待目标应用正式就绪")
            awaitingServiceReconnect = false
            runtime().reset("开始新页面运行前清理")
        }
        // Configuration/consent and archive admission remain mandatory.
        if (!runtime().preparePageSelectedStartup(taskId)) return false
        runtime().requestPageRouteObservation()
        return true
    }

    override fun onPageTaskChanged(session: PageRuntimeSessionState) {
        runtime().requestPageRouteObservation()
    }

    override fun saveProgressBeforeExit(reason: String, serviceBoundary: Boolean) {
        runtime().persistTaskProgressBeforeExit(reason, serviceBoundary)
    }

    override fun onRunPaused(reason: String) {
        onReadinessInvalidated("AI Reply 页面任务暂停：$reason")
        awaitingServiceReconnect = false
        runtime().reset("页面任务暂停：$reason", preservePendingRecovery = false)
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
            // 在重新绑定持久化调度之前，先重建本次运行所需的恢复门禁。
            // 否则后续 scheduleInitialObservation() 可能清除刚刚重新绑定的验证证据。
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
