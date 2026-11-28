package com.freedom.features.conversation.domain.contact

import com.freedom.features.aireply.ai.VerifiedDraftHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** An abandoned OCR preload must never become another verified contact's AI history. */
class R09dDraftHistoryIsolationTest {
    private fun identity(id: String) =
        ContactRowIdentity("snapshot", "temporary", id, ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
    private fun history(id: String) =
        VerifiedDraftHistory("", id, "momo-direct-$id", emptyList(), "LOCAL_PLATFORM_CONTACT")

    @Test
    fun abandonedPreloadCannotBeConsumedByDifferentContactEvenWithSameTitle() {
        val a = identity("contact-a")
        val b = identity("contact-b")
        val historyA = history("contact-a")
        val historyB = history("contact-b")
        val reads = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val preload = ContactHistoryResolver.DraftHistoryPreload(
            { id, _ -> reads.add(id.contactId); if (id.contactId == a.contactId) historyA else historyB },
            { logs.add(it) }
        )
        preload.prepare(a, "same-title")
        assertSame(historyB, preload.consume(b, "same-title"))
        assertEquals(listOf(a.contactId, b.contactId), reads)
        assertSame(historyA, preload.consume(a, "same-title"))
        assertEquals(listOf(a.contactId, b.contactId, a.contactId), reads)
        assertTrue(logs.any { it.contains("预加载联系人已变化") })
        assertTrue(logs.none { it.contains("contact-a") || it.contains("contact-b") })
    }

    @Test
    fun mismatchedPreloadIsClearedEvenWhenFreshHistoryReadFails() {
        val a = identity("contact-a")
        val b = identity("contact-b")
        val historyA = history("contact-a")
        var reads = 0
        val preload = ContactHistoryResolver.DraftHistoryPreload(
            { id, _ ->
                reads++
                if (id.contactId == b.contactId) throw IllegalStateException("repository unavailable")
                historyA
            }, {}
        )
        preload.prepare(a, null)
        assertNull(preload.consume(b, null))
        assertEquals(2, reads)
        assertSame(historyA, preload.consume(a, null))
        assertEquals(3, reads)
    }

    @Test
    fun matchingVerifiedContactRetainsSingleUsePreloadSemantics() {
        val a = identity("contact-a")
        val prepared = history("contact-a")
        val fallback = history("contact-a")
        var reads = 0
        val preload = ContactHistoryResolver.DraftHistoryPreload(
            { _, _ -> if (++reads == 1) prepared else fallback }, {}
        )
        preload.prepare(a, "title")
        assertSame(prepared, preload.consume(a, "title"))
        assertEquals(1, reads)
        assertSame(fallback, preload.consume(a, "title"))
        assertEquals(2, reads)
    }
}
