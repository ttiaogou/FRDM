package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseManualRecoveryTest {
    @Test
    fun touchRecoveryKeepsTaskInUnifiedRecoveryWithoutManualResumeLatch() {
        val runtime = runtime()
        runtime.requestRecovery(
            reason = "touch observed",
            beforePause = {},
            hideCountdown = {},
            onPauseReasonChanged = {}
        )
        assertEquals(com.freedom.features.recovery.RecoveryTransactionState.RECOVERING, runtime.recoveryState())
    }

    @Test
    fun recoveryDoesNotChangeTheBrowsePhaseToTerminalPause() {
        val runtime = runtime()
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB)
        runtime.requestRecovery(
            reason = "page unknown",
            beforePause = {},
            hideCountdown = {},
            onPauseReasonChanged = {}
        )
        assertEquals(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB, runtime.phase)
        assertEquals(com.freedom.features.recovery.RecoveryTransactionState.RECOVERING, runtime.recoveryState())
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
