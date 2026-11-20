package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AiReplyPageObservationSemanticsTest {
    private val semantics = AiReplyPageObservationSemantics()

    @Test
    fun emptyUnknownIsTemporaryUnavailable() {
        val decision = semantics.classify(snapshot(ChatPageKind.UNKNOWN), null, TARGET_PACKAGE)

        assertEquals(AiReplyPageObservationStatus.TEMPORARILY_UNAVAILABLE, decision.status)
    }

    @Test
    fun sameWindowUnknownIsTemporaryUnavailable() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, messageIds = 1)
        val incoming = snapshot(ChatPageKind.UNKNOWN, messageIds = 1)

        val decision = semantics.classify(incoming, previous, TARGET_PACKAGE)

        assertEquals(AiReplyPageObservationStatus.TEMPORARILY_UNAVAILABLE, decision.status)
    }

    @Test
    fun unknownWithDifferentWindowIsConfirmedUnknown() {
        val previous = snapshot(ChatPageKind.CHAT_LIST, generation = 8L, messageIds = 1)
        val incoming = snapshot(ChatPageKind.UNKNOWN, generation = 9L, messageIds = 1)

        val decision = semantics.classify(incoming, previous, TARGET_PACKAGE)

        assertEquals(AiReplyPageObservationStatus.CONFIRMED_UNKNOWN, decision.status)
    }

    @Test
    fun knownPageIsConfirmedPage() {
        val decision = semantics.classify(snapshot(ChatPageKind.CHAT_DETAIL_CANDIDATE), null, TARGET_PACKAGE)

        assertEquals(AiReplyPageObservationStatus.CONFIRMED_PAGE, decision.status)
    }

    private fun snapshot(
        kind: ChatPageKind,
        generation: Long = 8L,
        messageIds: Int = 0
    ) = ChatPageSnapshot(
        packageName = TARGET_PACKAGE,
        activityClass = "com.immomo.momo.maintab.MaintabActivity",
        pageKind = kind,
        scannedNodeCount = 0,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = messageIds,
        hasChatTab = false,
        messageListTarget = null,
        inputFieldTarget = null,
        windowGeneration = generation
    )

    private companion object {
        const val TARGET_PACKAGE = "com.immomo.momo"
    }
}
