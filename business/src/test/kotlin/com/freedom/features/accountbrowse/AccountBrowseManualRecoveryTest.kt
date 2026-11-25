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
    fun coldBrowseStartupSchedulesSecondReadOnlySampleAndResumesOriginalPhase() {
        val scheduler = QueuedScheduler()
        var fingerprint = "feed-a"
        var pageEvidence = page()
        var reads = 0
        var countdowns = 0
        var swipes = 0
        var generation = 7L
        lateinit var runtime: AccountBrowseRuntime
        val manager = com.freedom.features.recovery.UnifiedRecoveryTransactionManager({ generation })
        runtime = AccountBrowseRuntime(
            scheduler = scheduler,
            canScheduleStep = { true },
            isSessionEnabled = { true },
            isTouchActive = { false },
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { generation },
            recoveryTransactionManager = manager,
            pageSelectedStartup = true,
            onResetSession = {},
            onReadOnlyRecoveryObservation = {
                reads++
                runtime.observeRecoveryPage(pageEvidence)
                if (runtime.observeDynamicFeedFingerprintReadOnly(fingerprint, pageEvidence, true)) {
                    runtime.scheduleStep(300L)
                }
            },
            actions = AccountBrowseRuntime.Actions(
                waitForDynamicTab = {}, verifyNavigationAction = {},
                refreshDynamicFeed = {}, verifyDynamicFeedRefresh = {},
                waitForFeedStable = {
                    reads++
                    if (runtime.observeDynamicFeedFingerprint(fingerprint, pageEvidence, true)) {
                        assertTrue(runtime.canExecuteAutomaticStep())
                        runtime.transitionTo(AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN)
                        runtime.scheduleStep(1000L)
                    } else runtime.scheduleStep(750L)
                },
                runFirstSwipeCountdown = {
                    assertTrue(runtime.canExecuteAutomaticStep())
                    countdowns++
                    runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_LIST_SCROLL)
                    runtime.scheduleStep(1000L)
                },
                performFeedScroll = {
                    assertTrue(runtime.canExecuteAutomaticStep())
                    swipes++
                },
                verifyInitialSwipe = {}, openVisibleFeedCard = {}, checkDetailPageLoaded = {},
                returnFromDetailPage = {}, waitForImageBrowserReturn = {},
                waitForDetailRestabilize = {}, checkFeedListReturned = {}
            )
        )
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_FEED_STABLE)
        runtime.scheduleStep(300L)
        scheduler.runNext()
        assertEquals(1, reads)
        assertEquals(1, runtime.recoveryStableSamples())
        assertEquals(RecoveryTransactionState.VERIFYING, runtime.recoveryState())
        assertFalse(runtime.canExecuteAutomaticStep())
        // The old phase dispatch still cannot run in VERIFYING.
        runtime.performStep()
        assertEquals(1, reads)
        assertEquals(0, countdowns)
        assertEquals(0, swipes)

        // A changed feed cannot satisfy the second sample.
        fingerprint = "feed-b"
        scheduler.runNext()
        assertEquals(2, reads)
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        scheduler.runNext() // old ordinary phase callback is blocked
        assertEquals(2, reads)
        assertEquals(0, countdowns)

        // An intervening wrong page invalidates the previous sample.
        pageEvidence = page(BrowsePageKind.HOME)
        scheduler.runNext()
        assertEquals(3, reads)
        assertEquals(0, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        pageEvidence = page()
        scheduler.runNext()
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        scheduler.runNext()
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertEquals(0, countdowns)
        assertEquals(0, swipes)
        assertEquals(null, manager.snapshot("ACCOUNT_BROWSE"))

        scheduler.runNext() // original phase must read again before countdown
        assertEquals(AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN, runtime.phase)
        assertEquals(6, reads)
        assertEquals(0, swipes)
        scheduler.runNext()
        assertEquals(1, countdowns)
        scheduler.runNext()
        assertEquals(1, swipes)

        // A new stability phase on an unchanged feed must not reuse the completed round.
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_FEED_STABLE)
        runtime.scheduleStep(300L)
        scheduler.runNext()
        assertEquals(RecoveryTransactionState.VERIFYING, runtime.recoveryState())
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        val old = scheduler.nextCallback()
        generation++
        runtime.suspendSession()
        old()
        assertEquals(AccountBrowsePhase.IDLE, runtime.phase)
        assertEquals(1, swipes)
    }

    @Test
    fun readOnlyCompletionRequiresFreshMatchingFeedBeforeCountdown() {
        val runtime = runtime()
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_FEED_STABLE)
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-a", page(), routeVerified = true))
        assertFalse(runtime.canExecuteAutomaticStep())
        assertTrue(runtime.observeDynamicFeedFingerprintReadOnly("feed-a", page(), routeVerified = true))
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertEquals(AccountBrowsePhase.WAIT_FOR_FEED_STABLE, runtime.phase)
        // The asynchronous proof must not authorize the changed feed.
        assertFalse(runtime.observeDynamicFeedFingerprint("feed-b", page(), routeVerified = true))
        assertEquals(RecoveryTransactionState.RECOVERING, runtime.recoveryState())
        assertFalse(runtime.canExecuteAutomaticStep())
        assertFalse(runtime.observeDynamicFeedFingerprintReadOnly("feed-b", page(), routeVerified = true))
        assertTrue(runtime.observeDynamicFeedFingerprintReadOnly("feed-b", page(), routeVerified = true))
        assertTrue(runtime.observeDynamicFeedFingerprint("feed-b", page(), routeVerified = true))
        assertEquals(AccountBrowsePhase.WAIT_FOR_FEED_STABLE, runtime.phase)
    }

    @Test
    fun liveDetailRecoveryKeepsOriginalLoadPhaseAndRequiresTwoFreshDetailSamples() {
        val runtime = runtime(onFeedStep = { error("detail must not execute a feed step") })
        runtime.session.sourceFingerprint = "source-feed"
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD)
        runtime.requestRecovery("unattributed click", {}, {}, {})
        val detail = page(BrowsePageKind.DETAIL).copy(fingerprint = "detail-window-1")
        assertFalse(runtime.observeDetailFingerprintReadOnly(detail, true))
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        runtime.performStep()
        assertFalse(runtime.observeDetailFingerprintReadOnly(detail, false))
        assertEquals(0, runtime.recoveryStableSamples())
        assertFalse(runtime.observeDetailFingerprintReadOnly(detail, true))
        assertFalse(runtime.observeDetailFingerprintReadOnly(detail.copy(fingerprint = "detail-window-2"), true))
        assertEquals(1, runtime.recoveryStableSamples())
        assertTrue(runtime.observeDetailFingerprintReadOnly(detail.copy(fingerprint = "detail-window-2"), true))
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertEquals(AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD, runtime.phase)
        assertTrue(runtime.canExecuteAutomaticStep())
        runtime.suspendSession()
        assertFalse(runtime.observeDetailFingerprintReadOnly(detail, true))
        assertEquals(AccountBrowsePhase.IDLE, runtime.phase)
    }

    @Test
    fun detailRecoverySchedulerResumesLoadThenOriginalDwellBeforeBack() {
        val scheduler = QueuedScheduler()
        val detail = page(BrowsePageKind.DETAIL).copy(fingerprint = "detail-window")
        var reads = 0
        var loads = 0
        var backs = 0
        lateinit var runtime: AccountBrowseRuntime
        runtime = runtime(
            scheduler = scheduler,
            onReadOnlyObservation = {
                reads++
                runtime.observeRecoveryPage(detail)
                if (runtime.observeDetailFingerprintReadOnly(detail, true)) {
                    runtime.scheduleStep(300L)
                }
            },
            onDetailLoad = {
                assertTrue(runtime.canExecuteAutomaticStep())
                loads++
                runtime.transitionTo(AccountBrowsePhase.DETAIL_DWELL)
                runtime.scheduleStep(4000L)
            },
            onDetailReturn = {
                assertTrue(runtime.canExecuteAutomaticStep())
                backs++
            }
        )
        runtime.session.sourceFingerprint = "source-feed"
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD)
        runtime.requestRecovery("unattributed card click", {}, {}, {})
        scheduler.runNext()
        assertEquals(1, reads)
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        runtime.performStep()
        assertEquals(0, loads)
        assertEquals(0, backs)
        scheduler.runNext()
        assertEquals(2, reads)
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertEquals(0, loads)
        assertEquals(0, backs)
        scheduler.runNext()
        assertEquals(1, loads)
        assertEquals(AccountBrowsePhase.DETAIL_DWELL, runtime.phase)
        assertEquals(0, backs)
        scheduler.runNext()
        assertEquals(1, backs)
    }

    @Test
    fun pictureContentResumesThroughTheSameDetailLoadDwellAndReturnCallbacks() {
        val scheduler = QueuedScheduler()
        var picture = page(BrowsePageKind.IMAGE_VIEWER).copy(fingerprint = "picture-window")
        var reads = 0
        var loads = 0
        var backs = 0
        lateinit var runtime: AccountBrowseRuntime
        runtime = runtime(
            scheduler = scheduler,
            onReadOnlyObservation = {
                reads++
                runtime.observeRecoveryPage(picture)
                if (runtime.observeDetailFingerprintReadOnly(picture, true)) runtime.scheduleStep(300L)
            },
            onDetailLoad = {
                assertTrue(runtime.canExecuteAutomaticStep())
                loads++
                runtime.transitionTo(AccountBrowsePhase.DETAIL_DWELL)
                runtime.scheduleStep(4000L)
            },
            onDetailReturn = {
                assertTrue(runtime.canExecuteAutomaticStep())
                backs++
            }
        )
        runtime.session.sourceFingerprint = "source-feed"
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD)
        runtime.requestRecovery("picture content awaiting observation", {}, {}, {})
        scheduler.runNext()
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        runtime.performStep()
        assertEquals(0, backs)
        picture = picture.copy(fingerprint = "different-picture-window")
        scheduler.runNext()
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        scheduler.runNext()
        assertEquals(3, reads)
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertEquals(0, loads)
        scheduler.runNext()
        assertEquals(AccountBrowsePhase.DETAIL_DWELL, runtime.phase)
        assertEquals(1, loads)
        assertEquals(0, backs)
        scheduler.runNext()
        assertEquals(1, backs)
    }

    @Test
    fun pictureContentCannotReplacePersistedFeedCheckpointOrUnconfirmedEvidence() {
        val restored = runtime(FakeStore(savedCheckpoint()))
        assertTrue(restored.beginCheckpointVerification())
        repeat(3) { assertFalse(restored.observeDetailFingerprintReadOnly(page(BrowsePageKind.IMAGE_VIEWER), true)) }
        assertTrue(restored.hasPendingCheckpointVerification())
        val live = runtime()
        live.session.sourceFingerprint = "source-feed"
        live.transitionTo(AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD)
        live.requestRecovery("unknown page", {}, {}, {})
        repeat(3) {
            assertFalse(live.observeDetailFingerprintReadOnly(page(BrowsePageKind.IMAGE_VIEWER).copy(confidence = 35), true))
        }
        assertFalse(live.canExecuteAutomaticStep())
    }

    @Test
    fun verifiedFeedReturnCanResumeEveryLiveContentPhaseWithoutWaitingForDetailTimeout() {
        for (phase in listOf(AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD, AccountBrowsePhase.DETAIL_DWELL,
            AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN, AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE)) {
            val runtime = runtime()
            runtime.session.sourceFingerprint = "source-feed"
            runtime.session.sourceSnapshot = "original-list-position"
            runtime.transitionTo(phase)
            assertFalse(runtime.canResumeFeedReturn(page(), false))
            assertFalse(runtime.canResumeFeedReturn(page().copy(confidence = 35), true))
            assertFalse(runtime.canResumeFeedReturn(page().copy(windowAvailable = false), true))
            assertFalse(runtime.canResumeFeedReturn(page(BrowsePageKind.IMAGE_VIEWER), true))
            runtime.requestRecovery("unknown during content visit", {}, {}, {})
            assertFalse(runtime.canResumeFeedReturn(page(), true))
            assertFalse(runtime.observeDynamicFeedFingerprintReadOnly("feed", page(), true))
            assertFalse(runtime.canResumeFeedReturn(page(), true))
            assertTrue(runtime.observeDynamicFeedFingerprintReadOnly("feed", page(), true))
            assertTrue(runtime.canResumeFeedReturn(page(), true))
            assertEquals(phase, runtime.phase)
            assertEquals("original-list-position", runtime.session.sourceSnapshot)
            runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_LIST_RETURN)
            assertFalse(runtime.canResumeFeedReturn(page(), true))
            runtime.suspendSession()
            assertFalse(runtime.canResumeFeedReturn(page(), true))
        }
    }

    @Test
    fun contentRecoveryAlsoWorksWhileWaitingForReturnWithoutGrantingBackBeforeActive() {
        for (phase in listOf(AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN, AccountBrowsePhase.WAIT_FOR_LIST_RETURN)) {
            val runtime = runtime()
            runtime.session.sourceFingerprint = "source-feed"
            runtime.transitionTo(phase)
            runtime.requestRecovery("return window temporarily unknown", {}, {}, {})
            val detail = page(BrowsePageKind.DETAIL).copy(fingerprint = "detail-window")
            assertFalse(runtime.observeDetailFingerprintReadOnly(detail, true))
            assertFalse(runtime.canExecuteAutomaticStep())
            assertTrue(runtime.observeDetailFingerprintReadOnly(detail, true))
            assertEquals(phase, runtime.phase)
            assertTrue(runtime.canExecuteAutomaticStep())
        }
    }

    @Test
    fun detailRecoveryCannotReplacePersistedFeedCheckpointVerification() {
        val runtime = runtime(FakeStore(savedCheckpoint()))
        assertTrue(runtime.beginCheckpointVerification())
        val detail = page(BrowsePageKind.DETAIL)
        repeat(3) { assertFalse(runtime.observeDetailFingerprintReadOnly(detail, true)) }
        assertTrue(runtime.hasPendingCheckpointVerification())
        assertFalse(runtime.canExecuteAutomaticStep())
    }

    private class QueuedScheduler : TaskScheduler {
        private data class Entry(val due: Long, val callback: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        private var now = 0L
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(now + delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }
        private fun next() = entries.filterNot { it.cancelled }.minByOrNull { it.due }
            ?: error("No pending callback")
        fun nextCallback(): () -> Unit = next().callback
        fun runNext() {
            val entry = next()
            entry.cancelled = true
            now = entry.due
            entry.callback()
        }
    }

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
    fun suspendedBrowseDiscardsTransientStateAndPreservesOriginalCheckpointUntilFreshVerification() {
        val store = FakeStore(null)
        var generation = 11L
        val runtime = runtime(store, currentRunGeneration = { generation })
        val task = AccountBrowseTask(runtime)
        assertTrue(task.start())
        runtime.session.touchActive = true
        runtime.session.gestureInFlight = true
        runtime.session.overlayPassthroughActive = true
        runtime.session.sourceFingerprint = "old-source"
        runtime.session.lastStableFingerprint = "old-stable"
        runtime.session.stableSamples = 2
        runtime.session.swipeCount = 3
        runtime.transitionTo(AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE)
        runtime.saveCheckpointBeforePageExit()
        val original = requireNotNull(store.value)
        generation++
        task.suspendForPageExit()
        assertEquals(AccountBrowseTask.Status.IDLE, task.status)
        assertEquals(AccountBrowsePhase.IDLE, runtime.phase)
        assertFalse(runtime.session.touchActive)
        assertFalse(runtime.session.gestureInFlight)
        assertFalse(runtime.session.overlayPassthroughActive)
        assertEquals("", runtime.session.sourceFingerprint)
        assertEquals("", runtime.session.lastStableFingerprint)
        assertEquals(0, runtime.session.stableSamples)
        assertEquals(original, store.value)
        assertTrue(task.start())
        assertTrue(runtime.restoreAfterServiceReconnect())
        assertEquals(3, runtime.session.swipeCount)
        assertEquals("", runtime.session.sourceFingerprint)
        assertTrue(runtime.beginCheckpointVerification())
        assertEquals(original, store.value)
        assertFalse(runtime.observeDynamicFeedFingerprint("fresh-feed", page(), routeVerified = true))
        assertEquals(original, store.value)
        assertTrue(runtime.observeDynamicFeedFingerprint("fresh-feed", page(), routeVerified = true))
        assertFalse(runtime.hasPendingCheckpointVerification())
        task.stop({}, {})
        assertEquals(null, store.value)
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

    /**
     * The messages tab is a legitimate Momo page but NOT the dynamic-feed recovery route.
     * Returning to the feed after a persisted browser checkpoint requires two independent
     * confirmed feed observations, followed by the existing fresh-phase recheck.
     */
    @Test
    fun messagesTabToDynamicFeedRequiresRouteProofAndTwoFreshSnapshotsBeforeBrowseStep() {
        val store = FakeStore(savedCheckpoint())
        var feedSteps = 0
        val runtime = runtime(store, currentRunGeneration = { 41L }) { feedSteps++ }
        assertTrue(runtime.beginCheckpointVerification())
        val messagesTab = page(BrowsePageKind.HOME).copy(
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            fingerprint = "momo-messages-tab"
        )
        val dynamics = page().copy(
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            fingerprint = "momo-dynamic-feed-window-18"
        )

        assertFalse(runtime.observeDynamicFeedFingerprint(
            messagesTab.fingerprint, messagesTab, routeVerified = false
        ))
        assertTrue(runtime.hasPendingCheckpointVerification())
        assertFalse(runtime.canExecuteAutomaticStep())
        runtime.performStep()
        assertEquals(0, feedSteps)
        assertTrue(store.value != null)

        assertFalse(runtime.observeDynamicFeedFingerprint(
            dynamics.fingerprint, dynamics, routeVerified = true
        ))
        assertEquals(1, runtime.recoveryStableSamples())
        assertFalse(runtime.canExecuteAutomaticStep())
        runtime.performStep()
        assertEquals(0, feedSteps)

        assertTrue(runtime.observeDynamicFeedFingerprint(
            dynamics.fingerprint, dynamics, routeVerified = true
        ))
        assertEquals(RecoveryTransactionState.ACTIVE, runtime.recoveryState())
        assertFalse(runtime.hasPendingCheckpointVerification())
        assertEquals(0, feedSteps)
        // Restore completion is not the existing feed-phase recheck; that remains separate.
        assertTrue(runtime.observeDynamicFeedFingerprint(
            dynamics.fingerprint, dynamics, routeVerified = true
        ))
        runtime.performStep()
        assertEquals(1, feedSteps)
        assertEquals(AccountBrowsePhase.WAIT_FOR_FEED_STABLE, runtime.phase)
    }

    /**
     * The dynamic-card detail return is not established by a BACK callback or a transient
     * HOME frame. Only two matching current-feed position snapshots may permit restoration.
     */
    @Test
    fun detailToDynamicsReturnRejectsHomeWrongPositionAndSingleFeedFrame() {
        val decision = FeedReturnVerificationPolicy
        var previous = ""
        var samples = 0

        fun observe(snapshot: String, isFeed: Boolean, positionMatches: Boolean): Boolean {
            val result = FeedReturnStability.update(
                previousSnapshot = previous,
                previousSamples = samples,
                currentSnapshot = snapshot,
                isFeedPage = isFeed,
                positionMatches = positionMatches,
                requiredSamples = 2
            )
            previous = result.lastSnapshot
            samples = result.stableSamples
            return result.isStable
        }

        assertFalse(observe("detail-window", isFeed = false, positionMatches = false))
        assertEquals(0, samples)
        assertEquals(FeedReturnVerificationPolicy.Decision.PAUSE_NOT_FEED_PAGE,
            decision.decide(true, false, false, false, deadlineReached = true))
        assertEquals(FeedReturnVerificationPolicy.Decision.PAUSE_ROOT_UNAVAILABLE,
            decision.decide(false, false, false, false, deadlineReached = true))

        assertFalse(observe("feed-window-21-list-position-4", true, true))
        assertEquals(FeedReturnVerificationPolicy.Decision.WAIT,
            decision.decide(true, true, true, false, deadlineReached = false))
        assertFalse(observe("feed-window-21-list-position-5", true, false))
        assertEquals(0, samples)
        assertFalse(observe("feed-window-21-list-position-4", true, true))
        assertEquals(1, samples)
        assertTrue(observe("feed-window-21-list-position-4", true, true))
        assertEquals(2, samples)
        assertEquals(FeedReturnVerificationPolicy.Decision.RESTORED,
            decision.decide(true, true, true, true, deadlineReached = false))
        // A new HOME frame after verification is not a reusable feed-return permission.
        assertFalse(observe("messages-tab-window-22", false, false))
        assertEquals(0, samples)
        assertEquals(FeedReturnVerificationPolicy.Decision.WAIT,
            decision.decide(true, false, false, false, deadlineReached = false))
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
        scheduler: TaskScheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = ScheduledTask { }
        },
        onReadOnlyObservation: () -> Unit = {},
        onDetailLoad: () -> Unit = {},
        onDetailReturn: () -> Unit = {},
        onFeedStep: () -> Unit = {}
    ): AccountBrowseRuntime = AccountBrowseRuntime(
        scheduler = scheduler,
        onReadOnlyRecoveryObservation = onReadOnlyObservation,
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
            checkDetailPageLoaded = onDetailLoad,
            returnFromDetailPage = onDetailReturn,
            waitForImageBrowserReturn = {},
            waitForDetailRestabilize = {},
            checkFeedListReturned = {}
        )
    )
}
