package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.recovery.RecoveryTransactionState
import org.junit.Assert.assertFalse
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
    fun reservedBrowseTaskCannotDispatchDelayedGestureOnceRecoveryStarts() {
        val runtime = runtime()
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE)
        assertTrue(runtime.canExecuteAutomaticStep())

        // Recovery revokes execution permission without requiring a terminal phase
        // change or relinquishing the task slot. Queued gestures must recheck this.
        runtime.requestRecovery(
            reason = "page became unknown after gesture plan",
            beforePause = {},
            hideCountdown = {},
            onPauseReasonChanged = {}
        )

        assertEquals(AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE, runtime.phase)
        assertEquals(RecoveryTransactionState.RECOVERING, runtime.recoveryState())
        assertFalse(runtime.canExecuteAutomaticStep())
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

    @Test
    fun restoredBrowseCheckpointUsesSharedVerifierBeforeResumingExistingFeedPhase() {
        val store = FakeStore(savedCheckpoint())
        var unverifiedSteps = 0
        val runtime = runtime(store, currentRunGeneration = { 41L }) { unverifiedSteps++ }
        assertEquals(AccountBrowsePhase.PAUSED, runtime.phase)
        assertEquals(RecoveryTransactionState.RECOVERING, runtime.recoveryState())
        assertTrue(runtime.beginCheckpointVerification())

        val feed = page()
        assertFalse(runtime.observeDynamicFeedFingerprint("fresh-feed", feed, routeVerified = true))
        assertEquals(RecoveryTransactionState.VERIFYING, runtime.recoveryState())
        assertEquals(1, runtime.recoveryStableSamples())
        runtime.performStep()
        assertEquals(0, unverifiedSteps)

        assertTrue(runtime.observeDynamicFeedFingerprint("fresh-feed", feed, routeVerified = true))
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertFalse(runtime.hasPendingCheckpointVerification())
        // The original WAIT_FOR_FEED_STABLE step must still re-read the same feed before countdown.
        assertTrue(runtime.observeDynamicFeedFingerprint("fresh-feed", feed, routeVerified = true))
        assertEquals(AccountBrowsePhase.WAIT_FOR_FEED_STABLE, runtime.phase)
        assertEquals(0, unverifiedSteps)
    }

    @Test
    fun invalidPageBetweenTwoSamplesCannotAuthorizeResume() {
        val runtime = runtime(FakeStore(savedCheckpoint()))
        assertTrue(runtime.beginCheckpointVerification())
        val feed = page()
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", feed, routeVerified = true))
        assertFalse(runtime.observeDynamicFeedFingerprint("", page(BrowsePageKind.HOME), routeVerified = false))
        assertEquals(RecoveryTransactionState.RECOVERING, runtime.recoveryState())
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", feed, routeVerified = true))
        assertEquals(1, runtime.recoveryStableSamples())
        assertTrue(runtime.hasPendingCheckpointVerification())
        assertTrue(runtime.observeDynamicFeedFingerprint("feed-a", feed, routeVerified = true))
    }

    @Test
    fun changedFeedFingerprintRestartsFreshStableSamplesWithoutClearingCheckpoint() {
        val store = FakeStore(savedCheckpoint())
        val runtime = runtime(store)
        assertTrue(runtime.beginCheckpointVerification())

        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", page(), routeVerified = true))
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-b", page(), routeVerified = true))
        assertEquals(1, runtime.recoveryStableSamples())
        assertTrue(runtime.hasPendingCheckpointVerification())
        assertTrue(store.value != null)
        assertTrue(runtime.observeDynamicFeedFingerprint("feed-b", page(), routeVerified = true))
        assertFalse(runtime.hasPendingCheckpointVerification())
    }

    @Test
    fun missingRouteOrUnreadyFeedCannotCountAsVerifiedCheckpointEvidence() {
        val runtime = runtime(FakeStore(savedCheckpoint()))
        assertTrue(runtime.beginCheckpointVerification())
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", page(), routeVerified = false))
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", page().copy(confidence = 20), routeVerified = true))
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", page().copy(windowAvailable = false), routeVerified = true))
        assertEquals(RecoveryTransactionState.RECOVERING, runtime.recoveryState())
        assertEquals(0, runtime.recoveryStableSamples())
        assertTrue(runtime.hasPendingCheckpointVerification())
    }

    @Test
    fun newlyVerifiedFeedMustStillMatchBeforeEnteringOriginalCountdownPhase() {
        val runtime = runtime(FakeStore(savedCheckpoint()))
        assertTrue(runtime.beginCheckpointVerification())
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", page(), routeVerified = true))
        assertTrue(runtime.observeDynamicFeedFingerprint("feed-a", page(), routeVerified = true))
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-b", page(), routeVerified = true))
        assertEquals(RecoveryTransactionState.RECOVERING, runtime.recoveryState())
    }

    private class FakeStore(var value: AccountBrowseCheckpoint?) : AccountBrowseCheckpointRepository {
        override fun load(nowEpochMs: Long): AccountBrowseCheckpoint? = value
        override fun save(checkpoint: AccountBrowseCheckpoint) { value = checkpoint }
        override fun clear() { value = null }
    }

    private fun savedCheckpoint() = AccountBrowseCheckpoint(
        phase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN.name,
        sourceFingerprint = "old-source",
        detailFingerprint = "old-detail",
        lastStableFingerprint = "old-list",
        sourceSnapshot = "old-business-snapshot",
        currentActivityClass = "FeedActivity",
        imageBrowserActive = false,
        swipeCount = 3,
        feedRefreshRecoveryActive = false,
        feedRefreshLastSnapshot = "",
        savedAtEpochMs = 100L
    )

    private fun page(kind: BrowsePageKind = BrowsePageKind.DYNAMIC_FEED) = BrowsePageEvidence(
        packageName = "com.immomo.momo",
        activityClass = "FeedActivity",
        pageKind = kind,
        fingerprint = "current-page-route",
        confidence = 100,
        reason = "FRESH_READ"
    )

    private fun runtime(
        checkpointRepository: AccountBrowseCheckpointRepository? = null,
        currentRunGeneration: () -> Long = { 0L },
        onFeedStep: () -> Unit = {}
    ): AccountBrowseRuntime = AccountBrowseRuntime(
        scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = ScheduledTask { }
        },
        canScheduleStep = { true },
        isSessionEnabled = { true },
        isTouchActive = { false },
        targetPackage = { "com.immomo.momo" },
        checkpointRepository = checkpointRepository,
        currentRunGeneration = currentRunGeneration,
        onResetSession = {},
        actions = AccountBrowseRuntime.Actions(
            waitForDynamicTab = {},
            verifyNavigationAction = {},
            refreshDynamicFeed = {},
            verifyDynamicFeedRefresh = {},
            waitForFeedStable = onFeedStep,
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
