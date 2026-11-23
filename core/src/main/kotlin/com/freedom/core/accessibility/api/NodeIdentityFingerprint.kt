package com.freedom.core.accessibility.api

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Privacy-preserving identity evidence for a short-lived accessibility target.
 * Text and content descriptions are represented only by process-keyed HMAC-SHA-256 digests; the original values are
 * never retained by this object. This fingerprint is an in-memory validation aid, not a durable
 * user or conversation identity.
 */
data class NodeIdentityFingerprint(
    val className: String?,
    val viewId: String?,
    val textDigest: String?,
    val contentDescriptionDigest: String?,
    val childCount: Int?,
    val parentClassName: String?,
    val parentViewId: String?,
    val windowId: Int = -1
) {
    /** Relocation requires a class plus at least one stable, non-positional identity attribute. */
    val isStrongEnoughForRelocation: Boolean
        get() = !className.isNullOrBlank() &&
            (!viewId.isNullOrBlank() || textDigest != null || contentDescriptionDigest != null)

    /** All populated identity fields must agree. Position and bounds are intentionally excluded. */
    fun matches(candidate: NodeIdentityFingerprint): Boolean =
        (className == null || className == candidate.className) &&
            (viewId == null || viewId == candidate.viewId) &&
            (textDigest == null || textDigest == candidate.textDigest) &&
            (contentDescriptionDigest == null || contentDescriptionDigest == candidate.contentDescriptionDigest) &&
            (childCount == null || childCount == candidate.childCount) &&
            (parentClassName == null || parentClassName == candidate.parentClassName) &&
            (parentViewId == null || parentViewId == candidate.parentViewId) &&
            (windowId < 0 || windowId == candidate.windowId)

    companion object {
        fun create(
            className: String?,
            viewId: String?,
            text: String?,
            contentDescription: String?,
            childCount: Int?,
            parentClassName: String?,
            parentViewId: String?,
            windowId: Int = -1
        ): NodeIdentityFingerprint = NodeIdentityFingerprint(
            className = className?.takeIf(String::isNotBlank),
            viewId = viewId?.takeIf(String::isNotBlank),
            textDigest = digest(text),
            contentDescriptionDigest = digest(contentDescription),
            childCount = childCount,
            parentClassName = parentClassName?.takeIf(String::isNotBlank),
            parentViewId = parentViewId?.takeIf(String::isNotBlank),
            windowId = windowId
        )

        private fun digest(value: String?): String? {
            val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            // Mac is mutable and not thread-safe. Reuse one initialized instance per caller thread
            // to avoid constructing a provider and key spec for every node in a large tree scan.
            val bytes = processMac.get().doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
            val encoded = CharArray(bytes.size * 2)
            bytes.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                encoded[index * 2] = HEX_CHARS[unsigned ushr 4]
                encoded[index * 2 + 1] = HEX_CHARS[unsigned and 0x0f]
            }
            return String(encoded)
        }

        // A process-local key prevents low-entropy names/labels from being guessed from a digest
        // if a target is accidentally included in a diagnostic string. It is never persisted.
        private val processKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
        private val processMac = ThreadLocal.withInitial {
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(processKey, "HmacSHA256"))
            }
        }
    }
}

/** Whether an action target may be relocated when its original child-index path no longer matches. */
enum class NodeTargetRelocationPolicy {
    /** Preserve legacy behavior: validate only the original path. */
    PATH_ONLY,

    /** Permit a unique fingerprint match within the exact same window generation. */
    UNIQUE_MATCH_IN_SAME_WINDOW
}
