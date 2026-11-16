package com.freedom.features.aireply.domain.message

/**
 * A deduplicated observation of a message fingerprint within one run and one display-name scope.
 * The fingerprint is content-derived, not a target-app/server message ID.
 */
data class ChatMessageObservationRecord(
    val sessionKey: String,
    val message: ChatMessage,
    val firstObservedAtMillis: Long,
    val lastObservedAtMillis: Long,
    val observationCount: Int,
    val fingerprintCollision: Boolean,
    val sessionIdentityAmbiguous: Boolean = false
) {
    val aiInputEligible: Boolean
        get() = message.isAiInputEligible && !sessionIdentityAmbiguous
}

/** Aggregate counts for a run-local, read-only observation index. */
data class ChatMessageObservationSummary(
    val sessionCount: Int,
    val uniqueMessageFingerprintCount: Int,
    val observationCount: Int,
    val repeatedObservationCount: Int,
    val verifiedTextCount: Int,
    val unverifiedTextCount: Int,
    val ambiguousTextCount: Int,
    val failedTextCount: Int,
    val aiInputEligibleCount: Int,
    val fingerprintCollisionCount: Int
)

/**
 * Run-local de-duplication for repeated visible-message snapshots.
 *
 * Callers must first validate the chat header and active window. [sessionKey] is the project's
 * opaque normalized-display-name key, not an account ID. [ChatMessage.id] is also provisional:
 * it is generated from message content/direction/type/time evidence, not supplied by Momo.
 * This index is evidence consolidation only; it never promotes OCR text to VERIFIED.
 */
class ChatMessageObservationIndex {
    private data class MutableRecord(
        val sessionKey: String,
        var message: ChatMessage,
        var firstObservedAtMillis: Long,
        var lastObservedAtMillis: Long,
        var observationCount: Int,
        var fingerprintCollision: Boolean,
        var sessionIdentityAmbiguous: Boolean = false
    )

    private val recordsBySession = LinkedHashMap<String, LinkedHashMap<String, MutableRecord>>()
    private val blockedSessionIdentityKeys = LinkedHashSet<String>()

    @Synchronized
    fun observe(sessionKey: String?, batch: ChatMessageBatch): ChatMessageObservationSummary? {
        val normalizedKey = sessionKey?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (batch.windowSessionKey.isNullOrBlank() || batch.messages.isEmpty()) return summary()

        val sessionRecords = recordsBySession.getOrPut(normalizedKey) { LinkedHashMap() }
        batch.messages.forEach { message ->
            val fingerprint = message.id.trim()
            if (fingerprint.isEmpty()) return@forEach

            val sameFingerprintRecords = sessionRecords.values.filter { it.message.id == fingerprint }
            val existing = sameFingerprintRecords.firstOrNull { sameMessagePayload(it.message, message) }
            if (existing != null) {
                existing.observationCount += 1
                existing.firstObservedAtMillis = minOf(
                    existing.firstObservedAtMillis, batch.capturedAtMillis
                )
                existing.lastObservedAtMillis = maxOf(
                    existing.lastObservedAtMillis, batch.capturedAtMillis
                )
                existing.message = mergeEvidence(existing.message, message)
            } else {
                val collision = sameFingerprintRecords.isNotEmpty()
                if (collision) sameFingerprintRecords.forEach { it.fingerprintCollision = true }
                val storageKey = if (!collision && fingerprint !in sessionRecords) {
                    fingerprint
                } else {
                    var suffix = 2
                    var candidate = "$fingerprint#variant-$suffix"
                    while (candidate in sessionRecords) {
                        suffix += 1
                        candidate = "$fingerprint#variant-$suffix"
                    }
                    candidate
                }
                sessionRecords[storageKey] = MutableRecord(
                    sessionKey = normalizedKey,
                    message = message,
                    firstObservedAtMillis = batch.capturedAtMillis,
                    lastObservedAtMillis = batch.capturedAtMillis,
                    observationCount = 1,
                    fingerprintCollision = collision,
                    sessionIdentityAmbiguous = normalizedKey in blockedSessionIdentityKeys
                )
            }
        }
        return summary()
    }

