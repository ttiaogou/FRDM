package com.freedom.features.recovery

import com.freedom.features.accountbrowse.AccountBrowseRecoveryCheckpointAdapter
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.aireply.PrivateMessagesRecoveryCheckpointAdapter
import com.freedom.features.aireply.ReceivedGreetingsRecoveryCheckpointAdapter
import com.freedom.features.aireply.ports.MomoSayHiDeferredRowCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCheckpointAdaptersTest {
    private fun context(
        pageType: String,
        executionPhase: String? = null,
        fingerprint: String = "verified-page-fingerprint"
    ) = RecoveryCheckpointAdapterContext(
        checkpointId = "checkpoint-1",
        runGeneration = 17L,
        targetPackage = "com.immomo.momo",
        pageType = pageType,
        pageFingerprint = fingerprint,
        recoveryReason = "PAGE_UNKNOWN",
        targetIdentityFingerprint = "verified-identity-hash",
        executionPhase = executionPhase
    )

    @Test
    fun browseMapsPhaseAndSwipeCursorWithoutCopyingPageOrGestureState() {
        val original = AccountBrowseCheckpoint(
            phase = "WAIT_FOR_LIST_RETURN",
            sourceFingerprint = "old-source",
            detailFingerprint = "old-detail",
            lastStableFingerprint = "old-list",
            sourceSnapshot = "business-private-snapshot",
            currentActivityClass = "MomoActivity",
            imageBrowserActive = true,
            swipeCount = 4,
            feedRefreshRecoveryActive = true,
            feedRefreshLastSnapshot = "private-refresh",
            savedAtEpochMs = 100L
        )
        val generic = AccountBrowseRecoveryCheckpointAdapter()
            .adapt(original, context("DYNAMIC_FEED"))

        assertEquals("ACCOUNT_BROWSE", generic.taskId)
        assertEquals("account-browse", generic.routeId)
        assertEquals("WAIT_FOR_LIST_RETURN", generic.executionPhase)
        assertEquals("4", generic.businessCursor)
        assertEquals("verified-page-fingerprint", generic.pageFingerprint)
        assertEquals(17L, generic.runGeneration)
        assertEquals(100L, generic.savedAtEpochMs)
        assertTrue(generic.completedRecordKeys.isEmpty())
        assertFalse(generic.toString().contains("business-private-snapshot"))
        assertEquals("business-private-snapshot", original.sourceSnapshot)
    }

    @Test
    fun messageListMapsOnlyExplicitCursorAndDoesNotTreatAttemptsAsCompleted() {
        val original = PrivateMessagesTraversalCheckpoint(
            attemptedSessionKeys = setOf("attempted-hash"),
            lockedUnreadSessionKeys = setOf("locked-hash"),
            ambiguousSessionIdentityKeys = setOf("ambiguous-hash"),
            lastStableVisibleKeys = listOf("viewport-private-data"),
            phase = "SEEKING_UNREAD_BOUNDARY",
            atTop = false,
            initialInboxPositionCheckCompleted = true,
            initialInboxTopPositioningCompleted = false,
            initialUnreadGateSwipeCompleted = false,
            lockedUnreadSetFinalized = true,
            normalInboxHadUnread = true,
            normalInboxUnreadStateUnknown = false,
            savedAtEpochMs = 200L,
            resumeSessionKey = "resume-key",
            resumeSessionName = "private-contact-name",
            runGeneration = 2L,
            lastSuccessfulTaskId = "previous-task",
            nextCursorKey = "next-cursor-hash"
        )
        val generic = PrivateMessagesRecoveryCheckpointAdapter()
            .adapt(original, context("CHAT_LIST"))

        assertEquals("AI_REPLY_PRIVATE_MESSAGES", generic.taskId)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", generic.routeId)
        assertEquals("SEEKING_UNREAD_BOUNDARY", generic.executionPhase)
        assertEquals("next-cursor-hash", generic.businessCursor)
        assertEquals(17L, generic.runGeneration)
        assertEquals("verified-identity-hash", generic.targetIdentityFingerprint)
        assertTrue(generic.completedRecordKeys.isEmpty())
        assertFalse(generic.toString().contains("private-contact-name"))
        assertFalse(generic.toString().contains("viewport-private-data"))
        assertEquals(setOf("attempted-hash"), original.attemptedSessionKeys)
        assertEquals("resume-key", original.resumeSessionKey)
    }

    @Test
    fun greetingsMapsProcessedRowsButKeepsDeferredStateInOriginalCheckpoint() {
        val original = MomoSayHiTraversalCheckpointRecord(
            processedRowKeys = linkedSetOf("row-hash-1", "row-hash-2"),
            processedContactIdHashes = setOf("contact-hash"),
            deferredRows = mapOf(
                "deferred-row" to MomoSayHiDeferredRowCheckpointRecord(
                    attempts = 3,
                    retryAfterEpochMs = 2000L
                )
            ),
            deferredReturnToTopActive = true,
            deferredReplayStarted = false,
            savedAtEpochMs = 300L
        )
        val generic = ReceivedGreetingsRecoveryCheckpointAdapter()
            .adapt(original, context("NON_CHAT", executionPhase = "AWAITING_LIST_RECHECK"))

        assertEquals("AI_REPLY_RECEIVED_GREETINGS", generic.taskId)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", generic.routeId)
        assertEquals("AWAITING_LIST_RECHECK", generic.executionPhase)
        assertEquals(setOf("row-hash-1", "row-hash-2"), generic.completedRecordKeys)
        assertNull(generic.businessCursor)
        assertFalse(generic.completedRecordKeys.contains("contact-hash"))
        assertFalse(generic.toString().contains("deferred-row"))
        assertEquals(3, original.deferredRows.getValue("deferred-row").attempts)
    }

    @Test
    fun missingPageEvidenceAndWrongBusinessPageCannotProduceACheckpoint() {
        assertFails {
            context("DYNAMIC_FEED", fingerprint = "")
        }
        val browse = AccountBrowseRecoveryCheckpointAdapter()
        assertFails {
            browse.adapt(browseCheckpoint(), context("CHAT_LIST"))
        }
        val messageList = PrivateMessagesRecoveryCheckpointAdapter()
        assertFails {
            messageList.adapt(messageCheckpoint(), context("DYNAMIC_FEED"))
        }
        val greetings = ReceivedGreetingsRecoveryCheckpointAdapter()
        assertFails {
            greetings.adapt(greetingsCheckpoint(), context("CHAT_LIST", executionPhase = "READY"))
        }
    }

    @Test
    fun greetingsCannotInferAnExecutionPhaseFromDeferralsOrFlags() {
        val adapter = ReceivedGreetingsRecoveryCheckpointAdapter()
        assertFails {
            adapter.adapt(greetingsCheckpoint(), context("NON_CHAT"))
        }
        assertFails {
            adapter.adapt(greetingsCheckpoint(), context("NON_CHAT", executionPhase = " "))
        }
    }

    private fun browseCheckpoint() = AccountBrowseCheckpoint(
        phase = "WAIT_FOR_FEED_STABLE",
        sourceFingerprint = "",
        detailFingerprint = "",
        lastStableFingerprint = "",
        sourceSnapshot = "",
        currentActivityClass = "",
        imageBrowserActive = false,
        swipeCount = 0,
        feedRefreshRecoveryActive = false,
        feedRefreshLastSnapshot = "",
        savedAtEpochMs = 100L
    )

    private fun messageCheckpoint() = PrivateMessagesTraversalCheckpoint(
        attemptedSessionKeys = emptySet(),
        lockedUnreadSessionKeys = emptySet(),
        ambiguousSessionIdentityKeys = emptySet(),
        lastStableVisibleKeys = emptyList(),
        phase = "POSITIONING_AT_BOTTOM",
        atTop = false,
        initialInboxPositionCheckCompleted = false,
        initialInboxTopPositioningCompleted = false,
        initialUnreadGateSwipeCompleted = false,
        lockedUnreadSetFinalized = false,
        normalInboxHadUnread = false,
        normalInboxUnreadStateUnknown = false,
        savedAtEpochMs = 100L
    )

    private fun greetingsCheckpoint() = MomoSayHiTraversalCheckpointRecord(
        processedRowKeys = emptySet(),
        processedContactIdHashes = emptySet(),
        deferredRows = emptyMap(),
        deferredReturnToTopActive = true,
        deferredReplayStarted = true,
        savedAtEpochMs = 100L
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Invalid mapping must be rejected", failed)
    }
}
