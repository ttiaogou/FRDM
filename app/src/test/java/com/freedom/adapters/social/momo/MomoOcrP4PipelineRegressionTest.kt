package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.*
import org.junit.Assert.*
import org.junit.Test

/** P4 contract tests from raw OCR line evidence to parsed message plus P2 projection. */
class MomoOcrP4PipelineRegressionTest {
    private val listBounds = ScreenRect(0, 0, 720, 900)

    private fun target(row: Int = 3) = ChatMessageOcrTarget(
        rowIndex = row,
        rowBounds = ScreenRect(0, 200, 720, 335),
        bodyBounds = ScreenRect(140, 215, 450, 300),
        bubbleBounds = ScreenRect(125, 202, 470, 318),
        avatarBounds = ScreenRect(12, 205, 85, 280)
    )

    private fun line(text: String, top: Int = 0, symbols: List<OcrVisualSymbol> = emptyList()) =
        OcrVisualLine(text, OcrVisualRect(0, top, 200, top + 28), symbols)

    private fun map(text: String, target: ChatMessageOcrTarget = target()) =
        MomoChatOcrCandidateMapper.parse(
            listOf(target), mapOf(target.rowIndex to text), listBounds, ChatMessageParser(), 10
        ).single()

    @Test fun recognitionReuseSlotRetiresPreviousBatchAndLatePublications() {
        val slot = MomoChatMessageOcrProcessor.RecognitionReuseSlot<String>()
        val first = slot.begin()
        assertNull(first.previous)
        assertTrue(slot.publish(first, "first"))
        val second = slot.begin()
        assertEquals("first", second.previous)
        assertFalse(slot.publish(first, "stale-first"))
        // A failed/timed-out batch never publishes, and its consumed predecessor stays retired.
        val third = slot.begin()
        assertNull(third.previous)
        assertFalse(slot.publish(second, "late-second"))
        assertTrue(slot.publish(third, "third"))
        slot.invalidate()
        assertFalse(slot.publish(third, "late-third"))
        val fourth = slot.begin()
        assertNull(fourth.previous)
        slot.close()
        assertFalse(slot.publish(fourth, "after-close"))
        val afterClose = slot.begin()
        assertNull(afterClose.previous)
        assertFalse(slot.publish(afterClose, "closed"))
    }

