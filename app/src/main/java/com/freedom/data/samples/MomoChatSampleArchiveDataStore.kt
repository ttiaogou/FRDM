package com.freedom.data.samples

import com.freedom.application.ports.SessionSampleArchivePort
import com.freedom.features.aireply.domain.message.ChatMessageObservationIndex
import org.json.JSONObject
import java.io.File

/** Data implementation for sample persistence, archive integrity, and abandoned-run recovery. */
internal class MomoChatSampleArchiveDataStore : SessionSampleArchivePort {
    override fun appendJsonLine(runDirectory: File, name: String, value: JSONObject): Result<Unit> =
        runCatching { File(runDirectory, name).appendText(value.toString() + "\n", Charsets.UTF_8) }

    override fun relativePath(runDirectory: File, path: String): String {
        val file = File(path)
        return runCatching {
            if (file.canonicalPath.startsWith(runDirectory.canonicalPath + File.separator)) {
                file.relativeTo(runDirectory).invariantSeparatorsPath
            } else path
        }.getOrDefault(path)
    }

    override fun persistConversationMessageIndex(
        runDirectory: File,
        index: ChatMessageObservationIndex
    ) {
        val records = index.snapshot()
        val indexLines = records.map { record ->
            val message = record.message
            JSONObject()
                .put("sessionKey", record.sessionKey)
                .put("sessionIdentityScope", "normalized_display_name_hash")
                .put("messageId", message.id)
                .put("messageIdentityScope", "content_direction_type_time_hint_fingerprint")
                .put("type", message.type.name)
                .put("direction", message.direction.name)
                .put("text", message.content.orEmpty())
                .put("quality", message.contentQuality.name)
                .put("directionEvidence", message.directionEvidence.name)
                .put("firstObservedAt", record.firstObservedAtMillis)
                .put("lastObservedAt", record.lastObservedAtMillis)
                .put("observationCount", record.observationCount)
                .put("fingerprintCollision", record.fingerprintCollision)
                .put("aiInputEligible", record.aiInputEligible)
                .put("sessionIdentityAmbiguous", record.sessionIdentityAmbiguous).toString()
        }
        val indexFile = File(runDirectory, "conversation_message_index.jsonl")
        if (indexLines.isEmpty()) runCatching { indexFile.delete() }
        else indexFile.writeText(indexLines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        val summary = index.summary()
        val summaryJson = JSONObject()
            .put("schemaVersion", 1)
            .put("identityScope", "normalized_display_name_hash_not_account_id")
            .put("messageIdentityScope", "content_derived_fingerprint_not_server_message_id")
            .put("visibleMessageHistoryOnly", true)
            .put("sessionCount", summary.sessionCount)
            .put("uniqueMessageFingerprints", summary.uniqueMessageFingerprintCount)
            .put("messageObservations", summary.observationCount)
            .put("repeatMessageObservations", summary.repeatedObservationCount)
            .put("verifiedTextCount", summary.verifiedTextCount)
            .put("unverifiedTextCount", summary.unverifiedTextCount)
            .put("ambiguousTextCount", summary.ambiguousTextCount)
            .put("failedTextCount", summary.failedTextCount)
            .put("aiInputEligibleCount", summary.aiInputEligibleCount)
            .put("fingerprintCollisionCount", summary.fingerprintCollisionCount)
        File(runDirectory, "conversation_message_index_summary.json")
            .writeText(summaryJson.toString(), Charsets.UTF_8)
    }

    override fun packageVerifiedArchive(
        runDirectory: File,
        finishedAtEpochMs: Long,
        warnings: List<String>
    ): File = MomoChatSampleArchivePackager.packageVerifiedArchive(
        runDirectory = runDirectory,
        finishedAtEpochMs = finishedAtEpochMs,
        warnings = warnings
    )

    override fun isVerifiedArchive(zip: File): Boolean =
        SessionSampleArchiveIntegrity.verifyZipAgainstEmbeddedManifest(zip).valid

    override fun recoverPendingRuns(runsDirectory: File): SessionSampleArchivePort.RecoverySummary {
        val report = SessionSampleArchiveRecovery.recoverPendingRuns(runsDirectory)
        return SessionSampleArchivePort.RecoverySummary(
            recoveredCount = report.recoveredCount,
            cleanedSealedCount = report.cleanedSealedCount,
            skippedActiveCount = report.skippedActiveCount,
            skippedRecentUnownedCount = report.skippedRecentUnownedCount,
            failedCount = report.failedCount,
            errors = report.errors
        )
    }

    override fun writeOwnerMarker(runsDirectory: File, runId: String, startedAtEpochMs: Long): Boolean =
        SessionSampleArchiveRecovery.writeOwnerMarker(runsDirectory, runId, startedAtEpochMs)

    override fun removeOwnerMarker(runsDirectory: File, runId: String) {
        SessionSampleArchiveRecovery.removeOwnerMarker(runsDirectory, runId)
    }
}
