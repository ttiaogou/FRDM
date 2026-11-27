package com.freedom.application.ports

import com.freedom.core.logging.RunDiagnosticInput
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
import com.freedom.core.accessibility.overlay.FloatingButtonPosition

/**
 * Application-facing repository for monitor settings, runtime intent, logs and presentation state.
 * Android screens and the AccessibilityService depend on this contract rather than MonitorStore.
 */
interface MonitorControlRepository :
    RunStateRepository,
    RuntimeLogSink {

    fun targetPackage(): String
    fun setTargetPackage(packageName: String)
    fun isKeepAliveEnabled(): Boolean
    fun setKeepAliveEnabled(enabled: Boolean)
    fun isBootCheckEnabled(): Boolean
    fun isBootRecoveryPending(): Boolean
    fun setBootRecoveryPending(pending: Boolean)
    fun setBootCheckEnabled(enabled: Boolean)
    fun isOverlayEnabled(): Boolean
    fun setOverlayEnabled(enabled: Boolean)
    fun isFloatingLogEnabled(): Boolean
    fun setFloatingLogEnabled(enabled: Boolean)
    fun floatingButtonPosition(): FloatingButtonPosition = FloatingButtonPosition()
    fun setFloatingButtonPosition(position: FloatingButtonPosition) = Unit
    fun overlayWidthDp(): Int
    fun setOverlayWidthDp(widthDp: Int)
    fun overlayHeightDp(): Int
    fun setOverlayHeightDp(heightDp: Int)
    fun addLog(line: String)
    fun addDiagnosticLog(input: RunDiagnosticInput) = addLog(input.message)
    fun logs(): List<String>
    fun clearLogs()
    fun updateAppPage(appLabel: String? = null, pageLabel: String? = null)
    fun presentationSnapshot(): MonitorPresentationSnapshot

    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.immomo.momo"
    }
}
