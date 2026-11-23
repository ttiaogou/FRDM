package com.freedom.features.aireply.domain.message

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageObservationIndexTest {

    @Test
    fun repeatedSnapshotsAreMergedWithinTheSameSessionScope() {
        val index = ChatMessageObservationIndex()
        val message = message("fingerprint-1", "hello")

        index.observe("session-a", batch("window-1", 100L, message))
        val summary = index.observe("session-a", batch("window-2", 200L, message))

        assertEquals(1, summary?.uniqueMessageFingerprintCount)
        assertEquals(2, summary?.observationCount)
        assertEquals(1, summary?.repeatedObservationCount)
        val record = index.snapshot().single()
        assertEquals(100L, record.firstObservedAtMillis)
        assertEquals(200L, record.lastObservedAtMillis)
        assertEquals(2, record.observationCount)
    }

    @Test
    fun identicalMessageFingerprintDoesNotMergeAcrossDifferentSessionScopes() {
        val index = ChatMessageObservationIndex()
        val message = message("fingerprint-1", "hello")

        index.observe("session-a", batch("window-1", 100L, message))
        val summary = index.observe("session-b", batch("window-2", 200L, message))

        assertEquals(2, summary?.sessionCount)
        assertEquals(2, summary?.uniqueMessageFingerprintCount)
        assertEquals(2, summary?.observationCount)
    }

    @Test
    fun verifiedEvidenceCanUpgradeButUnverifiedEvidenceCannotDowngrade() {
        val index = ChatMessageObservationIndex()
        index.observe(
            "session-a",
            batch("window-1", 100L, message("fingerprint-1", "hello", MessageContentQuality.UNVERIFIED))
        )
        index.observe(
            "session-a",
            batch("window-2", 200L, message("fingerprint-1", "hello", MessageContentQuality.VERIFIED))
        )
        val summary = index.observe(
            "session-a",
            batch("window-3", 300L, message("fingerprint-1", "hello", MessageContentQuality.UNVERIFIED))
        )

        assertEquals(MessageContentQuality.VERIFIED, index.snapshot().single().message.contentQuality)
        assertEquals(1, summary?.verifiedTextCount)
        assertEquals(0, summary?.unverifiedTextCount)
        assertEquals(1, summary?.aiInputEligibleCount)
    }

    @Test
    fun validatedAvatarLayoutEvidenceOutranksOlderDirectionEvidence() {
        val index = ChatMessageObservationIndex()
        val first = message("fingerprint-layout", "hello").copy(
            directionEvidence = DirectionEvidence.EXPLICIT_LAYOUT_HINT
        )
        index.observe("session-a", batch("window-1", 100L, first))

        val upgraded = first.copy(directionEvidence = DirectionEvidence.LAYOUT_ID_AND_HIERARCHY)
        index.observe("session-a", batch("window-2", 200L, upgraded))

        val laterGeometry = first.copy(directionEvidence = DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY)
        index.observe("session-a", batch("window-3", 300L, laterGeometry))

        assertEquals(
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
            index.snapshot().single().message.directionEvidence
        )
    }

    @Test
    fun blankSessionOrUnvalidatedWindowIsNotAccumulated() {
        val index = ChatMessageObservationIndex()
        val message = message("fingerprint-1", "hello")

        assertNull(index.observe(" ", batch("window-1", 100L, message)))
        index.observe("session-a", batch(null, 200L, message))

        assertTrue(index.snapshot().isEmpty())
        assertEquals(0, index.summary().observationCount)
    }

    @Test
    fun conflictingPayloadUnderTheSameFingerprintIsPreservedAsCollision() {
        val index = ChatMessageObservationIndex()
        index.observe("session-a", batch("window-1", 100L, message("same-id", "first")))
        val summary = index.observe("session-a", batch("window-2", 200L, message("same-id", "second")))

        assertEquals(2, index.snapshot().size)
        assertEquals(1, summary?.uniqueMessageFingerprintCount)
        assertEquals(1, summary?.fingerprintCollisionCount)
        assertTrue(index.snapshot().all { it.fingerprintCollision })
    }

    @Test
    fun multipleConflictingVariantsCountAsOneCollisionGroup() {
        val index = ChatMessageObservationIndex()
        index.observe("session-a", batch("window-1", 100L, message("same-id", "first")))
        index.observe("session-a", batch("window-2", 200L, message("same-id", "second")))
        val summary = index.observe(
            "session-a", batch("window-3", 300L, message("same-id", "third"))
        )

        assertEquals(3, index.snapshot().size)
        assertEquals(1, summary?.fingerprintCollisionCount)
        assertEquals(1, summary?.uniqueMessageFingerprintCount)
    }

    @Test
    fun sameFingerprintWithDifferentPayloadAcrossSessionsIsNotACollision() {
        val index = ChatMessageObservationIndex()
        index.observe("session-a", batch("window-1", 100L, message("same-id", "first")))
        val summary = index.observe(
            "session-b", batch("window-2", 200L, message("same-id", "second"))
        )

        assertEquals(2, index.snapshot().size)
        assertEquals(2, summary?.uniqueMessageFingerprintCount)
        assertEquals(0, summary?.fingerprintCollisionCount)
    }

    @Test
    fun failedTextWithoutRecognizedContentIsIncludedInFailedQualityCount() {
        val index = ChatMessageObservationIndex()
        val failed = message(
            "fingerprint-failed", "", MessageContentQuality.FAILED
        )

        val summary = index.observe("session-a", batch("window-1", 100L, failed))

        assertEquals(1, summary?.failedTextCount)
        assertEquals(0, summary?.aiInputEligibleCount)
    }

    @Test
    fun verifiedTextWithUnknownDirectionIsNotAiInputEligible() {
        val index = ChatMessageObservationIndex()
        val unknownDirection = message(
            "fingerprint-1", "hello", MessageContentQuality.VERIFIED,
            direction = ChatMessageDirection.UNKNOWN
        )

        val summary = index.observe("session-a", batch("window-1", 100L, unknownDirection))

        assertEquals(0, summary?.aiInputEligibleCount)
        assertFalse(index.snapshot().single().message.isAiInputEligible)
    }


    @Test
    fun laterIdentityCollisionRevokesAiEligibilityForEarlierObservations() {
        val index = ChatMessageObservationIndex()
        val verified = message(
            "fingerprint-1", "hello", MessageContentQuality.VERIFIED
        )
        index.observe("same-name-key", batch("window-1", 100L, verified))
        assertEquals(1, index.summary().aiInputEligibleCount)

        index.blockSessionIdentity("same-name-key")

        val record = index.snapshot().single()
        assertTrue(record.sessionIdentityAmbiguous)
        assertFalse(record.aiInputEligible)
        assertEquals(0, index.summary().aiInputEligibleCount)

        index.observe("same-name-key", batch("window-2", 200L, verified))
        assertTrue(index.snapshot().all { it.sessionIdentityAmbiguous })
        assertEquals(0, index.summary().aiInputEligibleCount)
    }

    @Test
    fun identityBlockIsRunLocalAndClearedBetweenRuns() {
        val index = ChatMessageObservationIndex()
        index.blockSessionIdentity("same-name-key")
        index.observe(
            "same-name-key",
            batch("window-1", 100L, message("fingerprint-1", "hello", MessageContentQuality.VERIFIED))
        )
        assertEquals(0, index.summary().aiInputEligibleCount)

        index.clear()
        index.observe(
            "same-name-key",
            batch("window-2", 200L, message("fingerprint-2", "hello", MessageContentQuality.VERIFIED))
        )
        assertEquals(1, index.summary().aiInputEligibleCount)
    }

    private fun batch(windowKey: String?, capturedAt: Long, vararg messages: ChatMessage) =
        ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = windowKey,
            messages = messages.toList(),
            scannedRowCount = messages.size,
            candidateRowCount = messages.size,
            truncated = false,
            capturedAtMillis = capturedAt
        )

    private fun message(
        id: String,
        content: String,
        quality: MessageContentQuality = MessageContentQuality.UNVERIFIED,
        direction: ChatMessageDirection = ChatMessageDirection.INCOMING
    ) = ChatMessage(
        id = id,
        direction = direction,
        type = ChatMessageType.TEXT,
        content = content,
        bounds = ScreenRect(0, 100, 720, 220),
        directionEvidence = DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY,
        contentQuality = quality
    )
}
