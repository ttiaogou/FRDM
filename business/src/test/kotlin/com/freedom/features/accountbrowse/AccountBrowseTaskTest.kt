package com.freedom.features.accountbrowse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseTaskTest {
    @Test
    fun task_activation_is_idempotent_and_stop_returns_to_idle() {
        val task = AccountBrowseTask(FakeAccountBrowseRuntime.create())

        assertTrue(task.start())
        assertFalse(task.start())
        assertEquals(AccountBrowseTask.Status.RUNNING, task.status)

        task.stop({}, {})

        assertEquals(AccountBrowseTask.Status.IDLE, task.status)
    }
}

private object FakeAccountBrowseRuntime {
    fun create(): AccountBrowseRuntime = AccountBrowseRuntime(
        scheduler = object : com.freedom.core.runtime.TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit) =
                com.freedom.core.runtime.ScheduledTask { }
        },
        canScheduleStep = { false },
        isSessionEnabled = { false },
        isTouchActive = { false },
        targetPackage = { "com.immomo.momo" },
        onResetSession = {},
        actions = AccountBrowseRuntime.Actions(
            waitForDynamicTab = {}, verifyNavigationAction = {}, refreshDynamicFeed = {},
            verifyDynamicFeedRefresh = {}, waitForFeedStable = {}, runFirstSwipeCountdown = {},
            performFeedScroll = {}, verifyInitialSwipe = {}, openVisibleFeedCard = {},
            checkDetailPageLoaded = {}, returnFromDetailPage = {}, waitForImageBrowserReturn = {},
            waitForDetailRestabilize = {}, checkFeedListReturned = {}
        )
    )
}
