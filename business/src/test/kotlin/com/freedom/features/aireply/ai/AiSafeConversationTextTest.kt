package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.*
import org.junit.Assert.*
import org.junit.Test

class AiSafeConversationTextTest {
    private val line = OcrContentRect(1, 2, 80, 25)

    @Test fun oldVisualMarkersAreNeverWashedIntoVerifiedHistory() {
        OcrUncertaintyMarkers.all.forEach { marker ->
            assertNull(AiSafeConversationText.legacy("你好${marker}还没睡吗"))
            assertNull(AiSafeConversationText.legacy("${marker} 在吗a"))
        }
    }

    @Test fun unicodeEmojiIsRemovedWithoutDiscardingIndependentText() {
        assertEquals("你好还没睡吗", AiSafeConversationText.legacy("你好😊还没睡吗"))
        assertEquals("明天见", AiSafeConversationText.legacy("明天见👩‍💻"))
        assertEquals("8点见", AiSafeConversationText.legacy("8点见1️⃣"))
        assertEquals("睡不着鸭", AiSafeConversationText.legacy("睡不着鸭"))
    }

    @Test fun pureEmojiSymbolsAndStrayJoinersDoNotBecomeSafeText() {
        for (text in listOf("😊", "❤️", "1️⃣", ")(", "?!", "👩‍💻", "😂👍", " \u200D你好")) {
            assertNull(text, AiSafeConversationText.legacy(text))
        }
    }

