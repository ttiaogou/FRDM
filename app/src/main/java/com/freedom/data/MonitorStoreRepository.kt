package com.freedom.data

import android.content.Context
import com.freedom.application.ports.MonitorControlRepository
import com.freedom.core.accessibility.overlay.FloatingButtonPosition
import com.freedom.application.ports.MonitorPresentationSnapshot
import com.freedom.core.runtime.RunMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Data adapter retaining the existing SharedPreferences keys and MonitorStore semantics. */
class MonitorStoreRepository(context: Context) : MonitorControlRepository {
    private val appContext = context.applicationContext

    init {
        val diagnostics = RunDiagnosticsManager.get(appContext)
        diagnostics.recoverPreviousRunIfNeeded()
        // If the persisted run intent survived process death, start a new diagnostic trace only
        // after the previous process has been marked as an uncleanly ended run.
        if (MonitorStore.isRunning(appContext)) {
            diagnostics.beginRun(
                mode = MonitorStore.activeRunMode(appContext)?.name ?: "MANUAL_OBSERVATION",
                resumedAfterProcessRecovery = true
            )
        }
    }

    override fun targetPackage(): String = MonitorStore.targetPackage(appContext)

    override fun setTargetPackage(packageName: String) =
        MonitorStore.setTargetPackage(appContext, packageName)

    override fun isBrowseModeSelected(): Boolean = RunModePreferences.isBrowseModeSelected(appContext)

    override fun setBrowseModeSelected(enabled: Boolean) =
        RunModePreferences.setBrowseModeSelected(appContext, enabled)

    override fun isAiReplyModeSelected(): Boolean = RunModePreferences.isAiReplyModeSelected(appContext)

    override fun setAiReplyModeSelected(enabled: Boolean) =
        RunModePreferences.setAiReplyModeSelected(appContext, enabled)

    override fun isRunRequested(): Boolean = MonitorStore.isRunning(appContext)

    override fun activeMode(): RunMode? = MonitorStore.activeRunMode(appContext)

    override fun setActiveMode(mode: RunMode?) = MonitorStore.setActiveRunMode(appContext, mode)

    override fun setRunRequested(requested: Boolean) = MonitorStore.setRunning(appContext, requested)

    override fun isInputManualReviewRequired(): Boolean = MonitorStore.isInputManualReviewRequired(appContext)

    override fun setInputManualReviewRequired(required: Boolean) =
        MonitorStore.setInputManualReviewRequired(appContext, required)

    override fun selectedMode(): RunMode? = RunModePreferences.selectedMode(appContext)

    override fun isKeepAliveEnabled(): Boolean = MonitorStore.isKeepAliveEnabled(appContext)

    override fun setKeepAliveEnabled(enabled: Boolean) = MonitorStore.setKeepAliveEnabled(appContext, enabled)

    override fun isBootCheckEnabled(): Boolean = MonitorStore.isBootCheckEnabled(appContext)

    override fun setBootCheckEnabled(enabled: Boolean) = MonitorStore.setBootCheckEnabled(appContext, enabled)

    override fun isBootRecoveryPending(): Boolean = MonitorStore.isBootRecoveryPending(appContext)

    override fun setBootRecoveryPending(pending: Boolean) =
        MonitorStore.setBootRecoveryPending(appContext, pending)

    override fun isOverlayEnabled(): Boolean = MonitorStore.isOverlayEnabled(appContext)

    override fun setOverlayEnabled(enabled: Boolean) = MonitorStore.setOverlayEnabled(appContext, enabled)

    override fun isFloatingLogEnabled(): Boolean = MonitorStore.isFloatingLogEnabled(appContext)

    override fun setFloatingLogEnabled(enabled: Boolean) = MonitorStore.setFloatingLogEnabled(appContext, enabled)

    override fun floatingButtonPosition(): FloatingButtonPosition =
        MonitorStore.floatingButtonPosition(appContext)

    override fun setFloatingButtonPosition(position: FloatingButtonPosition) =
        MonitorStore.setFloatingButtonPosition(appContext, position)

    override fun overlayWidthDp(): Int = MonitorStore.overlayWidthDp(appContext)

    override fun setOverlayWidthDp(widthDp: Int) = MonitorStore.setOverlayWidthDp(appContext, widthDp)

    override fun overlayHeightDp(): Int = MonitorStore.overlayHeightDp(appContext)

    override fun setOverlayHeightDp(heightDp: Int) = MonitorStore.setOverlayHeightDp(appContext, heightDp)

    override fun addLog(line: String) = MonitorStore.addLog(appContext, line)

    override fun append(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        addLog("$timestamp  $message")
    }

    override fun recordException(component: String, eventType: String, message: String, error: Throwable) {
        addLog("$component：$message；异常=${error.javaClass.simpleName}：${error.message.orEmpty()}")
        RunDiagnosticsManager.get(appContext).recordException(component, eventType, message, error)
    }

    override fun finishRun(result: String, reason: String) {
        RunDiagnosticsManager.get(appContext).finishRun(result = result, reason = reason)
    }

    override fun logs(): List<String> = MonitorStore.logs(appContext)

    override fun clearLogs() = MonitorStore.clearLogs(appContext)

    override fun updateAppPage(appLabel: String?, pageLabel: String?) =
        MonitorStore.updateAppPage(appContext, app = appLabel, page = pageLabel)

    override fun presentationSnapshot(): MonitorPresentationSnapshot {
        val snapshot = MonitorStore.snapshot(appContext)
        return MonitorPresentationSnapshot(
            appLabel = snapshot.app,
            pageLabel = snapshot.page,
            nodePreview = snapshot.nodes,
            visitedNodeCount = snapshot.visitedCount,
            truncated = snapshot.truncated
        )
    }
}
