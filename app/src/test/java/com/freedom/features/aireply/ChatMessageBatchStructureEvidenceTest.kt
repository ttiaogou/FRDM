package com.freedom.features.aireply

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageStructureEvidence
import com.freedom.features.aireply.domain.message.MessageReadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageBatchStructureEvidenceTest {

    @Test
    fun structuralEvidenceIsSummarizedWithoutMessagePayload() {
        val evidence = ChatMessageStructureEvidence(
            rowIndex = 2,
            rowBounds = ScreenRect(0, 400, 720, 520),
            bodyViewId = "com.immomo.momo:id/message_tv_layouttextview",
            bodyViewClass = "view",
            bubbleBounds = ScreenRect(96, 400, 282, 520),
            avatarBounds = ScreenRect(15, 405, 105, 495)
        )
        val batch = ChatMessageBatch(
            status = MessageReadStatus.MESSAGE_TEXT_NOT_EXPOSED,
            windowSessionKey = "session",
            messages = emptyList(),
            scannedRowCount = 1,
            candidateRowCount = 0,
            truncated = false,
            capturedAtMillis = 1L,
            unexposedMessageTextViewCount = 1,
            messageStructureEvidence = listOf(evidence)
        )

        assertTrue(batch.summary().contains("状态=MESSAGE_TEXT_NOT_EXPOSED"))
        assertTrue(batch.summary().contains("未暴露正文的消息 View=1"))
        assertTrue(batch.summary().contains("结构消息行=1"))
        assertTrue(evidence.toString().contains("bubbleBounds=ScreenRect"))
        assertFalse(evidence.toString().contains("消息正文"))
    }

    @Test
    fun unavailableListReasonIsIncludedInSummary() {
        val batch = ChatMessageBatch(
            status = MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
            windowSessionKey = null,
            messages = emptyList(),
            scannedRowCount = 0,
            candidateRowCount = 0,
            truncated = false,
            capturedAtMillis = 1L,
            messageListUnavailableReason = "保存的消息列表节点路径在本次窗口树中无法解析"
        )
        assertTrue(batch.summary().contains("列表不可用原因="))
        assertTrue(batch.summary().contains("无法解析"))
    }
}
