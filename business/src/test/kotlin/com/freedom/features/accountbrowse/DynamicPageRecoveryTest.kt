package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.recovery.RecoveryTransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicPageRecoveryTest {
    @Test
    fun unknown_page_requires_two_stable_fingerprints_before_resume() {
        val recovery = DynamicPageRecovery(
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 7L }
        )

        assertEquals(
            RecoveryTransactionState.VERIFYING,
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
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 1L }
        )

        assertEquals(
            RecoveryTransactionState.VERIFYING,
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
}
