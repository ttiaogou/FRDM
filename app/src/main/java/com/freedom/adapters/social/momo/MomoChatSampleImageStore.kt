package com.freedom.adapters.social.momo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Owns sample screenshots, message crops and run-local avatar diagnostics. */
internal class MomoChatSampleImageStore(
    private val isRunAcceptingRecords: () -> Boolean,
    private val runDirectory: () -> File?,
    private val appendJsonLine: (String, JSONObject) -> Unit,
    private val relativeSamplePath: (String) -> String,
    private val blockSessionIdentityKey: (String) -> Unit,
    private val onWriteFailure: () -> Unit
) {
    private companion object {
        const val AVATAR_DHASH_DIAGNOSTIC_THRESHOLD = 8
        const val MAX_AVATAR_HISTORY_PER_NAME = 12
        const val MAX_AVATAR_NAME_KEYS = 200
    }

    private var nextImageIndex = 1
    private val avatarFingerprintHistory = LinkedHashMap<String, MutableList<AvatarFingerprintObservation>>()

    private data class AvatarFingerprintObservation(
        val sampleIndex: Int,
        val rowIndex: Int,
        val fingerprint: String
    )

    fun resetForNewRun() {
        nextImageIndex = 1
        avatarFingerprintHistory.clear()
    }

    fun clearHistory() {
        avatarFingerprintHistory.clear()
    }

    fun persistChatScreenshot(sampleIndex: Int, screenshotPath: String?, source: String) {
        if (!isRunAcceptingRecords() || sampleIndex <= 0 || screenshotPath.isNullOrBlank()) return
        appendJsonLine("chat_sample_screenshots.jsonl", JSONObject()
            .put("sampleIndex", sampleIndex)
            .put("capturedAt", System.currentTimeMillis())
            .put("source", source)
            .put("monitorOverlayHidden", true)
            .put("screenshotPath", relativeSamplePath(screenshotPath)))
    }

    fun persistSessionListScreenshot(
        sampleIndex: Int,
        screenshotPath: String?,
        snapshot: ChatPageSnapshot
    ) {
        if (!isRunAcceptingRecords() || sampleIndex <= 0 || screenshotPath.isNullOrBlank()) return
        appendJsonLine("session_list_screenshots.jsonl", JSONObject()
            .put("sampleIndex", sampleIndex)
            .put("capturedAt", System.currentTimeMillis())
            .put("monitorOverlayHidden", true)
            .put("screenshotPath", relativeSamplePath(screenshotPath)))

        // Avatar fingerprints are diagnostics only; conflicting name-only keys are quarantined.
        val bitmap = runCatching { BitmapFactory.decodeFile(screenshotPath) }.getOrNull() ?: return
        try {
            val evidenceRows = JSONArray()
            snapshot.chatListCandidates.forEach { candidate ->
                val bounds = candidate.avatarBounds ?: return@forEach
                val fingerprint = avatarDifferenceHash(bitmap, bounds) ?: return@forEach
                val displayNameKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
                val hasVerifiedPlatformIdentity = candidate.contactIdentity?.let { identity ->
                    identity.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                        !identity.contactId.isNullOrBlank()
                } == true
                val previous = avatarFingerprintHistory[displayNameKey].orEmpty()
                    .filter { it.sampleIndex < sampleIndex }
                val nearest = previous.minByOrNull {
                    MomoChatListSessionPolicy.avatarHashHammingDistance(it.fingerprint, fingerprint) ?: Int.MAX_VALUE
                }
                val distance = nearest?.let {
                    MomoChatListSessionPolicy.avatarHashHammingDistance(it.fingerprint, fingerprint)
                }
                val visualConflictCandidate = ChatSessionManagementPolicy.isEligible(candidate) &&
                    MomoChatListSessionPolicy.isAvatarFingerprintConflictCandidate(
                        distance = distance,
                        similarityThreshold = AVATAR_DHASH_DIAGNOSTIC_THRESHOLD,
                        platformIdVerified = hasVerifiedPlatformIdentity
                    )
                if (visualConflictCandidate) blockSessionIdentityKey(displayNameKey)
                evidenceRows.put(JSONObject()
                    .put("rowIndex", candidate.rowIndex)
                    .put("displayNameIdentityKey", displayNameKey)
                    .put("avatarBounds", MomoChatSampleDataMapper.rectJson(bounds))
                    .put("fingerprintAlgorithm", "dhash_8x8_v1")
                    .put("fingerprint", fingerprint)
                    .put("nearestPriorObservation", nearest?.let {
                        JSONObject().put("sampleIndex", it.sampleIndex).put("rowIndex", it.rowIndex)
                    })
                    .put("nearestPriorHammingDistance", distance)
                    .put("visualSimilarityCandidate", distance != null && distance <= AVATAR_DHASH_DIAGNOSTIC_THRESHOLD)
                    .put("visualConflictCandidate", visualConflictCandidate)
                    .put("identityVerified", false)
                    .put("identityQuarantined", visualConflictCandidate)
                    .put("selectionImpact", if (visualConflictCandidate) "BLOCKED_NAME_ONLY_IDENTITY" else "DIAGNOSTIC_ONLY"))
                val history = avatarFingerprintHistory.getOrPut(displayNameKey) { ArrayList() }
                history.add(AvatarFingerprintObservation(sampleIndex, candidate.rowIndex, fingerprint))
                while (history.size > MAX_AVATAR_HISTORY_PER_NAME) history.removeAt(0)
                while (avatarFingerprintHistory.size > MAX_AVATAR_NAME_KEYS) {
                    val eldestKey = avatarFingerprintHistory.keys.firstOrNull() ?: break
                    avatarFingerprintHistory.remove(eldestKey)
                }
            }
            appendJsonLine("session_list_avatar_evidence.jsonl", JSONObject()
                .put("sampleIndex", sampleIndex)
                .put("capturedAt", System.currentTimeMillis())
                .put("screenshotWidth", bitmap.width)
                .put("screenshotHeight", bitmap.height)
                .put("algorithm", "dhash_8x8_v1")
                .put("rows", evidenceRows))
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun avatarDifferenceHash(bitmap: Bitmap, bounds: ScreenRect): String? {
        val left = bounds.left.coerceAtLeast(0)
        val top = bounds.top.coerceAtLeast(0)
        val right = bounds.right.coerceAtMost(bitmap.width)
        val bottom = bounds.bottom.coerceAtMost(bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null

        fun luminance(x: Int, y: Int): Int {
            val px = left + ((x + 0.5f) * (right - left) / 9f).toInt()
            val py = top + ((y + 0.5f) * (bottom - top) / 8f).toInt()
            val color = bitmap.getPixel(px.coerceIn(left, right - 1), py.coerceIn(top, bottom - 1))
            val red = (color shr 16) and 0xff
            val green = (color shr 8) and 0xff
            val blue = color and 0xff
            return (red * 299 + green * 587 + blue * 114) / 1000
        }

        val values = Array(8) { row -> IntArray(9) { col -> luminance(col, row) } }
        var bits = 0L
        var bitIndex = 0
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                if (values[row][col] > values[row][col + 1]) bits = bits or (1L shl bitIndex)
                bitIndex++
            }
        }
        return java.lang.Long.toHexString(bits).padStart(16, '0')
    }

    fun saveBitmap(prefix: String, bitmap: Bitmap): String? {
        if (!isRunAcceptingRecords()) return null
        val directory = runDirectory() ?: return null
        val imageDirectory = File(directory, "screenshots")
        if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
            onWriteFailure()
            return null
        }
        val index = nextImageIndex++
        val safePrefix = prefix.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        val file = File(imageDirectory, "%05d_%s.png".format(index, safePrefix))
        return try {
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    onWriteFailure()
                    runCatching { file.delete() }
                    return null
                }
                output.flush()
            }
            file.absolutePath
        } catch (_: Exception) {
            onWriteFailure()
            runCatching { file.delete() }
            null
        }
    }

    fun saveCropBitmap(rowIndex: Int, bitmap: Bitmap): String? {
        if (!isRunAcceptingRecords()) return null
        val directory = runDirectory() ?: return null
        val cropDirectory = File(directory, "message_crops")
        if (!cropDirectory.exists() && !cropDirectory.mkdirs()) {
            onWriteFailure()
            return null
        }
        val file = File(cropDirectory, "crop_%05d_row_%03d.png".format(nextImageIndex++, rowIndex))
        return try {
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    onWriteFailure()
                    runCatching { file.delete() }
                    return null
                }
                output.flush()
            }
            file.absolutePath
        } catch (_: Exception) {
            onWriteFailure()
            runCatching { file.delete() }
            null
        }
    }
}
