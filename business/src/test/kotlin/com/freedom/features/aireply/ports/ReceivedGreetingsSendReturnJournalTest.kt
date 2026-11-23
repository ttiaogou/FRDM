package com.freedom.features.aireply.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsSendReturnJournalTest {
    private class FakeRepository : ReceivedGreetingsSendReturnRepository {
        val entries = LinkedHashMap<String, ReceivedGreetingsSendReturnRecord>()
        var writes = 0
        var rejectWrite = false

        override fun loadAll() = entries.values.toList()
        override fun save(record: ReceivedGreetingsSendReturnRecord): Boolean {
            if (rejectWrite) return false
            writes++
            entries[record.rowKeyHash] = record
            return true
        }
    }

    @Test
    fun confirmedSendSurvivesJournalRecreationAndIsNotEquivalentToSafeReturn() {
        val repo = FakeRepository()
        val original = ReceivedGreetingsSendReturnJournal(repo)
        assertTrue(original.recordConfirmedSend("row-hash", "contact-hash", "send-hash"))
        val reopened = ReceivedGreetingsSendReturnJournal(repo)
        assertEquals(ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED, reopened.findByRow("row-hash")?.phase)
        assertTrue(reopened.hasConfirmedSendForContact("contact-hash"))
        assertEquals(1, reopened.pendingReturns().size)
        assertFalse(reopened.recordConfirmedSend("row-hash", "contact-hash", "another-send"))
        assertFalse(reopened.confirmVerifiedReturn("row-hash", "another-contact"))
        assertTrue(reopened.confirmVerifiedReturn("row-hash", "contact-hash"))
        assertEquals(ReceivedGreetingsSendReturnPhase.RETURN_VERIFIED, reopened.findByRow("row-hash")?.phase)
        assertTrue(reopened.pendingReturns().isEmpty())
        assertTrue(reopened.hasConfirmedSendForContact("contact-hash"))
        assertTrue(reopened.confirmVerifiedReturn("row-hash", "contact-hash"))
        assertFalse(reopened.recordConfirmedSend("row-hash", "contact-hash", "send-hash"))
        assertEquals(2, repo.writes)
    }

    @Test
    fun persistenceFailureNeverAuthorizesReturnOrLosesPendingSend() {
        val repo = FakeRepository().apply { rejectWrite = true }
        val journal = ReceivedGreetingsSendReturnJournal(repo)
        assertFalse(journal.recordConfirmedSend("row-hash", "contact-hash", "send-hash"))
        assertFalse(journal.confirmVerifiedReturn("row-hash", "contact-hash"))
        repo.rejectWrite = false
        assertTrue(journal.recordConfirmedSend("row-hash", "contact-hash", "send-hash"))
        repo.rejectWrite = true
        assertFalse(journal.confirmVerifiedReturn("row-hash", "contact-hash"))
        assertEquals(ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED, journal.findByRow("row-hash")?.phase)
    }
}
