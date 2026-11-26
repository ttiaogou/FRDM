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
        assertFalse(ledger.confirmInterceptionEnabled("Contact B"))
        assertTrue(ledger.confirmInterceptionEnabled("Contact A"))
        assertEquals(2, ledger.successfulSendCount("Contact A"))
        assertFalse(ledger.confirmInterceptionEnabled("Contact A"))
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
        assertTrue(ledger.confirmInterceptionEnabled("Contact A"))

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
        assertFalse(ledger.confirmInterceptionEnabled("Contact A"))
        assertFalse(ledger.confirmImageSent("Contact B"))

        val restored = ContactMessageLedger(repository)
        assertTrue(restored.confirmImageSent("Contact A"))
        assertFalse(restored.confirmImageSent("Contact A"))
        assertEquals(2, restored.successfulSendCount("Contact A"))
        assertEquals(ContactPendingRecoveryAction.ENABLE_INTERCEPTION, restored.pendingRecoveryAction("Contact A"))

        val afterImage = ContactMessageLedger(repository)
        assertTrue(afterImage.confirmInterceptionEnabled("Contact A"))
        assertEquals(ContactPendingRecoveryAction.NONE, afterImage.pendingRecoveryAction("Contact A"))
        assertEquals(2, afterImage.successfulSendCount("Contact A"))
    }

    @Test
    fun zeroImageThresholdDoesNotCreateAnImageConfirmation() {
        val ledger = ContactMessageLedger()
        ledger.recordSuccessfulSend("Contact A", "event-1", 1, 0)
        assertFalse(ledger.confirmImageSent("Contact A"))
        assertEquals(ContactPendingRecoveryAction.ENABLE_INTERCEPTION, ledger.pendingRecoveryAction("Contact A"))
        assertTrue(ledger.confirmInterceptionEnabled("Contact A"))
    }
}
