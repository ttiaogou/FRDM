package com.freedom.features.conversation.domain.contact

import com.freedom.features.aireply.ai.VerifiedDraftHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class R08DraftHistoryPreloadTest {
    private val identity = ContactRowIdentity("s", "t", "c1", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
    private fun history() = VerifiedDraftHistory("", "c1", "momo-direct-c1", emptyList(), "LOCAL_PLATFORM_CONTACT")

    @Test
    fun preloadIsConsumedOnceWithoutReadingRepositoryAgain() {
        val prepared = history()
        val fallback = history()
        var reads = 0
        val log = mutableListOf<String>()
        val preload = ContactHistoryResolver.DraftHistoryPreload(
            { id, title ->
                assertEquals(identity, id)
                assertEquals("title", title)
                if (++reads == 1) prepared else fallback
            }, { log.add(it) }
        )
        preload.clear()
        preload.prepare(identity, "title")
        assertSame(prepared, preload.consume(identity, "title"))
        assertEquals(1, reads)
        assertSame(fallback, preload.consume(identity, "title"))
        assertEquals(2, reads)
        assertTrue(log.any { it.contains("不重复查询") })
    }

    @Test
    fun clearAndUnverifiedIdentityDoNotCarryPrivateHistoryIntoAnotherVisit() {
        val prepared = history()
        val fallback = history()
        var reads = 0
        val preload = ContactHistoryResolver.DraftHistoryPreload(
            { _, _ -> if (++reads == 1) prepared else fallback }, {}
        )
        preload.prepare(identity, null)
        assertNull(preload.consume(null, null))
        assertEquals(1, reads)
        assertSame(fallback, preload.consume(identity, null))
        preload.prepare(identity, null)
        preload.clear()
        assertSame(fallback, preload.consume(identity, null))
        assertEquals(4, reads)
    }

    @Test
    fun separateRuntimeSlotsAndFailedReadFallbackAreIndependent() {
        val prepared = history()
        val one = ContactHistoryResolver.DraftHistoryPreload({ _, _ -> prepared }, {})
        val log = mutableListOf<String>()
        val two = ContactHistoryResolver.DraftHistoryPreload(
            { _, _ -> throw IllegalStateException("private error detail") }, { log.add(it) }
        )
        one.prepare(identity, null)
        assertNull(two.consume(identity, null))
        assertSame(prepared, one.consume(identity, null))
        assertTrue(log.single().contains("CONTACT_HISTORY_READ_FAILED"))
        assertTrue(log.none { it.contains("private error detail") })
    }

    @Test(expected = IllegalStateException::class)
    fun preloadFailureRetainsExistingPropagationSemantics() {
        val preload = ContactHistoryResolver.DraftHistoryPreload(
            { _, _ -> throw IllegalStateException("read failed") }, {}
        )
        preload.clear()
        preload.prepare(identity, null)
    }
}
