package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import java.util.Locale

/**
 * Reusable, privacy-preserving text index for the node inspector's structural filter.
 * Only class names and resource IDs are indexed; node text and descriptions are never read.
 */
class NodeTreeFilterIndex(nodes: List<AccessibilityNodeSnapshot>) {
    private data class Entry(
        val node: AccessibilityNodeSnapshot,
        val normalizedClassName: String,
        val normalizedViewId: String
    )

    private val entries = nodes.map { node ->
        Entry(
            node = node,
            normalizedClassName = node.className.lowercase(Locale.ROOT),
            normalizedViewId = node.viewId.lowercase(Locale.ROOT)
        )
    }

    val nodeCount: Int get() = entries.size

    /**
     * Searches the complete bounded snapshot but retains at most [maxResults] nodes for UI work.
     * `matchedCount` is exact because the full in-memory index is scanned.
     */
    fun search(keyword: String, maxResults: Int): NodeTreeFilterResult {
        require(maxResults > 0) { "maxResults must be positive" }
        val started = System.nanoTime()
        val normalized = keyword.trim().lowercase(Locale.ROOT)
        val matches = ArrayList<AccessibilityNodeSnapshot>(minOf(maxResults, entries.size))
        var matchedCount = 0
        entries.forEach { entry ->
            val matchesKeyword = normalized.isEmpty() ||
                entry.normalizedClassName.contains(normalized) ||
                entry.normalizedViewId.contains(normalized)
            if (matchesKeyword) {
                matchedCount++
                if (matches.size < maxResults) matches += entry.node
            }
        }
        return NodeTreeFilterResult(
            nodes = matches,
            matchedCount = matchedCount,
            matchCountExact = true,
            scannedNodeCount = entries.size,
            truncated = matchedCount > matches.size,
            elapsedMs = ((System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L)
        )
    }
}

data class NodeTreeFilterResult(
    val nodes: List<AccessibilityNodeSnapshot>,
    val matchedCount: Int,
    val matchCountExact: Boolean,
    val scannedNodeCount: Int,
    val truncated: Boolean,
    val elapsedMs: Long
)
