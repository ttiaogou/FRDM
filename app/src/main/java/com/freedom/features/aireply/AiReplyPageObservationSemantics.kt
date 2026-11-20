package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * Converts raw page observations into a recovery-neutral semantic result.
 * ChatPageKind.UNKNOWN is never treated as the final business recovery conclusion here.
 */
internal enum class ObservationStatus {
    RAW_SNAPSHOT_AVAILABLE,
    CONFIRMED_PAGE,
    TEMPORARILY_UNAVAILABLE,
    CONFIRMED_UNKNOWN
}

/**
 * The semantic result shared by every AI Reply page observation.
 * ChatPageKind remains adapter evidence; callers must make decisions from this result instead.
 */
internal data class ObservationResult(
    val status: ObservationStatus,
    val reason: String,
    val snapshot: ChatPageSnapshot? = null,
    val observationGeneration: Long = 0L,
    val purpose: AiReplyPageReadPurpose? = null,
    val failure: Throwable? = null
)

internal class AiReplyPageObservationSemantics {
    fun classify(
        snapshot: ChatPageSnapshot?,
        previous: ChatPageSnapshot?,
        targetPackage: String
    ): ObservationResult {
        if (snapshot == null) {
            return decision(ObservationStatus.TEMPORARILY_UNAVAILABLE, "NO_FRESH_SNAPSHOT", snapshot)
        }
        if (snapshot.pageKind != ChatPageKind.UNKNOWN) {
            return decision(
                ObservationStatus.CONFIRMED_PAGE,
                "PAGE_KIND_${snapshot.pageKind.name}",
                snapshot
            )
        }
        if (isEmptyUnknown(snapshot)) {
            return decision(ObservationStatus.TEMPORARILY_UNAVAILABLE, "EMPTY_PAGE_TREE", snapshot)
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
                ObservationStatus.TEMPORARILY_UNAVAILABLE,
                "SAME_WINDOW_STRUCTURE_UNAVAILABLE",
                snapshot
            )
        }
        return decision(ObservationStatus.CONFIRMED_UNKNOWN, "UNKNOWN_PAGE_IDENTITY", snapshot)
    }

    private fun isEmptyUnknown(snapshot: ChatPageSnapshot): Boolean =
        snapshot.pageKind == ChatPageKind.UNKNOWN &&
            snapshot.scannedNodeCount == 0 &&
            snapshot.messageListCount == 0 &&
            snapshot.editableInputCount == 0 &&
            snapshot.sendActionCount == 0 &&
            snapshot.messageRelatedViewIdCount == 0

    private fun decision(
        status: ObservationStatus,
        reason: String,
        snapshot: ChatPageSnapshot?
    ) = ObservationResult(status = status, reason = reason, snapshot = snapshot)
}
