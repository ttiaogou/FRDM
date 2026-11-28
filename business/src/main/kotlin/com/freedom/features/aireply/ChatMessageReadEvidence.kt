package com.freedom.features.aireply

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Snapshot/batch comparison only: the caller owns fresh reads, task leases and binding checks. */
internal fun matchesMessageReadWindowEvidence(
    snapshot: ChatPageSnapshot,
    batch: ChatMessageBatch,
    active: ChatPageSnapshot,
    expectedWindowSessionKey: String?
): Boolean = expectedWindowSessionKey != null &&
    batch.windowSessionKey == expectedWindowSessionKey &&
    hasSameWindowSession(snapshot, active)

internal fun matchesPrivateMessageReadPageEvidence(
    snapshot: ChatPageSnapshot,
    active: ChatPageSnapshot
): Boolean = active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
    active.packageName == snapshot.packageName &&
    active.hasSameWindowIdentity(snapshot) &&
    hasSameWindowSession(snapshot, active)

/** Preserve the rejection precedence and redacted reason codes of the existing send-proof gate. */
internal fun sendConfirmationPageEvidenceRejectionReason(
    source: ChatPageSnapshot,
    fresh: ChatPageSnapshot
): String? {
    if (fresh.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) return "FRESH_CHAT_DETAIL_NOT_CONFIRMED"
    if (fresh.packageName != source.packageName) return "FRESH_PACKAGE_MISMATCH"
    if (!fresh.hasSameWindowIdentity(source)) return "WINDOW_IDENTITY_CHANGED"
    if (!hasSameWindowSession(source, fresh)) return "WINDOW_GENERATION_OR_SESSION_CHANGED"
    return null
}

internal fun sendConfirmationBatchEvidenceRejectionReason(
    batch: ChatMessageBatch,
    sourceWindowKey: String
): String {
    if (batch.status == MessageReadStatus.WINDOW_STALE) return "OCR_READ_INVALIDATED"
    if (batch.windowSessionKey != sourceWindowKey) return "OCR_BATCH_WINDOW_MISMATCH"
    return "GATE_REJECTED_UNCLASSIFIED"
}

/** Diagnostic count only; no admission or interpretation of message content. */
internal fun countMergedOcrTargets(batch: ChatMessageBatch): Int =
    batch.ocrTargets.count { target ->
        batch.messages.any { message -> message.bounds == target.rowBounds }
    }

private const val MAX_OCR_DEBUG_TEXT_CHARS = 4_200

internal fun createOcrDebugFingerprint(
    windowSessionKey: String?,
    targets: List<com.freedom.features.aireply.domain.message.ChatMessageOcrTarget>,
    finalRows: Map<Int, String>,
    chineseRows: Map<Int, String>,
    latinRows: Map<Int, String>,
    failureReason: String?
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(windowSessionKey.orEmpty().toByteArray(StandardCharsets.UTF_8))
    digest.update(failureReason.orEmpty().toByteArray(StandardCharsets.UTF_8))
    targets.forEach { target ->
        digest.update(target.rowIndex.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(finalRows[target.rowIndex].orEmpty().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(chineseRows[target.rowIndex].orEmpty().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(latinRows[target.rowIndex].orEmpty().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun escapeOcrDebugText(value: String): String {
    val escaped = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '\\' -> "\\\\"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '\"' -> "\\\""
                    else -> character.toString()
                }
            )
        }
    }
    return if (escaped.length <= MAX_OCR_DEBUG_TEXT_CHARS) escaped
    else escaped.take(MAX_OCR_DEBUG_TEXT_CHARS) + "…[已截断]"
}
