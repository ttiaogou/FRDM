package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
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

    @Test
    fun reconnectRequiresCheckpointAndPutsExistingRuntimeBackBehindVerificationGate() {
        val store = object : AccountBrowseCheckpointRepository {
            var checkpoint: AccountBrowseCheckpoint? = null
            override fun load(nowEpochMs: Long): AccountBrowseCheckpoint? = checkpoint
            override fun save(checkpoint: AccountBrowseCheckpoint) { this.checkpoint = checkpoint }
            override fun clear() { checkpoint = null }
        }
        val runtime = runtime(checkpointRepository = store)
        assertEquals(false, runtime.restoreAfterServiceReconnect())
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB)
        assertTrue(runtime.restoreAfterServiceReconnect())
        assertEquals(AccountBrowsePhase.PAUSED, runtime.phase)
        assertEquals(com.freedom.features.recovery.RecoveryTransactionState.RECOVERING, runtime.recoveryState())
        assertTrue(runtime.hasPendingCheckpointVerification())
        assertTrue(runtime.beginCheckpointVerification())
        assertEquals(AccountBrowsePhase.WAIT_FOR_FEED_STABLE, runtime.phase)
    }

    private fun runtime(
        checkpointRepository: AccountBrowseCheckpointRepository? = null
    ): AccountBrowseRuntime = AccountBrowseRuntime(
        scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = ScheduledTask { }
        },
        canScheduleStep = { true },
        isSessionEnabled = { true },
        isTouchActive = { false },
        targetPackage = { "com.immomo.momo" },
        checkpointRepository = checkpointRepository,
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