    @Test fun outboundSanitizesEveryMessageAndRetainsRolesAndOrder() {
        val messages = listOf(
            AiModelMessage(AiModelMessage.Role.USER, "早上好😊"),
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "你好呀"),
            AiModelMessage(AiModelMessage.Role.USER, "在吗${OcrUncertaintyMarkers.UNKNOWN_VISUAL_SYMBOL}"),
            AiModelMessage(AiModelMessage.Role.USER, "👩‍💻"),
            AiModelMessage(AiModelMessage.Role.USER, "明天见")
        )
        val safe = AiSafeConversationText.outbound(messages)
        assertEquals(listOf("早上好", "你好呀", "明天见"), safe.map { it.content })
        assertEquals(listOf(AiModelMessage.Role.USER, AiModelMessage.Role.ASSISTANT,
            AiModelMessage.Role.USER), safe.map { it.role })
    }

    private fun msg(raw: String, quality: MessageContentQuality, row: Int = 3): ChatMessage = ChatMessage(
        id = "same-logical-id", direction = ChatMessageDirection.INCOMING, type = ChatMessageType.TEXT,
        content = raw, bounds = ScreenRect(0, 10, 100, 30),
        directionEvidence = DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
        contentQuality = quality, logicalMessageId = "same-logical-id",
        observationRowIndex = row
    )

    private fun sample(raw: String, tokens: List<OcrContentToken>, projection: OcrTextProjection,
        quality: MessageContentQuality = MessageContentQuality.AMBIGUOUS
    ): Pair<ChatMessageBatch, ChatMessage> {
        val message = msg(raw, quality)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "same-window", listOf(message),
            1, 1, false, 1L,
            ocrTokensByRowIndex = mapOf(3 to tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection))
        return batch to message
    }

    @Test fun independentlyAdmittedLineSurvivesASeparateUnknownVisualLine() {
        val marker = OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY
        val raw = "今晚有空吗\n${marker} 在吗a"
        val tokens = listOf(
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.RECOGNIZED, "今晚有空吗",
                OcrTokenSource.OCR_LINE, 0, line),
            OcrContentToken(OcrContentKind.PLACEHOLDER, OcrTokenStatus.UNVERIFIED, marker,
                OcrTokenSource.VISUAL_GUARD, 1, OcrContentRect(1, 40, 80, 60)),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.AMBIGUOUS, " 在吗a",
                OcrTokenSource.OCR_LINE, 1, OcrContentRect(1, 40, 80, 60))
        )
        val projection = OcrTextProjection("今晚有空吗", OcrTextProjectionStatus.PARTIAL,
            listOf(0), listOf(1))
        val (batch, message) = sample(raw, tokens, projection)
        assertFalse(message.isAiInputEligible)
        assertEquals("今晚有空吗", AiSafeConversationText.visible(batch, message))
        assertEquals(raw, message.content)
        assertEquals("same-logical-id", message.stableLogicalMessageId)
        assertEquals("今晚有空吗", VisibleDraftContext.build(batch)!!.messages.single().content)
    }

    @Test fun tamperedProjectionCannotBeUsedAsAShortcutAroundOcrEvidence() {
        val raw = "你好😊还没睡"
        val tokens = listOf(
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.RECOGNIZED, "你好",
                OcrTokenSource.OCR_LINE, 0, line),
            OcrContentToken(OcrContentKind.EMOJI, OcrTokenStatus.UNVERIFIED, "😊",
                OcrTokenSource.UNICODE_EMOJI, 0, line),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.RECOGNIZED, "还没睡",
                OcrTokenSource.OCR_LINE, 0, line)
        )
        val projection = OcrTextProjection("你好还没睡", OcrTextProjectionStatus.PARTIAL,
            listOf(0), emptyList())
        val (batch, message) = sample(raw, tokens, projection, MessageContentQuality.VERIFIED)
        assertEquals("你好还没睡", AiSafeConversationText.visible(batch, message))
        assertNull(AiSafeConversationText.visible(batch.copy(
            ocrTextProjectionsByRowIndex = mapOf(3 to projection.copy(text = "不可信"))
        ), message))
        assertNull(AiSafeConversationText.visible(batch.copy(
            ocrTokensByRowIndex = mapOf(3 to tokens.reversed())
        ), message))
        assertNull(AiSafeConversationText.visible(batch, message.copy(observationRowIndex = 4)))
        assertNull(AiSafeConversationText.visible(batch, message.copy(content = "不是这条消息")))
        assertNull(AiSafeConversationText.visible(batch.copy(
            ocrTextProjectionsByRowIndex = emptyMap()
        ), message))
    }

    @Test fun sharedProjectionRuleRejectsSameLineUncertaintyEvenWithPermissiveTextPolicy() {
        val marker = OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY
        val tokens = listOf(
            OcrContentToken(OcrContentKind.PLACEHOLDER, OcrTokenStatus.UNVERIFIED, marker,
                OcrTokenSource.VISUAL_GUARD, 0, line, OcrContentRect(70, 2, 80, 25)),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.AMBIGUOUS, " 哈哈，我也是，牛马生活",
                OcrTokenSource.OCR_LINE, 0, line)
        )
        val raw = tokens.joinToString("") { it.text }
        assertNull(OcrTextProjection.derive(raw, tokens, { it }, { it }))
        // Even a forged RECOGNIZED flag cannot replace missing independent evidence.
        val changed = tokens.map { it.copy(status = if (it.kind == OcrContentKind.TEXT)
            OcrTokenStatus.RECOGNIZED else it.status) }
        assertNull(OcrTextProjection.derive(raw, changed, { it }, { it }))
    }

    @Test fun sharedProjectionStillEnforcesLineGeometryAndTokenOrder() {
        val tokens = listOf(
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.RECOGNIZED, "你好",
                OcrTokenSource.OCR_LINE, 0, line),
            OcrContentToken(OcrContentKind.EMOJI, OcrTokenStatus.UNVERIFIED, "😊",
                OcrTokenSource.UNICODE_EMOJI, 0, line)
        )
        val expected = OcrTextProjection("你好", OcrTextProjectionStatus.PARTIAL, listOf(0), emptyList())
        assertEquals(expected, OcrTextProjection.derive("你好😊", tokens, { it }, { it.takeIf(String::isNotBlank) }))
        assertNull(OcrTextProjection.derive("你好😊", tokens.reversed(), { it }, { it }))
        assertNull(OcrTextProjection.derive("你好😊", tokens.map { it.copy(lineBounds = null) }, { it }, { it }))
    }

    @Test fun isolatedEdgeEvidenceAdmitsOnlyMatchingIndependentTextAndPreservesRawQuality() {
        val marker = OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY
        val lineBounds = OcrContentRect(0, 0, 392, 59)
        val region = OcrContentRect(355, 14, 391, 48)
        val proof = OcrIsolatedTextEvidence(OcrContentRect(0, 0, 353, 59),
            OcrContentRect(1, 16, 350, 47), "哈哈，我也是，牛马生活", 0.95f)
        val tokens = listOf(
            OcrContentToken(OcrContentKind.PLACEHOLDER, OcrTokenStatus.UNVERIFIED, marker,
                OcrTokenSource.VISUAL_GUARD, 0, lineBounds, region),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.AMBIGUOUS, " 哈哈，我也是，牛马生活",
                OcrTokenSource.OCR_LINE, 0, lineBounds, isolatedTextEvidence = proof)
        )
        val raw = tokens.joinToString("") { it.text }
        val projection = OcrTextProjection("哈哈，我也是，牛马生活", OcrTextProjectionStatus.PARTIAL,
            listOf(0), emptyList())
        val (batch, message) = sample(raw, tokens, projection)
        assertEquals(projection.text, AiSafeConversationText.visible(batch, message))
        assertEquals(projection.text, VisibleDraftContext.build(batch)!!.messages.single().content)
        assertEquals(MessageContentQuality.AMBIGUOUS, message.contentQuality)
        assertFalse(message.isAiInputEligible)
        assertEquals(raw, message.content)
        assertNull(AiSafeConversationText.legacy(raw))
        for (invalid in listOf(
            proof.copy(recognizedText = "错误文字"),
            proof.copy(confidence = 0.5f),
            proof.copy(cropBounds = OcrContentRect(0, 0, 390, 59)),
            proof.copy(recognizedBounds = OcrContentRect(1, 16, 355, 47))
        )) {
            val bad = tokens.map { if (it.kind == OcrContentKind.TEXT)
                it.copy(isolatedTextEvidence = invalid) else it }
            assertNull(AiSafeConversationText.visible(batch.copy(ocrTokensByRowIndex = mapOf(3 to bad)), message))
        }
        assertNull(AiSafeConversationText.visible(batch.copy(ocrTokensByRowIndex = mapOf(3 to
            tokens.map { it.copy(isolatedTextEvidence = null) })), message))
        assertNull(AiSafeConversationText.visible(batch, message.copy(direction = ChatMessageDirection.UNKNOWN)))
        assertFalse(proof.toString().contains(proof.recognizedText))
    }

    @Test fun edgeEmojiWithIndependentProofAdmitsOnlyVerifiedTextToDraft() {
        val lineBounds = OcrContentRect(0, 0, 145, 59)
        val region = OcrContentRect(108, 15, 142, 47)
        val proof = OcrIsolatedTextEvidence(OcrContentRect(0, 0, 106, 59),
            OcrContentRect(1, 16, 94, 46), "怎么说", 0.95f)
        val tokens = listOf(
            OcrContentToken(OcrContentKind.PLACEHOLDER, OcrTokenStatus.UNVERIFIED,
                OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY, OcrTokenSource.VISUAL_GUARD, 0, lineBounds, region),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.AMBIGUOUS, " 怎么说",
                OcrTokenSource.OCR_LINE, 0, lineBounds, isolatedTextEvidence = proof),
            OcrContentToken(OcrContentKind.EMOJI, OcrTokenStatus.UNVERIFIED, "😌",
                OcrTokenSource.UNICODE_EMOJI, 0, lineBounds)
        )
        val projection = OcrTextProjection("怎么说", OcrTextProjectionStatus.PARTIAL,
            listOf(0), emptyList())
        val raw = tokens.joinToString("") { it.text }
        val (batch, message) = sample(raw, tokens, projection)
        assertEquals("怎么说", AiSafeConversationText.visible(batch, message))
        assertEquals("怎么说", VisibleDraftContext.build(batch)!!.messages.single().content)
        assertEquals(raw, message.content)
        assertEquals(MessageContentQuality.AMBIGUOUS, message.contentQuality)
        assertFalse(message.isAiInputEligible)
        assertNull(AiSafeConversationText.legacy(raw))

        val marker = tokens[0]
        val text = tokens[1]
        val emoji = tokens[2]
        val rejected = listOf(
            listOf(marker, text.copy(isolatedTextEvidence = null), emoji),
            listOf(marker, text.copy(isolatedTextEvidence = proof.copy(recognizedText = "错误")), emoji),
            listOf(marker, text.copy(isolatedTextEvidence = proof.copy(confidence = 0.79f)), emoji),
            listOf(marker, text.copy(isolatedTextEvidence = proof.copy(cropBounds = lineBounds)), emoji),
            listOf(marker, text, emoji.copy(status = OcrTokenStatus.RECOGNIZED)),
            listOf(marker, text, emoji.copy(lineBounds = region)),
            listOf(marker, text, emoji.copy(isolatedTextEvidence = proof)),
            listOf(marker, emoji, text),
            listOf(marker, text, emoji, emoji),
            listOf(marker.copy(symbolBounds = OcrContentRect(55, 15, 89, 47)), text, emoji)
        )
        rejected.forEach { bad ->
            val badRaw = bad.joinToString("") { it.text }
            val (badBatch, badMessage) = sample(badRaw, bad, projection)
            assertNull(AiSafeConversationText.visible(badBatch, badMessage))
            assertNull(VisibleDraftContext.build(badBatch))
        }
    }

    @Test fun missingProjectionNeverUpgradesAmbiguousContent() {
        val marker = OcrUncertaintyMarkers.UNKNOWN_VISUAL_SYMBOL
        val raw = "你好${marker}"
        val message = msg(raw, MessageContentQuality.AMBIGUOUS)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "window", listOf(message), 1, 1, false, 1L)
        assertNull(AiSafeConversationText.visible(batch, message))
    }

    @Test fun aVerifiedTextWithNoOcrEvidenceUsesSafeLegacyPolicy() {
        val message = msg("你好😊", MessageContentQuality.VERIFIED)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "window", listOf(message), 1, 1, false, 1L)
        assertEquals("你好", AiSafeConversationText.visible(batch, message))
    }
}
