package com.freedom.adapters.accessibility.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bridges monitor log persistence to the existing Android presentation refresh. */
internal class AndroidMonitorLogPresenter(
    private val persistLog: (String) -> Unit,
    private val refreshPresentation: () -> Unit,
    private val timestamp: () -> String = {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }
) {
    /** Preserve the established timestamp format and refresh once after every persisted log. */
    fun addLog(message: String) {
        persistLog("${timestamp()}  $message")
        refreshPresentation()
    }

    /** Refresh the monitor UI without writing a log entry. */
    fun refresh() = refreshPresentation()
}
