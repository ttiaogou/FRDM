package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.recovery.RecoveryTransactionState
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

    private val noOpScheduler = object : TaskScheduler {
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = ScheduledTask { }
    }
}
