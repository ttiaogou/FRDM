package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMonitorPreviewSchedulerTest {
    @Test
    fun repeatedRequestsReplaceThePendingTaskAndKeepThe350MsDebounce() {
        val fakeScheduler = FakeTaskScheduler()
        var scanCount = 0
        val previewScheduler = AndroidMonitorPreviewScheduler(
            scheduler = fakeScheduler,
            isBrowseRunActive = { true },
            scanActiveWindow = { scanCount++ }
        )

        previewScheduler.scheduleBrowseScan()
        previewScheduler.scheduleBrowseScan()

        assertEquals(2, fakeScheduler.tasks.size)
        assertTrue(fakeScheduler.tasks[0].cancelled)
        assertEquals(350L, fakeScheduler.tasks[1].delayMillis)
        assertEquals(0, scanCount)

        fakeScheduler.tasks[1].run()
        assertEquals(1, scanCount)
    }

    @Test
    fun executionRechecksBrowseRunGateAndCancelPreventsTheScan() {
        val fakeScheduler = FakeTaskScheduler()
        var browseRunActive = true
        var scanCount = 0
        val previewScheduler = AndroidMonitorPreviewScheduler(
            scheduler = fakeScheduler,
            isBrowseRunActive = { browseRunActive },
            scanActiveWindow = { scanCount++ }
        )

        previewScheduler.scheduleBrowseScan()
        browseRunActive = false
        fakeScheduler.tasks[0].run()
        assertEquals(0, scanCount)

        browseRunActive = true
        previewScheduler.scheduleBrowseScan()
        previewScheduler.cancel()
        assertTrue(fakeScheduler.tasks[1].cancelled)
        fakeScheduler.tasks[1].run()
        assertEquals(0, scanCount)
    }

    private class FakeTaskScheduler : TaskScheduler {
        val tasks = mutableListOf<FakeTask>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = FakeTask(delayMillis, task)
            tasks += entry
            return ScheduledTask { entry.cancel() }
        }
    }

    private class FakeTask(
        val delayMillis: Long,
        private val action: () -> Unit
    ) {
        var cancelled: Boolean = false
            private set

        fun cancel() {
            cancelled = true
        }

        fun run() {
            if (!cancelled) action()
        }
    }
}
