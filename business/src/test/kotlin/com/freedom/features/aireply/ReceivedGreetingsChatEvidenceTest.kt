package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageNodeEvidence
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsChatEvidenceTest {
    private fun detail(
        packageName: String = "com.immomo.momo",
        pageKind: ChatPageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE,
        title: String? = "与某人对话",
        messageWindowId: Int = 21,
        inputWindowId: Int = 21,
        messageGeneration: Long = 7L,
        inputGeneration: Long = 7L,
        screenBounds: ScreenRect? = null,
        structureEvidence: List<ChatPageNodeEvidence> = emptyList()
    ): ChatPageSnapshot {
        val messageWindow = WindowRef(packageName, "ChatActivity", messageGeneration, messageWindowId)
        val inputWindow = WindowRef(packageName, "ChatActivity", inputGeneration, inputWindowId)
        return ChatPageSnapshot(
            packageName = packageName,
            activityClass = "ChatActivity",
            pageKind = pageKind,
            scannedNodeCount = 80,
            editableInputCount = 1,
            messageListCount = 1,
            sendActionCount = 0,
            messageRelatedViewIdCount = 1,
            hasChatTab = true,
            messageListTarget = NodeTarget(messageWindow, NodePath(listOf(1, 2))),
            inputFieldTarget = NodeTarget(inputWindow, NodePath(listOf(1, 3))),
            screenBounds = screenBounds,
            structureEvidence = structureEvidence,
            chatTitleText = title,
            windowGeneration = messageGeneration
        )
    }

    private fun node(
        path: String = "1/2",
        left: Int = -20,
        top: Int = 10,
        right: Int = -1,
        bottom: Int = 30,
        boundsValid: Boolean = true
    ) = ChatPageNodeEvidence(
        path = path,
        className = "android.widget.FrameLayout",
        viewId = "message_list",
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        childCount = 0,
        isEditable = false,
        isScrollable = true,
        isClickable = false,
        hasText = false,
        insideListCandidate = false,
        boundsValid = boundsValid
    )

    @Test
    fun headerComparisonKeepsTheExistingExactAndWrappedTitleRules() {
        assertTrue(matchesReceivedGreetingsChatHeader(detail(), "某人"))
        assertTrue(matchesReceivedGreetingsChatHeader(detail(title = "某人"), "某人"))
        assertFalse(matchesReceivedGreetingsChatHeader(detail(title = "与另一个人对话"), "某人"))
        assertFalse(matchesReceivedGreetingsChatHeader(detail(), null))
    }

    @Test
    fun verifiedWindowEvidenceRequiresExpectedPackagePageKindAndSameSessionWindow() {
        val snapshot = detail()
        val observed = collectReceivedGreetingsChatWindowEvidence(snapshot)
        val verified = verifyReceivedGreetingsChatWindowEvidence(snapshot, "com.immomo.momo", observed)

        assertNotNull(verified)
        assertEquals(snapshot.conversationIdentityKey(), verified?.chatTitleKey)
        assertEquals(windowSessionKey(snapshot), verified?.windowSessionKey)
        assertNull(
            verifyReceivedGreetingsChatWindowEvidence(snapshot, "other.package", observed)
        )
        assertNull(
            verifyReceivedGreetingsChatWindowEvidence(
                snapshot.copy(pageKind = ChatPageKind.NON_CHAT), "com.immomo.momo", observed
            )
        )
    }

    @Test
    fun mismatchedOrMissingWindowAndConversationEvidenceNeverVerifies() {
        val mismatchedWindow = detail(inputGeneration = 8L)
        val mismatchEvidence = collectReceivedGreetingsChatWindowEvidence(mismatchedWindow)
        assertNull(
            verifyReceivedGreetingsChatWindowEvidence(
                mismatchedWindow, "com.immomo.momo", mismatchEvidence
            )
        )

        val invalidWindow = detail(messageWindowId = -1, inputWindowId = -1)
        val invalidEvidence = collectReceivedGreetingsChatWindowEvidence(invalidWindow)
        assertNull(
            verifyReceivedGreetingsChatWindowEvidence(invalidWindow, "com.immomo.momo", invalidEvidence)
        )

        val invalidTitle = detail(title = "陌陌首页")
        val titleEvidence = collectReceivedGreetingsChatWindowEvidence(invalidTitle)
        assertNull(
            verifyReceivedGreetingsChatWindowEvidence(invalidTitle, "com.immomo.momo", titleEvidence)
        )
    }

    @Test
    fun offscreenDeferralRequiresAValidTargetPathAndExplicitOutsideDisplayBounds() {
        val snapshot = detail(
            screenBounds = ScreenRect(0, 0, 100, 100),
            structureEvidence = listOf(node())
        )
        assertTrue(hasOffscreenGreetingChatLocators(snapshot))
        assertFalse(hasOffscreenGreetingChatLocators(snapshot.copy(screenBounds = null)))
        assertFalse(hasOffscreenGreetingChatLocators(snapshot.copy(structureEvidence = listOf(node(path = "9/9")))))
        assertFalse(hasOffscreenGreetingChatLocators(snapshot.copy(structureEvidence = listOf(node(boundsValid = false)))))
        assertFalse(
            hasOffscreenGreetingChatLocators(
                snapshot.copy(structureEvidence = listOf(node(left = 10, top = 10, right = 90, bottom = 30)))
            )
        )
    }
}