    @Test fun previousRecognitionBatchRequiresCurrentCompleteSameWindowAndBoundedAge() {
        val session = "momo|1|13|conversation=42"
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, session, emptyList(), 1, 1, false,
            61_000L, ocrTargets = listOf(target()))
        fun eligible(current: ChatMessageBatch, previousSession: String = session, previousAt: Long = 1_000L) =
            MomoChatMessageOcrProcessor.canReusePreviousRecognitionBatch(current, previousSession, previousAt)
        assertTrue(eligible(batch))
        assertFalse(eligible(batch.copy(capturedAtMillis = 61_001L)))
        assertFalse(eligible(batch.copy(capturedAtMillis = 999L)))
        assertFalse(eligible(batch, previousSession = "momo|1|14|conversation=42"))
        assertFalse(eligible(batch, previousSession = "momo|1|13|conversation=99"))
        assertFalse(eligible(batch.copy(windowSessionKey = null)))
        assertFalse(eligible(batch.copy(windowSessionKey = "momo|1|13|conversation=unknown"),
            previousSession = "momo|1|13|conversation=unknown"))
        assertFalse(eligible(batch.copy(truncated = true)))
        assertFalse(eligible(batch.copy(coordinateSpaceReliable = false)))
        assertFalse(eligible(batch.copy(ocrTargets = emptyList())))
        assertFalse(eligible(batch.copy(ocrTargets = List(21) { target(it) })))
        assertTrue(MomoChatMessageOcrProcessor.canRetainRecognitionBatch(batch))
    }

    @Test fun sendProofReuseRequiresExactIncomingCropWindowAndStructure() {
        val incoming = target().copy(
            explicitDirectionHint = ChatMessageDirection.INCOMING,
            identityDirectionHint = ChatMessageDirection.INCOMING,
            structureSignature = "verified-row"
        )
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "momo|1|13|conversation=42",
            emptyList(), 1, 1, false, 1L)
        val png = byteArrayOf(1, 2, 3)
        val key = MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, incoming, png)
        assertNotNull(key)
        assertEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, incoming, png.copyOf()))
        assertNotEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, incoming, byteArrayOf(1, 2, 4)))
        assertNotEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(
            batch.copy(windowSessionKey = "momo|1|14|conversation=42"), incoming, png))
        assertNotEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch,
            incoming.copy(structureSignature = "different-row"), png))
        assertNull(MomoChatMessageOcrProcessor.incomingCropReuseKey(batch,
            incoming.copy(explicitDirectionHint = ChatMessageDirection.OUTGOING,
                identityDirectionHint = ChatMessageDirection.OUTGOING), png))
        assertNull(MomoChatMessageOcrProcessor.incomingCropReuseKey(batch,
            incoming.copy(identityDirectionHint = null), png))
        assertNull(MomoChatMessageOcrProcessor.incomingCropReuseKey(batch.copy(truncated = true), incoming, png))
        assertNull(MomoChatMessageOcrProcessor.incomingCropReuseKey(
            batch.copy(windowSessionKey = "momo|1|13|conversation=unknown"), incoming, png))
    }

    @Test fun incomingCropReuseAcrossVisibleRowsKeepsCurrentMessageIdentity() {
        val original = target(4).copy(
            explicitDirectionHint = ChatMessageDirection.INCOMING,
            identityDirectionHint = ChatMessageDirection.INCOMING,
            structureSignature = "verified-row",
            timeHint = "same-time"
        )
        val moved = original.copy(
            rowIndex = 3,
            rowBounds = ScreenRect(0, 40, 720, 175),
            bodyBounds = ScreenRect(140, 55, 450, 140),
            bubbleBounds = ScreenRect(125, 42, 470, 158),
            avatarBounds = ScreenRect(12, 45, 85, 120)
        )
        val another = original.copy(rowIndex = 5)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "momo|1|13|conversation=42",
            emptyList(), 1, 1, false, 1L)
        val png = byteArrayOf(1, 2, 3)
        val key = MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, original, png)
        assertNotNull(key)
        assertEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, moved, png))
        assertEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, another, png))
        assertNotEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch,
            moved.copy(timeHint = "changed-time"), png))
        assertNull(MomoChatMessageOcrProcessor.incomingCropReuseKey(
            batch.copy(coordinateSpaceReliable = false), moved, png))

        // One recognition value can populate two current rows, never an old message object.
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(moved, another), mapOf(moved.rowIndex to "你好", another.rowIndex to "你好"),
            listBounds, ChatMessageParser(), 10
        )
        assertEquals(2, messages.size)
        assertEquals(listOf(3, 5), messages.map { it.observationRowIndex })
        assertEquals(listOf(moved.rowBounds, another.rowBounds), messages.map { it.bounds })
        assertNotEquals(messages[0].stableLogicalMessageId, messages[1].stableLogicalMessageId)
    }

    @Test fun outgoingBaselineReuseRequiresConfirmedBatchAndExactPixelsAndStructure() {
        val outgoing = target().copy(
            explicitDirectionHint = ChatMessageDirection.OUTGOING,
            identityDirectionHint = ChatMessageDirection.OUTGOING,
            structureSignature = "verified-row",
            neighborSignature = "previous-row"
        )
        val batch = ChatMessageBatch(
            MessageReadStatus.SUCCESS, "momo|1|13|conversation=42",
            emptyList(), 1, 1, false, 1L,
            windowSnapshotSignature = "structure-one"
        )
        val png = byteArrayOf(1, 2, 3)
        val key = MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(batch, outgoing, png)
        assertNotNull(key)
        assertEquals(key, MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(batch, outgoing, png.copyOf()))
        assertNotEquals(key, MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch, outgoing.copy(rowIndex = outgoing.rowIndex + 1), png))
        assertNotEquals(key, MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(batch, outgoing, byteArrayOf(1, 2, 4)))
        assertNotEquals(key, MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch, outgoing.copy(neighborSignature = "changed-neighbor"), png))
        assertNotEquals(key, MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch, outgoing.copy(bodyBounds = ScreenRect(140, 216, 450, 301)), png))
        assertNotEquals(key, MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch.copy(windowSessionKey = "momo|1|14|conversation=42"), outgoing, png))
        assertNull(MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, outgoing, png))
        assertNull(MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch.copy(truncated = true), outgoing, png))
        assertNull(MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch.copy(windowSnapshotSignature = ""), outgoing, png))
        assertNull(MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(
            batch, outgoing.copy(identityDirectionHint = null), png))
        assertTrue(MomoChatMessageOcrProcessor.canReuseConfirmedOutgoingBaseline(
            "confirmed", "confirmed", "structure-one", "structure-one"))
        // Actual send proof always has a null authorization, even with identical pixels.
        assertFalse(MomoChatMessageOcrProcessor.canReuseConfirmedOutgoingBaseline(
            null, "confirmed", "structure-one", "structure-one"))
        assertFalse(MomoChatMessageOcrProcessor.canReuseConfirmedOutgoingBaseline(
            "confirmed", "another-batch", "structure-one", "structure-one"))
        assertFalse(MomoChatMessageOcrProcessor.canReuseConfirmedOutgoingBaseline(
            "confirmed", "confirmed", "structure-two", "structure-one"))
        assertFalse(MomoChatMessageOcrProcessor.canReuseConfirmedOutgoingBaseline(
            "confirmed", "confirmed", "", ""))
    }

    @Test fun incomingIsolatedTextReusePreservesAmbiguousQualityAndFullEvidence() {
        val rawLine = OcrVisualLine("哈哈，我也是，牛马生活", OcrVisualRect(0, 0, 392, 59), emptyList())
        val region = OcrVisualRect(355, 14, 391, 48)
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(rawLine), listOf(region))
        val bounds = OcrTextProjection.isolatedCropBounds(
            OcrContentRect(0, 0, 392, 59), OcrContentRect(355, 14, 391, 48), 392, 59
        )!!
        val proof = OcrIsolatedTextEvidence(bounds, OcrContentRect(1, 16, 350, 47), rawLine.text, 0.95f)
        val tokens = rendered.tokens.map {
            if (it.kind == OcrContentKind.TEXT) it.copy(isolatedTextEvidence = proof) else it
        }
        val projection = MomoOcrTextProjector.extract(rendered.text, tokens)!!
        val incoming = target().copy(
            explicitDirectionHint = ChatMessageDirection.INCOMING,
            identityDirectionHint = ChatMessageDirection.INCOMING,
            structureSignature = "verified-row"
        )
        val batch = ChatMessageBatch(
            MessageReadStatus.SUCCESS, "momo|1|13|conversation=42", emptyList(), 1, 1, false, 1L,
            windowSnapshotSignature = "structure-one"
        )
        val png = byteArrayOf(1, 2, 3)
        val key = MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, incoming, png)!!
        val moved = incoming.copy(rowIndex = 2)
        assertEquals(key, MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, moved, png))
        assertTrue(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, tokens, projection))
        val message = map(rendered.text, moved)
        assertEquals(MessageContentQuality.AMBIGUOUS, message.contentQuality)
        assertEquals(rendered.text, message.content)
        assertFalse(message.isAiInputEligible)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals(rawLine.text, projection.text)

        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, rendered.tokens, projection))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, tokens, null))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, tokens, projection.copy(text = "different")))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, tokens.map {
                if (it.kind == OcrContentKind.TEXT) it.copy(isolatedTextEvidence = proof.copy(confidence = 0.2f))
                else it
            }, projection))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, tokens.map {
                if (it.kind == OcrContentKind.TEXT) it.copy(isolatedTextEvidence = proof.copy(
                    recognizedBounds = OcrContentRect(355, 16, 390, 47)))
                else it
            }, projection))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 2, tokens, projection))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, "😊", 1, emptyList(), null))

        val outgoing = incoming.copy(
            explicitDirectionHint = ChatMessageDirection.OUTGOING,
            identityDirectionHint = ChatMessageDirection.OUTGOING
        )
        val outgoingKey = MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(batch, outgoing, png)!!
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            outgoingKey, rendered.text, 1, tokens, projection))
        // Preserve the original pure-text admission on both directions.
        assertTrue(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, "你好", 1, emptyList(), null))
        assertTrue(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            outgoingKey, "你好", 1, emptyList(), null))
    }

    @Test fun ocrRenderedEmojiAtVisualEdgeStillRequiresIndependentTextForReuse() {
        val rawLine = OcrVisualLine("怎么说😌", OcrVisualRect(0, 0, 145, 59), emptyList())
        val region = OcrVisualRect(108, 15, 142, 47)
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(rawLine), listOf(region))
        assertEquals(listOf(OcrContentKind.PLACEHOLDER, OcrContentKind.TEXT, OcrContentKind.EMOJI),
            rendered.tokens.map { it.kind })
        val text = OcrTextProjection.isolatedTextCandidate(rendered.tokens)!!
        assertEquals("怎么说", text.text.trim())
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens))
        val bounds = OcrTextProjection.isolatedCropBounds(
            text.lineBounds!!, OcrContentRect(108, 15, 142, 47), 145, 59
        )!!
        val proof = OcrIsolatedTextEvidence(bounds, OcrContentRect(1, 16, 94, 46), "怎么说", 0.95f)
        val tokens = rendered.tokens.map { if (it === text) it.copy(isolatedTextEvidence = proof) else it }
        val projection = MomoOcrTextProjector.extract(rendered.text, tokens)!!
        assertEquals("怎么说", projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals(MessageContentQuality.AMBIGUOUS, map(rendered.text).contentQuality)
        val incoming = target().copy(explicitDirectionHint = ChatMessageDirection.INCOMING,
            identityDirectionHint = ChatMessageDirection.INCOMING, structureSignature = "verified-row")
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "momo|1|13|conversation=42",
            emptyList(), 1, 1, false, 1L)
        val key = MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, incoming, byteArrayOf(1, 2, 3))!!
        assertTrue(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, tokens, projection))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(
            key, rendered.text, 1, rendered.tokens, projection))
        val middle = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(rawLine), listOf(OcrVisualRect(55, 15, 89, 47)))
        assertNull(OcrTextProjection.isolatedTextCandidate(middle.tokens))
        val split = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(rawLine.copy(text = "怎么😌说")), listOf(region))
        assertNull(OcrTextProjection.isolatedTextCandidate(split.tokens))
    }

    @Test fun groupedLeadingRegionsFromDeviceSampleRequireIndependentTextAndReuseProof() {
        val rawLine = OcrVisualLine("我请你", OcrVisualRect(0, 0, 176, 59), emptyList())
        val regions = listOf(OcrVisualRect(3, 14, 37, 49), OcrVisualRect(43, 14, 77, 49))
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(rawLine), regions)
        assertEquals("${OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY} 我请你", rendered.text)
        val marker = rendered.tokens.single { it.kind == OcrContentKind.PLACEHOLDER }
        assertEquals(OcrContentRect(3, 14, 77, 49), marker.symbolBounds)
        assertEquals(regions.map { OcrContentRect(it.left, it.top, it.right, it.bottom) }, marker.visualRegions)
        val text = OcrTextProjection.isolatedTextCandidate(rendered.tokens)!!
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens))
        val bounds = OcrTextProjection.isolatedCropBounds(text.lineBounds!!, marker.symbolBounds!!, 176, 59)!!
        assertEquals(OcrContentRect(79, 0, 176, 59), bounds)
        val proof = OcrIsolatedTextEvidence(bounds, OcrContentRect(81, 16, 173, 47), "我请你", 0.95f)
        val tokens = rendered.tokens.map { if (it === text) it.copy(isolatedTextEvidence = proof) else it }
        val projection = MomoOcrTextProjector.extract(rendered.text, tokens)!!
        assertEquals("我请你", projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals(MessageContentQuality.AMBIGUOUS, map(rendered.text).contentQuality)
        val incoming = target().copy(explicitDirectionHint = ChatMessageDirection.INCOMING,
            identityDirectionHint = ChatMessageDirection.INCOMING, structureSignature = "verified-row")
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS, "momo|1|13|conversation=42",
            emptyList(), 1, 1, false, 1L)
        val key = MomoChatMessageOcrProcessor.incomingCropReuseKey(batch, incoming, byteArrayOf(1, 2, 3))!!
        assertTrue(MomoChatMessageOcrProcessor.canCacheCropRecognition(key, rendered.text, 1, tokens, projection))
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(key, rendered.text, 1, rendered.tokens, projection))
        val outgoing = incoming.copy(explicitDirectionHint = ChatMessageDirection.OUTGOING,
            identityDirectionHint = ChatMessageDirection.OUTGOING)
        val outgoingKey = MomoChatMessageOcrProcessor.outgoingBaselineCropReuseKey(batch, outgoing, byteArrayOf(1, 2, 3))!!
        assertFalse(MomoChatMessageOcrProcessor.canCacheCropRecognition(outgoingKey, rendered.text, 1, tokens, projection))
        val unicode = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(rawLine.copy(text = "🤔🤔我请你")), regions)
        val unicodeText = OcrTextProjection.isolatedTextCandidate(unicode.tokens)!!
        assertEquals("我请你", unicodeText.text)
        assertEquals("我请你", MomoOcrTextProjector.extract(unicode.text, unicode.tokens.map {
            if (it === unicodeText) it.copy(isolatedTextEvidence = proof) else it
        })!!.text)
    }

    @Test fun groupedRegionsRejectNonContiguousMiddleBothEdgesAndSeparateLineEvidence() {
        val rawLine = OcrVisualLine("我请你", OcrVisualRect(0, 0, 176, 59), emptyList())
        val leading = OcrVisualRect(3, 14, 37, 49)
        val second = OcrVisualRect(43, 14, 77, 49)
        val trailing = listOf(OcrVisualRect(99, 14, 133, 49), OcrVisualRect(139, 14, 173, 49))
        val accepted = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(rawLine), trailing)
        assertEquals(OcrContentRect(99, 14, 173, 49), accepted.tokens.first().symbolBounds)
        assertNotNull(OcrTextProjection.isolatedTextCandidate(accepted.tokens))
        val rejected = listOf(
            listOf(leading, OcrVisualRect(90, 14, 124, 49)),
            listOf(leading, trailing.last()),
            listOf(OcrVisualRect(45, 14, 79, 49), OcrVisualRect(85, 14, 119, 49)),
            listOf(leading, OcrVisualRect(43, 44, 77, 58)),
            listOf(leading, second, OcrVisualRect(139, 14, 173, 49))
        )
        rejected.forEach { regions ->
            val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(rawLine), regions)
            assertNull(OcrTextProjection.isolatedTextCandidate(rendered.tokens))
            assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens))
        }
        val split = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(rawLine.copy(text = "我🤔请你")), listOf(leading, second))
        assertNull(OcrTextProjection.isolatedTextCandidate(split.tokens))
        val multiLine = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(rawLine, line("你好", 60)), listOf(leading, second))
        assertTrue(multiLine.tokens.all { it.visualRegions.isEmpty() })
    }

    @Test fun pureTextKeepsOriginalContentTypeDirectionAndCompleteProjection() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("今晚有空吗")), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        assertEquals("今晚有空吗", result.text)
        assertEquals(OcrContentKind.TEXT, result.tokens.single().kind)
        assertEquals(OcrTextProjectionStatus.COMPLETE, projection.status)
        assertEquals(result.text, projection.text)
        assertEquals(ChatMessageDirection.INCOMING, message.direction)
        assertEquals(MessageContentQuality.VERIFIED, message.contentQuality)
        assertTrue(message.isAiInputEligible)
    }

    @Test fun unicodeEmojiIsIndependentTokenAndProjectionDoesNotRewriteMessageIdentity() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好😊还没睡吗")), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        val originalRevision = message.effectiveContentRevision
        val originalId = message.stableLogicalMessageId
        val batch = ChatMessageBatch(
            MessageReadStatus.SUCCESS, "session", listOf(message), 1, 1, false, 1L,
            ocrTokensByRowIndex = mapOf(3 to result.tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        )
        assertEquals(listOf(OcrContentKind.TEXT, OcrContentKind.EMOJI, OcrContentKind.TEXT),
            result.tokens.map { it.kind })
        assertEquals("你好还没睡吗", projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals("你好😊还没睡吗", message.content)
        assertEquals(originalId, batch.messages.single().stableLogicalMessageId)
        assertEquals(originalRevision, batch.messages.single().effectiveContentRevision)
        assertFalse(message.toString().contains("你好😊"))
    }

    @Test fun unknownVisualLineIsExcludedWhileIndependentLinesStayOrdered() {
        val region = OcrVisualRect(20, 52, 45, 72)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("今晚有空吗"), line("在吗a", 48), line("明天也可以", 100)),
            listOf(region)
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        assertEquals("今晚有空吗\n明天也可以", projection.text)
        assertEquals(listOf(0, 2), projection.includedLineIndices)
        assertEquals(listOf(1), projection.excludedLineIndices)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertTrue(result.tokens.any { it.kind == OcrContentKind.PLACEHOLDER })
        assertEquals(MessageContentQuality.AMBIGUOUS, message.contentQuality)
        assertFalse(message.isAiInputEligible)
        assertEquals(result.text, message.content)
    }

    @Test fun unknownVisualInsideOneLineNeverPromotesItsNeighborText() {
        val region = OcrVisualRect(20, 4, 45, 23)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你还没睡吗a")), listOf(region)
        )
        assertTrue(result.text.contains(MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY))
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens))
        assertFalse(map(result.text).isAiInputEligible)
    }

    @Test fun pureEmojiAndNoisySymbolsCannotCreateModelTextProjection() {
        listOf("😊", "1️⃣", "👩‍💻", ")(", "?!").forEach { raw ->
            val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
                listOf(line(raw)), emptyList()
            )
            assertNull(raw, MomoOcrTextProjector.extract(result.text, result.tokens))
            assertFalse(raw, map(result.text).isAiInputEligible)
        }
        val visualOnly = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            emptyList(), listOf(OcrVisualRect(4, 5, 35, 34))
        )
        assertNull(MomoOcrTextProjector.extract(visualOnly.text, visualOnly.tokens))
        assertFalse(map(visualOnly.text).isAiInputEligible)
    }

    @Test fun lowConfidencePunctuationCannotBecomeVerifiedNearbyText() {
        val glyph = OcrVisualSymbol("？", OcrVisualRect(25, 2, 36, 24), 0.2f)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好？", symbols = listOf(glyph))), emptyList()
        )
        assertTrue(result.text.contains(MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL))
        assertTrue(result.tokens.any { it.source == OcrTokenSource.OCR_SYMBOL })
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens))
        assertEquals(MessageContentQuality.AMBIGUOUS, map(result.text).contentQuality)
    }

    @Test fun mismatchedOrMissingOcrEvidenceCannotBeUsedForPartialAdmission() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好😊还没睡")), emptyList()
        )
        assertNull(MomoOcrTextProjector.extract(result.text + "篡改", result.tokens))
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens.dropLast(1)))
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens.reversed()))
        val onlyClassification = target().copy(classificationOnly = true)
        assertTrue(MomoChatOcrCandidateMapper.parse(
            listOf(onlyClassification), mapOf(3 to "个人资料"), listBounds,
            ChatMessageParser(), 10
        ).isEmpty())
    }

    @Test fun fullOcrPayloadAndTokenDataAreNotPrintedByBatchDiagnostics() {
        val secret = "P4_SECRET_RAW_OCR_SENTINEL"
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line(secret)), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        val batch = ChatMessageBatch(
            MessageReadStatus.SUCCESS, "window", listOf(message), 1, 1, false, 1L,
            ocrTokensByRowIndex = mapOf(3 to result.tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        )
        assertFalse(batch.toString().contains(secret))
        assertFalse(batch.summary().contains(secret))
        assertFalse(result.tokens.single().toString().contains(secret))
        assertFalse(projection.toString().contains(secret))
    }
}