    @Synchronized
    fun snapshot(): List<ChatMessageObservationRecord> = recordsBySession.values
        .flatMap { it.values }
        .map { record ->
            ChatMessageObservationRecord(
                sessionKey = record.sessionKey,
                message = record.message,
                firstObservedAtMillis = record.firstObservedAtMillis,
                lastObservedAtMillis = record.lastObservedAtMillis,
                observationCount = record.observationCount,
                fingerprintCollision = record.fingerprintCollision,
                sessionIdentityAmbiguous = record.sessionIdentityAmbiguous
            )
        }

    @Synchronized
    fun summary(): ChatMessageObservationSummary {
        val records = recordsBySession.values.flatMap { it.values }
        val textRecords = records.filter { it.message.type == ChatMessageType.TEXT }
        val observationCount = records.sumOf { it.observationCount }
        val uniqueFingerprintCount = records
            .map { it.sessionKey to it.message.id }
            .distinct()
            .size
        return ChatMessageObservationSummary(
            sessionCount = recordsBySession.size,
            uniqueMessageFingerprintCount = uniqueFingerprintCount,
            observationCount = observationCount,
            repeatedObservationCount = (observationCount - records.size).coerceAtLeast(0),
            verifiedTextCount = textRecords.count {
                it.message.contentQuality == MessageContentQuality.VERIFIED
            },
            unverifiedTextCount = textRecords.count {
                it.message.contentQuality == MessageContentQuality.UNVERIFIED
            },
            ambiguousTextCount = textRecords.count {
                it.message.contentQuality == MessageContentQuality.AMBIGUOUS
            },
            failedTextCount = textRecords.count {
                it.message.contentQuality == MessageContentQuality.FAILED
            },
            aiInputEligibleCount = records.count {
                it.message.isAiInputEligible && !it.sessionIdentityAmbiguous
            },
            // Count colliding fingerprint groups, not the number of affected variant records.
            // A single fingerprint with three conflicting payload variants is one collision.
            fingerprintCollisionCount = records
                .groupBy { it.sessionKey to it.message.id }
                .values
                .count { variants -> variants.any { it.fingerprintCollision } }
        )
    }

    /**
     * A later same-name collision invalidates every earlier observation in that display-name
     * scope. The raw observations remain available for diagnostics, but none may enter AI input.
     */
    @Synchronized
    fun blockSessionIdentity(sessionKey: String) {
        val key = sessionKey.trim().takeIf(String::isNotEmpty) ?: return
        blockedSessionIdentityKeys += key
        recordsBySession[key]?.values?.forEach { it.sessionIdentityAmbiguous = true }
    }

    @Synchronized
    fun clear() {
        recordsBySession.clear()
        blockedSessionIdentityKeys.clear()
    }

    private fun sameMessagePayload(first: ChatMessage, second: ChatMessage): Boolean =
        first.direction == second.direction && first.type == second.type &&
            first.content == second.content

    private fun mergeEvidence(existing: ChatMessage, incoming: ChatMessage): ChatMessage {
        val selectedQuality = if (qualityRank(incoming.contentQuality) > qualityRank(existing.contentQuality)) {
            incoming.contentQuality
        } else {
            existing.contentQuality
        }
        val selectedDirectionEvidence = if (
            directionEvidenceRank(incoming.directionEvidence) >
            directionEvidenceRank(existing.directionEvidence)
        ) incoming.directionEvidence else existing.directionEvidence
        return existing.copy(
            contentQuality = selectedQuality,
            directionEvidence = selectedDirectionEvidence
        )
    }

    private fun qualityRank(value: MessageContentQuality): Int = when (value) {
        MessageContentQuality.FAILED -> 0
        MessageContentQuality.AMBIGUOUS -> 1
        MessageContentQuality.UNVERIFIED -> 2
        MessageContentQuality.VERIFIED -> 3
    }

    private fun directionEvidenceRank(value: DirectionEvidence): Int = when (value) {
        DirectionEvidence.AMBIGUOUS -> 0
        DirectionEvidence.BUBBLE_GEOMETRY -> 1
        DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY -> 2
        DirectionEvidence.EXPLICIT_LAYOUT_HINT -> 3
        DirectionEvidence.LAYOUT_ID_AND_HIERARCHY -> 4
    }
}
