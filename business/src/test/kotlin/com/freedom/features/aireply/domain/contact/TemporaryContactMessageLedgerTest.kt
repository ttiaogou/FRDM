package com.freedom.features.aireply.domain.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMessageLedgerTest {
    @Test
    fun countsOnlyDistinctConfirmedSendEventsPerContact() {
        val ledger = ContactMessageLedger(defaultInterceptionThreshold = 3)
        val first = ledger.recordSuccessfulSend("Contact A", "send-event-1")
        val duplicate = ledger.recordSuccessfulSend("Contact A", "send-event-1")
        val second = ledger.recordSuccessfulSend("Contact A", "send-event-2")
        val otherContact = ledger.recordSuccessfulSend("Contact B", "send-event-1")

        assertEquals(ContactSendRecordStatus.RECORDED, first.status)
        assertEquals(1, first.count)
        assertEquals(ContactSendRecordStatus.DUPLICATE, duplicate.status)
        assertEquals(1, duplicate.count)
        assertEquals(2, second.count)
        assertEquals(1, otherContact.count)
        assertEquals(2, ledger.successfulSendCount("Contact A"))
        assertEquals(1, ledger.successfulSendCount("Contact B"))
    }

    @Test
    fun thresholdPausesContactAndCleanupWaitsForConfirmedInterception() {
        val ledger = ContactMessageLedger(defaultInterceptionThreshold = 2)
        ledger.recordSuccessfulSend("Contact A", "send-event-1")
        val reached = ledger.recordSuccessfulSend("Contact A", "send-event-2")

        assertTrue(reached.thresholdReachedNow)
        assertTrue(reached.shouldPauseContact)
        assertEquals(2, reached.count)
        assertFalse(ledger.confirmInterceptionEnabled("Contact B", ledger.followUpCheckpoint("Contact B")))
        assertTrue(ledger.confirmInterceptionEnabled("Contact A", ledger.followUpCheckpoint("Contact A")))
        assertEquals(2, ledger.successfulSendCount("Contact A"))
        assertFalse(ledger.confirmInterceptionEnabled("Contact A", ledger.followUpCheckpoint("Contact A")))
    }

    @Test
    fun contactCannotAccumulateMoreSendsAfterThresholdUntilInterception() {
        val ledger = ContactMessageLedger(defaultInterceptionThreshold = 1)
        ledger.recordSuccessfulSend("Contact A", "send-event-1")
        val next = ledger.recordSuccessfulSend("Contact A", "send-event-2")

        assertEquals(ContactSendRecordStatus.CONTACT_LIMIT_REACHED, next.status)
        assertEquals(1, next.count)
        assertTrue(next.shouldPauseContact)
    }

    @Test
    fun blankInputsAreRejectedAndRunResetClearsAllContacts() {
        val ledger = ContactMessageLedger()
        val invalid = ledger.recordSuccessfulSend(" ", "send-event-1")
        assertEquals(ContactSendRecordStatus.INVALID_INPUT, invalid.status)

        ledger.recordSuccessfulSend("Contact A", "send-event-1")
        ledger.recordSuccessfulSend("Contact B", "send-event-2")
        ledger.clearAll()

        assertEquals(0, ledger.successfulSendCount("Contact A"))
        assertEquals(0, ledger.successfulSendCount("Contact B"))
    }

    @Test
    fun zeroThresholdsDisableActionsAndImageThresholdIsReportedOnce() {
        val ledger = ContactMessageLedger()

        val disabled = ledger.recordSuccessfulSend("Contact A", "send-event-1", 0, 0)
        assertFalse(disabled.thresholdReachedNow)
        assertFalse(disabled.imageThresholdReachedNow)

        val imageReached = ledger.recordSuccessfulSend("Contact B", "send-event-1", 3, 1)
        val next = ledger.recordSuccessfulSend("Contact B", "send-event-2", 3, 1)
        assertTrue(imageReached.imageThresholdReachedNow)
        assertFalse(next.imageThresholdReachedNow)
        assertFalse(imageReached.thresholdReachedNow)
    }

    @Test
    fun confirmedCountsAndPendingActionSurviveLedgerRecreation() {
        val repository = InMemoryContactMessageLedgerRepository()
        ContactMessageLedger(repository).recordSuccessfulSend("Contact A", "send-event-1", 3, 1)

        val restored = ContactMessageLedger(repository)

        assertEquals(1, restored.successfulSendCount("Contact A"))
        assertEquals(ContactPendingRecoveryAction.SEND_IMAGE, restored.pendingRecoveryAction("Contact A"))
    }

    @Test
    fun interceptionConfirmationIsPersistedInsteadOfDeletingTheContactRecord() {
        val repository = InMemoryContactMessageLedgerRepository()
        val ledger = ContactMessageLedger(repository)
        ledger.recordSuccessfulSend("Contact A", "send-event-1", 1, 0)
        assertTrue(ledger.confirmInterceptionEnabled("Contact A", ledger.followUpCheckpoint("Contact A")))

        val restored = ContactMessageLedger(repository)

        assertEquals(1, restored.successfulSendCount("Contact A"))
        assertEquals(ContactPendingRecoveryAction.NONE, restored.pendingRecoveryAction("Contact A"))
    }
    @Test
    fun equalThresholdRequiresConfirmedImageBeforeInterceptionAcrossRestart() {
        val repository = InMemoryContactMessageLedgerRepository()
        val ledger = ContactMessageLedger(repository)
        ledger.recordSuccessfulSend("Contact A", "event-1", 2, 2)
        val due = ledger.recordSuccessfulSend("Contact A", "event-2", 2, 2)
        assertTrue(due.thresholdReachedNow)
        assertTrue(due.imageThresholdReachedNow)
        assertEquals(ContactPendingRecoveryAction.SEND_IMAGE, ledger.pendingRecoveryAction("Contact A"))
        assertFalse(ledger.confirmInterceptionEnabled("Contact A", ledger.followUpCheckpoint("Contact A")))
        assertFalse(ledger.confirmImageSent("Contact B", ledger.followUpCheckpoint("Contact B")))

        val restored = ContactMessageLedger(repository)
        assertTrue(restored.confirmImageSent("Contact A", restored.followUpCheckpoint("Contact A")))
        assertFalse(restored.confirmImageSent("Contact A", restored.followUpCheckpoint("Contact A")))
        assertEquals(2, restored.successfulSendCount("Contact A"))
        assertEquals(ContactPendingRecoveryAction.ENABLE_INTERCEPTION, restored.pendingRecoveryAction("Contact A"))

        val afterImage = ContactMessageLedger(repository)
        assertTrue(afterImage.confirmInterceptionEnabled("Contact A", afterImage.followUpCheckpoint("Contact A")))
        assertEquals(ContactPendingRecoveryAction.NONE, afterImage.pendingRecoveryAction("Contact A"))
        assertEquals(2, afterImage.successfulSendCount("Contact A"))
    }

    @Test
    fun zeroImageThresholdDoesNotCreateAnImageConfirmation() {
        val ledger = ContactMessageLedger()
        ledger.recordSuccessfulSend("Contact A", "event-1", 1, 0)
        assertFalse(ledger.confirmImageSent("Contact A", ledger.followUpCheckpoint("Contact A")))
        assertEquals(ContactPendingRecoveryAction.ENABLE_INTERCEPTION, ledger.pendingRecoveryAction("Contact A"))
        assertTrue(ledger.confirmInterceptionEnabled("Contact A", ledger.followUpCheckpoint("Contact A")))
    }

    @Test
    fun checkpointRecreationRetainsUncertainEffectAndRejectsStaleOrWrongContact() {
        val repository = InMemoryContactMessageLedgerRepository()
        val ledger = ContactMessageLedger(repository)
        ledger.recordSuccessfulSend("Contact A", "event-1", 1, 1)
        ledger.recordSuccessfulSend("Contact B", "event-1", 1, 1)
        val initial = requireNotNull(ledger.followUpCheckpoint("Contact A"))
        assertEquals(ContactFollowUpStep.IMAGE_OPEN_ALBUM, initial.step)
        assertFalse(ledger.updateFollowUpCheckpoint("Contact B", initial, ContactFollowUpStep.IMAGE_SEND, true))
        assertFalse(ledger.updateFollowUpCheckpoint("Contact A", initial, ContactFollowUpStep.INTERCEPTION_SWITCH, true))
        assertTrue(ledger.updateFollowUpCheckpoint("Contact A", initial, ContactFollowUpStep.IMAGE_SEND, true))

        val restored = ContactMessageLedger(repository)
        val unresolved = requireNotNull(restored.followUpCheckpoint("Contact A"))
        assertEquals(ContactFollowUpStep.IMAGE_SEND, unresolved.step)
        assertTrue(unresolved.reconciliationRequired)
        assertEquals(initial.revision + 1, unresolved.revision)
        assertFalse(restored.confirmImageSent("Contact A", initial))
        assertFalse(restored.confirmImageSent("Contact B", unresolved))
        assertTrue(restored.confirmImageSent("Contact A", unresolved))
        val next = requireNotNull(restored.followUpCheckpoint("Contact A"))
        assertEquals(ContactFollowUpStep.INTERCEPTION_OPEN_SETTINGS, next.step)
        assertFalse(restored.confirmInterceptionEnabled("Contact A", unresolved))
        assertTrue(restored.confirmInterceptionEnabled("Contact A", next))
        assertEquals(ContactPendingRecoveryAction.SEND_IMAGE, restored.pendingRecoveryAction("Contact B"))
    }

    @Test
    fun failedSaveDoesNotPublishCountOrCheckpointProgress() {
        val delegate = InMemoryContactMessageLedgerRepository()
        var fail = true
        val repository = object : ContactMessageLedgerRepository by delegate {
            override fun save(entry: ContactMessageLedgerEntry) {
                check(!fail)
                delegate.save(entry)
            }
        }
        val ledger = ContactMessageLedger(repository)
        assertEquals(ContactSendRecordStatus.PERSISTENCE_FAILED,
            ledger.recordSuccessfulSend("Contact A", "event-1", 1, 1).status)
        assertEquals(0, ledger.successfulSendCount("Contact A"))
        assertEquals(null, ledger.followUpCheckpoint("Contact A"))
        fail = false
        assertEquals(ContactSendRecordStatus.RECORDED,
            ledger.recordSuccessfulSend("Contact A", "event-1", 1, 1).status)
        val checkpoint = requireNotNull(ledger.followUpCheckpoint("Contact A"))
        fail = true
        assertFalse(ledger.updateFollowUpCheckpoint("Contact A", checkpoint, ContactFollowUpStep.IMAGE_SEND, true))
        assertFalse(ledger.confirmImageSent("Contact A", checkpoint))
        assertEquals(checkpoint, ledger.followUpCheckpoint("Contact A"))
        assertEquals(checkpoint, ContactMessageLedger(repository).followUpCheckpoint("Contact A"))
    }

}
