package com.freedom.features.aireply

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageCandidate
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.domain.message.DirectionEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageParserTest {
    private val parser = ChatMessageParser()
    private val listBounds = ScreenRect(0, 100, 1_000, 1_800)

    @Test
    fun leftAlignedBubbleIsIncomingAndRightAlignedBubbleIsOutgoing() {
        val result = parser.parse(
            listOf(
                candidate(0, ScreenRect(40, 200, 360, 280), "你好"),
                candidate(1, ScreenRect(640, 320, 960, 400), "你好呀")
            )
        )

        assertEquals(ChatMessageDirection.INCOMING, result[0].direction)
        assertEquals(ChatMessageDirection.OUTGOING, result[1].direction)
        assertEquals(DirectionEvidence.BUBBLE_GEOMETRY, result[0].directionEvidence)
    }

    @Test
    fun centerAlignedOrFullWidthBubbleRemainsUnknown() {
        val centered = candidate(0, ScreenRect(350, 200, 650, 280), "不确定的布局")
        val fullWidth = candidate(1, ScreenRect(20, 320, 980, 400), "全宽消息")

        val result = parser.parse(listOf(centered, fullWidth))

        assertTrue(result.all { it.direction == ChatMessageDirection.UNKNOWN })
        assertTrue(result.all { it.directionEvidence == DirectionEvidence.AMBIGUOUS })
    }

    @Test
    fun explicitAdapterDirectionHintTakesPrecedenceOverGeometry() {
        val result = parser.parse(
            listOf(
                candidate(
                    rowIndex = 0,
                    bubble = ScreenRect(650, 200, 950, 280),
                    text = "布局线索优先",
                    explicit = ChatMessageDirection.INCOMING
                )
            )
        ).single()

        assertEquals(ChatMessageDirection.INCOMING, result.direction)
        assertEquals(DirectionEvidence.EXPLICIT_LAYOUT_HINT, result.directionEvidence)
    }


    @Test
    fun explicitUnknownDirectionBlocksGeometricGuessing() {
        val result = parser.parse(
            listOf(
                candidate(
                    rowIndex = 0,
                    bubble = ScreenRect(40, 200, 340, 280),
                    text = "结构线索冲突",
                    explicit = ChatMessageDirection.UNKNOWN
                )
            )
        ).single()

        assertEquals(ChatMessageDirection.UNKNOWN, result.direction)
        assertEquals(DirectionEvidence.AMBIGUOUS, result.directionEvidence)
    }

    @Test
    fun avatarOnSameSideCorroboratesBubbleGeometry() {
        val result = parser.parse(
            listOf(
                candidate(
                    rowIndex = 0,
                    bubble = ScreenRect(90, 200, 390, 280),
                    text = "收到的消息",
                    avatar = ScreenRect(20, 210, 70, 260)
                )
            )
        ).single()

        assertEquals(ChatMessageDirection.INCOMING, result.direction)
        assertEquals(DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY, result.directionEvidence)
    }

    @Test
    fun avatarOnOppositeSideMakesBubbleDirectionAmbiguous() {
        val result = parser.parse(
            listOf(
                candidate(
                    rowIndex = 0,
                    bubble = ScreenRect(40, 200, 340, 280),
                    text = "结构冲突",
                    avatar = ScreenRect(930, 210, 980, 260)
                )
            )
        ).single()

        assertEquals(ChatMessageDirection.UNKNOWN, result.direction)
        assertEquals(DirectionEvidence.AMBIGUOUS, result.directionEvidence)
    }

    @Test
    fun leftAvatarDisambiguatesLongBubbleWhoseCenterFallsInCenterBand() {
        // Mirrors the observed Momo layout: the bubble starts beside the left avatar but is
        // wide enough that its geometric center is almost exactly the center of the screen.
        val result = parser.parse(
            listOf(
                ChatMessageCandidate(
                    rowIndex = 0,
                    rowBounds = ScreenRect(0, 740, 720, 850),
                    bubbleBounds = ScreenRect(112, 756, 600, 838),
                    listBounds = ScreenRect(0, 100, 720, 1_800),
                    text = "一条较长的对方消息",
                    avatarBounds = ScreenRect(24, 748, 98, 822)
                )
            )
        ).single()

        assertEquals(ChatMessageDirection.INCOMING, result.direction)
        assertEquals(DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY, result.directionEvidence)
    }

    @Test
    fun metadataOnlyRowsAreIgnored() {
        val result = parser.parse(
            listOf(
                candidate(0, ScreenRect(20, 200, 120, 230), "10:28"),
                candidate(1, ScreenRect(20, 250, 180, 280), "昨天"),
                candidate(2, ScreenRect(40, 320, 300, 390), "正常消息")
            )
        )

        assertEquals(1, result.size)
        assertEquals("正常消息", result.single().content)
    }

    @Test
    fun unsupportedMediaTypeIsPreservedWithoutExtractingPayload() {
        val result = parser.parse(
            listOf(
                candidate(
                    rowIndex = 0,
                    bubble = ScreenRect(40, 200, 340, 360),
                    text = null,
                    type = ChatMessageType.IMAGE
                )
            )
        ).single()

        assertEquals(ChatMessageType.IMAGE, result.type)
        assertNull(result.content)
        assertEquals(ChatMessageDirection.INCOMING, result.direction)
    }

    @Test
    fun systemMessagesNeverReceiveIncomingOrOutgoingDirection() {
        val result = parser.parse(
            listOf(
                candidate(
                    rowIndex = 0,
                    bubble = ScreenRect(200, 200, 800, 250),
                    text = "你们已经成为好友"
                )
            )
        ).single()

        assertEquals(ChatMessageType.SYSTEM, result.type)
        assertEquals(ChatMessageDirection.UNKNOWN, result.direction)
    }

    @Test
    fun identicalVisibleMessagesReceiveDistinctStableOccurrenceIds() {
        val rows = listOf(
            candidate(0, ScreenRect(40, 200, 340, 270), "重复内容", time = "10:20"),
            candidate(1, ScreenRect(40, 300, 340, 370), "重复内容", time = "10:20")
        )

        val first = parser.parse(rows)
        val second = parser.parse(rows)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertNotEquals(first[0].id, first[1].id)
    }

    @Test
    fun messageAndBatchStringRepresentationsRedactBodies() {
        val message = parser.parse(
            listOf(candidate(0, ScreenRect(40, 200, 340, 270), "PRIVATE_CHAT_BODY_SENTINEL"))
        ).single()

        assertFalse(message.toString().contains("PRIVATE_CHAT_BODY_SENTINEL"))
        assertTrue(message.toString().contains("REDACTED"))
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "test-window",
            messages = listOf(message),
            scannedRowCount = 1,
            candidateRowCount = 1,
            truncated = false,
            capturedAtMillis = 1L
        )
        assertFalse(batch.toString().contains("PRIVATE_CHAT_BODY_SENTINEL"))
    }


    @Test
    fun candidateStringRepresentationRedactsRawMessageBody() {
        val candidate = candidate(
            rowIndex = 0,
            bubble = ScreenRect(40, 200, 340, 270),
            text = "PRIVATE_CANDIDATE_BODY_SENTINEL"
        )

        assertFalse(candidate.toString().contains("PRIVATE_CANDIDATE_BODY_SENTINEL"))
        assertTrue(candidate.toString().contains("REDACTED"))
    }

    @Test
    fun latestIncomingTextMessageSkipsOutgoingAndAmbiguousMessages() {
        val messages = parser.parse(
            listOf(
                candidate(0, ScreenRect(40, 200, 340, 270), "第一条收到"),
                candidate(1, ScreenRect(350, 300, 650, 370), "方向不明"),
                candidate(2, ScreenRect(660, 400, 960, 470), "自己发出"),
                candidate(3, ScreenRect(40, 500, 340, 570), "最新收到")
            )
        )
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "test-window",
            messages = messages,
            scannedRowCount = 4,
            candidateRowCount = 4,
            truncated = false,
            capturedAtMillis = 1L
        )

        assertEquals("最新收到", batch.latestIncomingTextMessage?.content)
        assertEquals(ChatMessageDirection.UNKNOWN, messages[1].direction)
    }

    @Test
    fun exposesEligibleIncomingMessagesInBottomToTopProcessingOrder() {
        val messages = parser.parse(
            listOf(
                candidate(0, ScreenRect(40, 200, 340, 270), "较早收到"),
                candidate(1, ScreenRect(660, 300, 960, 370), "自己发送"),
                candidate(2, ScreenRect(40, 400, 340, 470), "较新收到")
            )
        )
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "test-window",
            messages = messages,
            scannedRowCount = 3,
            candidateRowCount = 3,
            truncated = false,
            capturedAtMillis = 1L
        )

        assertEquals(
            listOf("较新收到", "自己发送", "较早收到"),
            batch.messagesBottomToTop.map { it.content }
        )
        assertEquals(
            listOf("较新收到", "自己发送", "较早收到"),
            batch.aiInputMessagesBottomToTop.map { it.content }
        )
        assertEquals(
            listOf("较新收到", "较早收到"),
            batch.incomingMessagesBottomToTop.map { it.content }
        )
    }

    @Test
    fun outputIsLimitedToMostRecentVisibleRows() {
        val candidates = (0 until 14).map { index ->
            candidate(index, ScreenRect(40, 200 + index * 80, 340, 260 + index * 80), "消息$index")
        }

        val result = parser.parse(candidates, limit = 10)

        assertEquals(10, result.size)
        assertEquals("消息4", result.first().content)
        assertEquals("消息13", result.last().content)
    }

    private fun candidate(
        rowIndex: Int,
        bubble: ScreenRect,
        text: String?,
        type: ChatMessageType = ChatMessageType.UNKNOWN,
        explicit: ChatMessageDirection? = null,
        time: String? = null,
        avatar: ScreenRect? = null
    ) = ChatMessageCandidate(
        rowIndex = rowIndex,
        rowBounds = ScreenRect(0, bubble.top - 10, 1_000, bubble.bottom + 10),
        bubbleBounds = bubble,
        listBounds = listBounds,
        text = text,
        typeHint = type,
        explicitDirectionHint = explicit,
        timeHint = time,
        avatarBounds = avatar
    )
}
