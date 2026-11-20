package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseManualRecoveryTest {
    @Test
    fun manualResumeStartsNavigationAfterFirstQuietStageAndKeepsRecoveryEligibleUntilVerified() {
        val runtime = runtime()
        val scheduledSteps = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        runtime.pauseSession(
            reason = "manual handoff",
            allowManualResume = true,
            capturePausedContext = {},
            beforePause = {},
            hideCountdown = {},
            onPauseReasonChanged = {}
        )

        assertTrue(runtime.session.manualResumeAllowed)
        assertTrue(runtime.session.manualRecoveryInProgress)
        assertFalse(runtime.session.manualRecoveryAttemptActive)

        runtime.resumeAfterManualTouch(
            isMonitoringRunning = { true },
            isBrowseModeEnabled = { true },
            quietPeriodMs = 5_000L,
            quietPeriodStage = 1,
            initialDynamicTabWaitTimeoutMs = 15_000L,
            pageStatePollMs = 300L,
            currentTimeMillis = { 10_000L },
            onLog = logs::add,
            scheduleStep = scheduledSteps::add
        )

        assertEquals(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB, runtime.phase)
        assertTrue(runtime.session.manualResumeAllowed)
        assertTrue(runtime.session.manualRecoveryInProgress)
        assertTrue(runtime.session.manualRecoveryAttemptActive)
        assertEquals(25_000L, runtime.session.deadlineAt)
        assertEquals(listOf(300L), scheduledSteps)
        assertTrue(logs.any { it.contains("静默 5.0 秒") && it.contains("第1轮") })
    }

    @Test
    fun manualRecoveryDoesNotUsePersistentReviewHold() {
        val runtime = runtime()
        val scheduledSteps = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        runtime.pauseSession(
            reason = "manual handoff",
            allowManualResume = true,
            capturePausedContext = {},
            beforePause = {},
            hideCountdown = {},
            onPauseReasonChanged = {}
        )

        runtime.resumeAfterManualTouch(
            isMonitoringRunning = { true },
            isBrowseModeEnabled = { true },
            quietPeriodMs = 60_000L,
            quietPeriodStage = 3,
            initialDynamicTabWaitTimeoutMs = 15_000L,
            pageStatePollMs = 300L,
            currentTimeMillis = { 10_000L },
            onLog = logs::add,
            scheduleStep = scheduledSteps::add
        )

        assertEquals(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB, runtime.phase)
        assertTrue(runtime.session.manualResumeAllowed)
        assertTrue(runtime.session.manualRecoveryAttemptActive)
        assertEquals(listOf(300L), scheduledSteps)
        assertTrue(logs.none { it.contains("已存在持久化人工复核锁") })
        assertTrue(logs.any { it.contains("手动恢复已进入主动导航") })
    }

    private fun runtime(): AccountBrowseRuntime = AccountBrowseRuntime(
        scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = ScheduledTask { }
        },
        canScheduleStep = { true },
        isSessionEnabled = { true },
        isTouchActive = { false },
        targetPackage = { "com.immomo.momo" },
        onResetSession = {},
        actions = AccountBrowseRuntime.Actions(
            waitForDynamicTab = {},
            verifyNavigationAction = {},
            refreshDynamicFeed = {},
            verifyDynamicFeedRefresh = {},
            waitForFeedStable = {},
            runFirstSwipeCountdown = {},
            performFeedScroll = {},
            verifyInitialSwipe = {},
            openVisibleFeedCard = {},
            checkDetailPageLoaded = {},
            returnFromDetailPage = {},
            waitForImageBrowserReturn = {},
            waitForDetailRestabilize = {},
            checkFeedListReturned = {}
        )
    )
}
