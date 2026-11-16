package com.freedom.core.runtime

/** Handle for one delayed main-thread task; cancellation may be requested from any thread. */
fun interface ScheduledTask {
    fun cancel()
}

/** Android Handler is adapted behind this interface; features do not own Handler/Runnable. */
interface TaskScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask
}
