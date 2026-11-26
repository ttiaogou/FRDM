package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager
import com.freedom.features.recovery.RecoveryCheckpoint
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicPageRecoveryTest {
    @Test
    fun unknown_page_requires_two_stable_fingerprints_before_resume() {
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 7L }
        )

        assertEquals(
            RecoveryTransactionState.RECOVERING,
            recovery.observePage(
                BrowsePageEvidence(
                    packageName = "com.immomo.momo",
                    activityClass = "FeedActivity",
                    pageKind = BrowsePageKind.UNKNOWN,
                    fingerprint = "",
                    confidence = 0,
                    reason = "page_not_confirmed"
                )
            )
        )
        assertFalse(recovery.observeFingerprint("feed-a"))
        assertTrue(recovery.observeFingerprint("feed-a"))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
    }

    @Test
    fun observationHintsReuseManagedVerificationWithoutRewindingOrWaivingFreshEvidence() {
        val manager = UnifiedRecoveryTransactionManager({ 7L })
        var plan: RecoveryScheduleSnapshot? = null
        var saves = 0
        val repository = object : RecoveryScheduleRepository {
            override fun load() = plan
            override fun save(snapshot: RecoveryScheduleSnapshot) { plan = snapshot; saves++ }
            override fun clear() { plan = null }
        }
        val recovery = DynamicPageRecovery(noOpScheduler, { "com.immomo.momo" }, { 7L },
            scheduleRepository = repository, sharedManager = manager)
        val unknown = BrowsePageEvidence("com.immomo.momo", "FeedActivity", BrowsePageKind.UNKNOWN, "", 0, "unknown")
        recovery.observePage(unknown)
        assertFalse(recovery.observeFingerprint("feed-a"))
        val before = plan
        val writes = saves
        repeat(300) { assertTrue(recovery.continuePendingObservation()) }
        assertEquals(before, plan)
        assertEquals(writes, saves)
        assertEquals(1, recovery.stableSamples())
        assertEquals(RecoveryTransactionState.VERIFYING, manager.snapshot("ACCOUNT_BROWSE")?.state)
        recovery.observePage(unknown.copy(reason = "actual_window_changed"))
        assertEquals(0, recovery.stableSamples())
        assertFalse(recovery.observeFingerprint("feed-b"))
        assertTrue(recovery.observeFingerprint("feed-b"))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
        assertFalse(recovery.continuePendingObservation())
        assertEquals(null, manager.snapshot("ACCOUNT_BROWSE"))
    }

    @Test
    fun actualTouchEdgesReachTheSameRecoveryPlanWithoutCreatingAnotherTransaction() {
        var plan: RecoveryScheduleSnapshot? = null
        val repository = object : RecoveryScheduleRepository {
            override fun load() = plan
            override fun save(snapshot: RecoveryScheduleSnapshot) { plan = snapshot }
            override fun clear() { plan = null }
        }
        val recovery = DynamicPageRecovery(noOpScheduler, { "com.immomo.momo" }, { 7L },
            scheduleRepository = repository)
        recovery.observePage(BrowsePageEvidence("", "", BrowsePageKind.UNKNOWN, "", 0, "touch"))
        recovery.onUserInteractionStarted()
        assertEquals(true, plan?.touchActive)
        assertEquals(5, plan?.backoffStage)
        val duringTouch = plan
        repeat(100) { assertTrue(recovery.continuePendingObservation()) }
        assertEquals(duringTouch, plan)
        recovery.onUserInteractionEnded()
        assertEquals(false, plan?.touchActive)
        assertEquals(0, plan?.backoffStage)
        assertEquals(RecoveryTransactionState.RECOVERING, recovery.state())
    }

    @Test
    fun target_mismatch_is_not_authorized_for_dynamic_recovery() {
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 1L }
        )

        assertEquals(
            RecoveryTransactionState.RECOVERING,
            recovery.observePage(
                BrowsePageEvidence(
                    packageName = "other.package",
                    activityClass = "OtherActivity",
                    pageKind = BrowsePageKind.DYNAMIC_FEED,
                    fingerprint = "other-feed",
                    confidence = 100,
                    reason = "wrong_target"
                )
            )
        )
    }

    @Test
    fun processRestartRestoresOnlyRecoveryObservationAndRequiresFreshDoubleSample() {
        var plan: RecoveryScheduleSnapshot? = RecoveryScheduleSnapshot(
            taskId = "ACCOUNT_BROWSE",
            routeId = "account-browse",
            runGeneration = 0L,
            recoveryReason = "UNKNOWN_PAGE",
            checkpointId = "account-browse-checkpoint",
            observationCount = 6L,
            nextObservationAtEpochMs = 0L,
            backoffStage = 5,
            touchActive = false
        )
        val repository = object : RecoveryScheduleRepository {
            override fun load(): RecoveryScheduleSnapshot? = plan
            override fun save(snapshot: RecoveryScheduleSnapshot) { plan = snapshot }
            override fun clear() { plan = null }
        }
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 3L },
            scheduleRepository = repository
        )
        recovery.restoreFromCheckpoint(100L)
        assertEquals(RecoveryTransactionState.RECOVERING, recovery.state())
        assertEquals(3L, plan?.runGeneration)
        assertEquals(6L, plan?.observationCount)
        assertFalse(recovery.observeFingerprint("fresh-feed"))
        assertTrue(recovery.observeFingerprint("fresh-feed"))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
        assertEquals(null, plan)
    }

    @Test
    fun managedInitialFeedStabilityStillRequiresTwoFreshSamples() {
        val manager = UnifiedRecoveryTransactionManager({ 7L })
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 7L },
            sharedManager = manager
        )

        assertFalse(recovery.observeFingerprint("fresh-feed"))
        assertEquals(RecoveryTransactionState.VERIFYING, recovery.state())
        assertEquals(RecoveryTransactionState.VERIFYING,
            manager.snapshot("ACCOUNT_BROWSE")?.state)
        assertTrue(recovery.observeFingerprint("fresh-feed"))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
        assertEquals(null, manager.snapshot("ACCOUNT_BROWSE"))
    }

    @Test
    fun managedUnknownPageAndInterruptedFingerprintsCannotBypassProcessGate() {
        val manager = UnifiedRecoveryTransactionManager({ 11L })
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 11L },
            sharedManager = manager
        )
        val unknown = BrowsePageEvidence(
            packageName = "com.immomo.momo",
            activityClass = "FeedActivity",
            pageKind = BrowsePageKind.UNKNOWN,
            fingerprint = "",
            confidence = 0,
            reason = "unconfirmed_page"
        )
        assertEquals(RecoveryTransactionState.RECOVERING, recovery.observePage(unknown))
        assertFalse(recovery.observeFingerprint("feed-v1"))
        assertEquals(RecoveryTransactionState.VERIFYING, manager.snapshot("ACCOUNT_BROWSE")?.state)
        recovery.observePage(unknown.copy(reason = "overlay_changed"))
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot("ACCOUNT_BROWSE")?.state)
        assertFalse(recovery.observeFingerprint(""))
        assertFalse(recovery.observeFingerprint("feed-v2"))
        assertTrue(recovery.observeFingerprint("feed-v2"))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
        assertEquals(null, manager.snapshot("ACCOUNT_BROWSE"))
    }

    @Test
    fun restoredBrowseCheckpointUsesIndependentReducerLaneUntilBothSamplesConfirm() {
        val manager = UnifiedRecoveryTransactionManager({ 7L })
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 7L },
            sharedManager = manager
        )
        val page = BrowsePageEvidence(
            packageName = "com.immomo.momo",
            activityClass = "FeedActivity",
            pageKind = BrowsePageKind.DYNAMIC_FEED,
            fingerprint = "fresh-feed",
            confidence = 90,
            reason = "verified_feed"
        )
        val checkpoint = RecoveryCheckpoint(
            checkpointId = "account-browse-checkpoint",
            taskId = "ACCOUNT_BROWSE",
            routeId = "account-browse",
            runGeneration = 7L,
            executionPhase = "WAIT_FOR_FEED_STABLE",
            targetPackage = "com.immomo.momo",
            pageType = BrowsePageKind.DYNAMIC_FEED.name,
            pageFingerprint = "fresh-feed",
            targetIdentityFingerprint = null,
            businessCursor = null,
            completedRecordKeys = emptySet(),
            recoveryReason = "PROCESS_RESTART",
            savedAtEpochMs = 100L
        )
        recovery.restoreFromCheckpoint(100L)
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot("ACCOUNT_BROWSE")?.state)
        assertFalse(recovery.observeRestoredCheckpoint(checkpoint, page))
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot("ACCOUNT_BROWSE")?.state)
        assertEquals(RecoveryTransactionState.VERIFYING,
            manager.snapshot("ACCOUNT_BROWSE", "ACCOUNT_BROWSE_CHECKPOINT")?.state)
        assertTrue(recovery.observeRestoredCheckpoint(checkpoint, page))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
        assertEquals(null, manager.snapshot("ACCOUNT_BROWSE"))
        assertEquals(null, manager.snapshot("ACCOUNT_BROWSE", "ACCOUNT_BROWSE_CHECKPOINT"))
    }

    @Test
    fun changedFreshFeedStartsNewManagedCheckpointVerificationFromZeroSamples() {
        val manager = UnifiedRecoveryTransactionManager({ 7L })
        val recovery = DynamicPageRecovery(
            scheduler = noOpScheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 7L },
            sharedManager = manager
        )
        val page = BrowsePageEvidence(
            packageName = "com.immomo.momo",
            activityClass = "FeedActivity",
            pageKind = BrowsePageKind.DYNAMIC_FEED,
            fingerprint = "feed-v1",
            confidence = 90,
            reason = "verified"
        )
        val checkpoint = RecoveryCheckpoint(
            checkpointId = "account-browse-checkpoint",
            taskId = "ACCOUNT_BROWSE",
            routeId = "account-browse",
            runGeneration = 7L,
            executionPhase = "WAIT_FOR_FEED_STABLE",
            targetPackage = "com.immomo.momo",
            pageType = BrowsePageKind.DYNAMIC_FEED.name,
            pageFingerprint = "feed-v1",
            targetIdentityFingerprint = null,
            businessCursor = null,
            completedRecordKeys = emptySet(),
            recoveryReason = "PROCESS_RESTART",
            savedAtEpochMs = 100L
        )
        recovery.restoreFromCheckpoint(100L)
        assertFalse(recovery.observeRestoredCheckpoint(checkpoint, page))
        assertEquals(RecoveryTransactionState.VERIFYING,
            manager.snapshot("ACCOUNT_BROWSE", "ACCOUNT_BROWSE_CHECKPOINT")?.state)
        assertFalse(recovery.observeRestoredCheckpoint(
            checkpoint.copy(pageFingerprint = "feed-v2"),
            page.copy(fingerprint = "feed-v2")
        ))
        assertEquals(RecoveryTransactionState.RECOVERING,
            manager.snapshot("ACCOUNT_BROWSE")?.state)
        assertEquals(RecoveryTransactionState.VERIFYING,
            manager.snapshot("ACCOUNT_BROWSE", "ACCOUNT_BROWSE_CHECKPOINT")?.state)
        assertTrue(recovery.observeRestoredCheckpoint(
            checkpoint.copy(pageFingerprint = "feed-v2"),
            page.copy(fingerprint = "feed-v2")
        ))
        assertEquals(RecoveryTransactionState.ACTIVE, recovery.state())
    }

    private val noOpScheduler = object : TaskScheduler {
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = ScheduledTask { }
    }
}
