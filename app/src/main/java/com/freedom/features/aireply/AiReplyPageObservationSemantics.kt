package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * Converts raw page observations into a recovery-neutral semantic result.
 * ChatPageKind.UNKNOWN is never treated as the final business recovery conclusion here.
 */
internal enum class AiReplyPageObservationStatus {
    CONFIRMED_PAGE,
    TEMPORARILY_UNAVAILABLE,
    CONFIRMED_UNKNOWN
}

internal data class AiReplyPageObservationDecision(
    val status: AiReplyPageObservationStatus,
    val reason: String
)

internal class AiReplyPageObservationSemantics {
    fun classify(
        snapshot: ChatPageSnapshot?,
        previous: ChatPageSnapshot?,
        targetPackage: String
    ): AiReplyPageObservationDecision {
        if (snapshot == null) {
            return decision(AiReplyPageObservationStatus.TEMPORARILY_UNAVAILABLE, "NO_FRESH_SNAPSHOT")
        }
        if (snapshot.pageKind != ChatPageKind.UNKNOWN) {
            return decision(
                AiReplyPageObservationStatus.CONFIRMED_PAGE,
                "PAGE_KIND_${snapshot.pageKind.name}"
            )
        }
        if (isEmptyUnknown(snapshot)) {
            return decision(AiReplyPageObservationStatus.TEMPORARILY_UNAVAILABLE, "EMPTY_PAGE_TREE")
        }
        if (previous != null &&
            previous.pageKind != ChatPageKind.UNKNOWN &&
            previous.packageName == targetPackage &&
            snapshot.packageName == targetPackage &&
            previous.activityClass == snapshot.activityClass &&
            previous.windowGeneration > 0L &&
            previous.windowGeneration == snapshot.windowGeneration
        ) {
            return decision(
                AiReplyPageObservationStatus.TEMPORARILY_UNAVAILABLE,
                "SAME_WINDOW_STRUCTURE_UNAVAILABLE"
            )
        }
        return decision(AiReplyPageObservationStatus.CONFIRMED_UNKNOWN, "UNKNOWN_PAGE_IDENTITY")
    }

    private fun isEmptyUnknown(snapshot: ChatPageSnapshot): Boolean =
        snapshot.pageKind == ChatPageKind.UNKNOWN &&
            snapshot.scannedNodeCount == 0 &&
            snapshot.messageListCount == 0 &&
            snapshot.editableInputCount == 0 &&
            snapshot.sendActionCount == 0 &&
            snapshot.messageRelatedViewIdCount == 0

    private fun decision(
        status: AiReplyPageObservationStatus,
        reason: String
    ) = AiReplyPageObservationDecision(status, reason)
}
