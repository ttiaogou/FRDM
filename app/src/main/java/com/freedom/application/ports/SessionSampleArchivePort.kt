package com.freedom.application.ports

import com.freedom.features.aireply.domain.message.ChatMessageObservationIndex
import org.json.JSONObject
import java.io.File

/** Application-facing capability for sealing and recovering local session-sample archives. */
interface SessionSampleArchivePort {
    data class RecoverySummary(
        val recoveredCount: Int = 0,
        val cleanedSealedCount: Int = 0,
        val skippedActiveCount: Int = 0,
        val skippedRecentUnownedCount: Int = 0,
        val failedCount: Int = 0,
        val errors: List<String> = emptyList()
    )

    fun appendJsonLine(runDirectory: File, name: String, value: JSONObject): Result<Unit>
    fun relativePath(runDirectory: File, path: String): String
    fun persistConversationMessageIndex(runDirectory: File, index: ChatMessageObservationIndex)
    fun packageVerifiedArchive(runDirectory: File, finishedAtEpochMs: Long, warnings: List<String>): File
    fun isVerifiedArchive(zip: File): Boolean
    fun recoverPendingRuns(runsDirectory: File): RecoverySummary
    fun writeOwnerMarker(runsDirectory: File, runId: String, startedAtEpochMs: Long): Boolean
    fun removeOwnerMarker(runsDirectory: File, runId: String)
}
