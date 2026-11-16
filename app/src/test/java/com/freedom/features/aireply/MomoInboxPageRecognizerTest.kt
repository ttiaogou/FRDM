package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoInboxPageRecognizerTest {
    private val recognizer = MomoInboxPageRecognizer()

    @Test
    fun mapsOnlyTheExistingChatPageKindsWithoutAddingFingerprintRules() {
        assertEquals(
            MomoInboxPageRecognizer.Page.MESSAGE_LIST,
            recognizer.recognize(snapshot(ChatPageKind.CHAT_LIST))
        )
        assertEquals(
            MomoInboxPageRecognizer.Page.CHAT_DETAIL,
            recognizer.recognize(snapshot(ChatPageKind.CHAT_DETAIL_CANDIDATE))
        )
        assertEquals(
            MomoInboxPageRecognizer.Page.NON_CHAT,
            recognizer.recognize(snapshot(ChatPageKind.NON_CHAT))
        )
        assertEquals(
            MomoInboxPageRecognizer.Page.UNKNOWN,
            recognizer.recognize(snapshot(ChatPageKind.UNKNOWN))
        )
    }

    @Test
    fun conveniencePredicatesMatchTheRecognizedPageOnly() {
        assertTrue(recognizer.isMessageList(snapshot(ChatPageKind.CHAT_LIST)))
        assertTrue(recognizer.isChatDetail(snapshot(ChatPageKind.CHAT_DETAIL_CANDIDATE)))
    }

    private fun snapshot(kind: ChatPageKind) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "MainActivity",
        pageKind = kind,
        scannedNodeCount = 0,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = false,
        messageListTarget = null,
        inputFieldTarget = null
    )
}
