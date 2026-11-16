package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TieredManualHandoffQuietPeriod

/** Owns only cancellable timer handles for manual-takeover coordination; domain state stays in Feature runtime. */
internal class AiReplyManualTakeoverCoordinator(private val scheduler: TaskScheduler) {
    enum class TaskKey {
        USER_CONTROL_SETTLE,
        USER_CONTROL_STABILITY,
        USER_CONTROL_NAVIGATION,
        MESSAGE_TAB_GUARD,
        MESSAGE_TAB_PROBE,
        HANDOFF_MESSAGE_TAB_WATCH
    }

    private val tasks = mutableMapOf<TaskKey, ScheduledTask>()
    val quietPeriod = TieredManualHandoffQuietPeriod()

    fun isScheduled(key: TaskKey): Boolean = tasks[key] != null

    fun cancel(key: TaskKey) {
        tasks.remove(key)?.cancel()
    }

    fun schedule(key: TaskKey, delayMs: Long, action: () -> Unit) {
        cancel(key)
        tasks[key] = scheduler.schedule(delayMs) {
            tasks.remove(key)
            action()
        }
    }

    fun cancelAll() {
        tasks.keys.toList().forEach(::cancel)
    }
}
