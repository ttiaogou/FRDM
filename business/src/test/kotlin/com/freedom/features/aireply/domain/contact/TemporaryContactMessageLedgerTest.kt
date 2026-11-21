package com.freedom.features.aireply.domain.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryContactMessageLedgerTest {
    @Test
    fun countsOnlyDistinctConfirmedSendEventsPerContact() {
        val ledger = TemporaryContactMessageLedger(defaultInterceptionThreshold = 3)
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
        val ledger = TemporaryContactMessageLedger(defaultInterceptionThreshold = 2)
        ledger.recordSuccessfulSend("Contact A", "send-event-1")
        val reached = ledger.recordSuccessfulSend("Contact A", "send-event-2")

        assertTrue(reached.thresholdReachedNow)
        assertTrue(reached.shouldPauseContact)
        assertEquals(2, reached.count)
        assertFalse(ledger.confirmInterceptionEnabled("Contact B"))
        assertTrue(ledger.confirmInterceptionEnabled("Contact A"))
        assertEquals(0, ledger.successfulSendCount("Contact A"))
        assertFalse(ledger.confirmInterceptionEnabled("Contact A"))
    }

    @Test
    fun contactCannotAccumulateMoreSendsAfterThresholdUntilInterception() {
        val ledger = TemporaryContactMessageLedger(defaultInterceptionThreshold = 1)
        ledger.recordSuccessfulSend("Contact A", "send-event-1")
        val next = ledger.recordSuccessfulSend("Contact A", "send-event-2")

        assertEquals(ContactSendRecordStatus.CONTACT_LIMIT_REACHED, next.status)
        assertEquals(1, next.count)
        assertTrue(next.shouldPauseContact)
    }

    @Test
    fun blankInputsAreRejectedAndRunResetClearsAllContacts() {
        val ledger = TemporaryContactMessageLedger()
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
        val ledger = TemporaryContactMessageLedger()

        val disabled = ledger.recordSuccessfulSend("Contact A", "send-event-1", 0, 0)
        assertFalse(disabled.thresholdReachedNow)
        assertFalse(disabled.imageThresholdReachedNow)

        val imageReached = ledger.recordSuccessfulSend("Contact B", "send-event-1", 3, 1)
        val next = ledger.recordSuccessfulSend("Contact B", "send-event-2", 3, 1)
        assertTrue(imageReached.imageThresholdReachedNow)
        assertFalse(next.imageThresholdReachedNow)
        assertFalse(imageReached.thresholdReachedNow)
    }
}
