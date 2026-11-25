package com.freedom.features.accountbrowse.ports

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.api.ScreenRect

/** A 1-based position in a photo sequence; never a page fingerprint. */
data class ImageSequencePosition(val current: Int, val total: Int) {
    init {
        require(total > 0 && current in 1..total)
    }

    companion object {
        private val counterPattern = Regex("""([0-9]+)\s*/\s*([0-9]+)""")

        fun parse(text: String): ImageSequencePosition? {
            val match = counterPattern.matchEntire(text.trim()) ?: return null
            val current = match.groupValues[1].toIntOrNull() ?: return null
            val total = match.groupValues[2].toIntOrNull() ?: return null
            if (total <= 0 || current !in 1..total) return null
            return ImageSequencePosition(current, total)
        }
    }
}

enum class ImageIndexUnavailableReason {
    NOT_SUPPORTED,
    WINDOW_NOT_CONFIRMED,
    INDEX_MISSING,
    INDEX_AMBIGUOUS,
    INDEX_INVALID,
    NODE_READ_FAILED
}

/** Read-only, short-lived evidence. Consumers must reacquire it before a later UI action. */
sealed interface ImageIndexReadResult {
    data class Confirmed(
        val position: ImageSequencePosition,
        val pageFingerprint: String,
        val targetWindow: WindowRef,
        /** Fresh viewport from the same verified root; not part of page identity. */
        val viewport: ScreenRect? = null
    ) : ImageIndexReadResult

    data class Unavailable(val reason: ImageIndexUnavailableReason) : ImageIndexReadResult

    companion object {
        /** Input must consist only of freshly read, visible exact-ID nodes from the verified window. */
        fun fromVisibleLabels(
            labels: List<String>,
            pageFingerprint: String,
            targetWindow: WindowRef
        ): ImageIndexReadResult {
            if (pageFingerprint.isBlank() || targetWindow.packageName.isBlank() ||
                targetWindow.windowId < 0 || targetWindow.generation < 0
            ) return Unavailable(ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED)
            if (labels.isEmpty()) return Unavailable(ImageIndexUnavailableReason.INDEX_MISSING)
            if (labels.size != 1) return Unavailable(ImageIndexUnavailableReason.INDEX_AMBIGUOUS)
            val position = ImageSequencePosition.parse(labels.single())
                ?: return Unavailable(ImageIndexUnavailableReason.INDEX_INVALID)
            return Confirmed(position, pageFingerprint, targetWindow)
        }
    }
}
