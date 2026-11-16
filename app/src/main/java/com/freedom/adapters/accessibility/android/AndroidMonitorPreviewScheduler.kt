package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/** Owns the Android-side debounce for the monitor's active-window preview scan. */
internal class AndroidMonitorPreviewScheduler(
    private val scheduler: TaskScheduler,
    private val isBrowseRunActive: () -> Boolean,
    private val scanActiveWindow: () -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS
) {
    private var pendingTask: ScheduledTask? = null

    /** Replace any pending request and run one scan after the established debounce. */
    fun scheduleBrowseScan() {
        pendingTask?.cancel()
        pendingTask = scheduler.schedule(debounceMillis) {
            pendingTask = null
            if (isBrowseRunActive()) scanActiveWindow()
        }
    }

    /** Cancel the pending scan when the Service is destroyed. */
    fun cancel() {
        pendingTask?.cancel()
        pendingTask = null
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 350L
    }
}
