package com.freedom.adapters.runtime.android

import android.os.Handler
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/** Handler-backed scheduler kept at the Android composition boundary. */
internal class HandlerTaskScheduler(private val handler: Handler) : TaskScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis.coerceAtLeast(0L))
        return ScheduledTask { handler.removeCallbacks(runnable) }
    }
}
