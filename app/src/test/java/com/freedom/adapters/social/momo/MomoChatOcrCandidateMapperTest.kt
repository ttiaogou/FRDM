package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.application.message.ChatMessageCandidate
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.DirectionEvidence
import com.freedom.features.aireply.domain.message.ChatMessageOcrTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoChatOcrCandidateMapperTest {
    private val listBounds = ScreenRect(0, 293, 720, 1_359)
    private val incomingTarget = ChatMessageOcrTarget(
        rowIndex = 1,
        rowBounds = ScreenRect(0, 434, 720, 602),
        bodyBounds = ScreenRect(140, 472, 455, 515),
        bubbleBounds = ScreenRect(140, 434, 455, 553),
        avatarBounds = ScreenRect(15, 434, 105, 524)
    )
    private val outgoingTarget = ChatMessageOcrTarget(
        rowIndex = 2,
        rowBounds = ScreenRect(0, 620, 720, 760),
        bodyBounds = ScreenRect(400, 650, 620, 710),
        bubbleBounds = ScreenRect(390, 635, 650, 730),
        avatarBounds = ScreenRect(655, 640, 705, 690)
    )

    @Test
    fun structuralAndOcrObservationsShareLogicalIdentity() {
        val target = incomingTarget.copy(
            timeHint = "21:03",
            structureSignature = "root=message_root;nodes=0:message_root,0.1:leftcontainer",
            neighborSignature = "prev|current|next",
            identityDirectionHint = ChatMessageDirection.INCOMING
        )
        val structural = ChatMessageCandidate(
            rowIndex = target.rowIndex,
            rowBounds = target.rowBounds,
            bubbleBounds = target.bubbleBounds,
            listBounds = listBounds,
            text = "旧识别",
            typeHint = com.freedom.features.aireply.domain.message.ChatMessageType.TEXT,
            explicitDirectionHint = ChatMessageDirection.INCOMING,
            explicitDirectionEvidence = DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
            timeHint = target.timeHint,
            avatarBounds = target.avatarBounds,
            neighborSignature = target.neighborSignature,
            structureSignature = target.structureSignature,
            identityDirectionHint = target.identityDirectionHint,
            identityBubbleBounds = target.bubbleBounds,
            identityAvatarBounds = target.avatarBounds
        )
        val structuralMessage = ChatMessageParser().parse(listOf(structural)).single()
        val ocrMessage = MomoChatOcrCandidateMapper.parse(
            listOf(target), mapOf(target.rowIndex to "新识别"), listBounds, ChatMessageParser(), 10
        ).single()

        assertEquals(structuralMessage.logicalMessageId, ocrMessage.logicalMessageId)
        assertEquals("旧识别", structuralMessage.content)
        assertEquals("新识别", ocrMessage.content)
    }

    @Test
    fun longIncomingBubbleUsesExistingAvatarGeometry() {
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(incomingTarget),
            mapOf(1 to "嗨，晚上好呀，还没休息😊"),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(1, messages.size)
        assertEquals(ChatMessageDirection.INCOMING, messages.single().direction)
    }

    @Test
    fun outgoingBubbleUsesRightAvatarWithoutChangingDirectionRules() {
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(outgoingTarget),
            mapOf(2 to "hi"),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(1, messages.size)
        assertEquals(ChatMessageDirection.OUTGOING, messages.single().direction)
    }


    @Test
    fun validatedLayoutDirectionIsPreservedThroughOcrFallback() {
        val structuralIncoming = incomingTarget.copy(
            // The geometry is deliberately placed on the right: validated layout evidence wins.
            bubbleBounds = ScreenRect(390, 434, 690, 553),
            explicitDirectionHint = ChatMessageDirection.INCOMING,
            explicitDirectionEvidence = DirectionEvidence.LAYOUT_ID_AND_HIERARCHY
        )
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(structuralIncoming),
            mapOf(1 to "来自无障碍树旁路识别的消息"),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(ChatMessageDirection.INCOMING, messages.single().direction)
        assertEquals(
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
            messages.single().directionEvidence
        )
    }

    @Test
    fun uncertainEmojiMarkerIsPreservedButNotEligibleForAi() {
        val text = "嗨打个招呼${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}你这么晚也没睡"
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(incomingTarget),
            mapOf(1 to text),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(1, messages.size)
        assertEquals(text, messages.single().content)
        assertEquals(MessageContentQuality.AMBIGUOUS, messages.single().contentQuality)
        assertFalse(messages.single().isAiInputEligible)
    }

    @Test
    fun singleEngineOcrTextIsEligibleForAiInputAfterStructuralDirectionValidation() {
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(incomingTarget),
            mapOf(1 to "睡不着呀"),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(1, messages.size)
        assertEquals(MessageContentQuality.VERIFIED, messages.single().contentQuality)
        assertTrue(messages.single().isAiInputEligible)
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "window",
            messages = messages,
            scannedRowCount = 1,
            candidateRowCount = 1,
            truncated = false,
            capturedAtMillis = 1L
        )
        assertEquals(1, batch.aiInputMessages.size)
        assertEquals("睡不着呀", batch.latestIncomingTextMessage?.content)
        assertEquals("睡不着呀", batch.latestIncomingMessage?.content)
    }

    @Test
    fun processorQualityDoesNotBlockStructurallyAdmittedOcrText() {
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(incomingTarget),
            mapOf(1 to "睡不着呀"),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(MessageContentQuality.VERIFIED, messages.single().contentQuality)
        assertTrue(messages.single().isAiInputEligible)
    }

    @Test
    fun symbolOnlyOcrResultIsNotEligibleForAi() {
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(incomingTarget),
            mapOf(1 to ")("),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertEquals(1, messages.size)
        assertEquals(MessageContentQuality.AMBIGUOUS, messages.single().contentQuality)
        assertFalse(messages.single().isAiInputEligible)
    }

    @Test
    fun emptyOcrAndUiLabelsDoNotCreateMessages() {
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(incomingTarget, outgoingTarget),
            mapOf(1 to "", 2 to "关注"),
            listBounds,
            ChatMessageParser(),
            limit = 10
        )

        assertTrue(messages.isEmpty())
    }

    @Test
    fun temporaryOcrDebugPayloadIsExcludedFromBatchSummaryAndString() {
        val secret = "PRIVATE_OCR_DEBUG_SENTINEL"
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "com.immomo.momo|ChatActivity|1",
            messages = emptyList(),
            scannedRowCount = 1,
            candidateRowCount = 1,
            truncated = false,
            capturedAtMillis = 1L,
            ocrDebugTextByRowIndex = mapOf(1 to secret),
            ocrDebugChineseTextByRowIndex = mapOf(1 to secret),
            ocrDebugLatinTextByRowIndex = mapOf(1 to secret),
            ocrSampleTextByRowIndex = mapOf(1 to secret),
            ocrConfidenceByRowIndex = mapOf(1 to 0.98f),
            ocrLineCountByRowIndex = mapOf(1 to 1)
        )

        assertFalse(batch.summary().contains(secret))
        assertFalse(batch.toString().contains(secret))
    }

    @Test
    fun ocrTargetsAndResultStringNeverExposeRecognizedBody() {
        val result = MomoChatOcrResult(
            textByRowIndex = mapOf(1 to "PRIVATE_OCR_BODY_SENTINEL"),
            attemptedTargetCount = 1,
            failedTargetCount = 0
        )

        assertFalse(incomingTarget.toString().contains("PRIVATE_OCR_BODY_SENTINEL"))
        assertFalse(result.toString().contains("PRIVATE_OCR_BODY_SENTINEL"))
        assertTrue(result.toString().contains("REDACTED"))
    }

    @Test
    fun standaloneEmojiAndUncertainLineCannotEnterAiInputBatch() {
        val texts = listOf("😊", "${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a")
        texts.forEach { text ->
            val messages = MomoChatOcrCandidateMapper.parse(
                listOf(incomingTarget), mapOf(1 to text),
                listBounds, ChatMessageParser(), limit = 10
            )
            assertEquals(1, messages.size)
            assertEquals(text, messages.single().content)
            assertEquals(MessageContentQuality.AMBIGUOUS, messages.single().contentQuality)
            assertFalse(messages.single().isAiInputEligible)
            val batch = ChatMessageBatch(
                status = MessageReadStatus.SUCCESS,
                windowSessionKey = "window",
                messages = messages,
                scannedRowCount = 1,
                candidateRowCount = 1,
                truncated = false,
                capturedAtMillis = 1L
            )
            assertTrue(batch.aiInputMessages.isEmpty())
        }
    }
}
