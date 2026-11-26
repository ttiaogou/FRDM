package com.freedom.features.aireply.domain.message

import com.freedom.core.accessibility.api.ScreenRect
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable local identity for a visually observed row. OCR text is deliberately excluded. */
object ChatMessageIdentity {
    fun logicalMessageId(
        type: ChatMessageType,
        direction: ChatMessageDirection,
        rowBounds: ScreenRect,
        bubbleBounds: ScreenRect?,
        avatarBounds: ScreenRect?,
        timeHint: String?,
        neighborSignature: String? = null,
        structureSignature: String? = null
    ): String = "logical-${sha256(listOf(type.name, direction.name, rect(rowBounds), rect(bubbleBounds), rect(avatarBounds), timeHint.orEmpty().trim(), neighborSignature.orEmpty(), structureSignature.orEmpty()).joinToString("\u001f"))}"

    fun contentRevision(content: String?): String = "ocr-${sha256(content.orEmpty().trim())}"

    /**
     * Identity evidence must not depend on a following row that may not exist yet.
     * Keep the predecessor and current row so appending a reply does not rename
     * the previously visible tail. Geometry remains provisional, not a server ID.
     */
    fun neighborSignature(rows: List<ScreenRect>, index: Int): String = listOfNotNull(
        rows.getOrNull(index - 1)?.let(::rect),
        rows.getOrNull(index)?.let(::rect)
    ).joinToString("|")

    fun observationBatchId(windowSessionKey: String?, capturedAtMillis: Long, rows: List<ScreenRect>): String =
        "batch-${sha256(listOf(windowSessionKey.orEmpty(), capturedAtMillis.toString(), rows.joinToString("|") { rect(it) }).joinToString("\u001f"))}"

    fun windowSnapshotSignature(
        windowSessionKey: String?,
        rootBounds: ScreenRect?,
        listBounds: ScreenRect?,
        rows: List<ScreenRect>,
        structureSignatures: List<String>
    ): String = sha256(listOf(
        windowSessionKey.orEmpty(), rect(rootBounds), rect(listBounds),
        rows.joinToString("|") { rect(it) }, structureSignatures.joinToString("|")
    ).joinToString("\u001f"))

    private fun rect(value: ScreenRect?): String = value?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "-"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
