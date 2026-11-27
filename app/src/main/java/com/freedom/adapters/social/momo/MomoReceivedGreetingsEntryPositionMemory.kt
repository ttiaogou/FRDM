package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy

/** List-content direction, not finger/swipe direction and never a click authorization. */
internal enum class MomoReceivedGreetingsEntryPosition {
    VISIBLE, ABOVE, BELOW, UNKNOWN
}

/**
 * Run-local hint derived exclusively from the existing ordinary-inbox row extraction.
 * No nodes, gesture results, entry coordinates, or durable contact IDs are retained.
 * An overlapping, ordered viewport with actual row displacement establishes a crossing;
 * a missing label or a completed gesture alone does not.
 */
internal class MomoReceivedGreetingsEntryPositionMemory {
    private data class Anchor(val key: String, val top: Int, val bottom: Int)

    private data class Viewport(
        val surface: Pair<String, String>,
        val windowKey: String,
        val bounds: ScreenRect,
        val anchors: List<Anchor>,
        val collectionCount: Int?,
        val collectionRange: IntRange?,
        val entryAboveKeys: Set<String>,
        val entryBelowKeys: Set<String>,
        val position: MomoReceivedGreetingsEntryPosition
    )

    private enum class Movement { TOWARD_END, TOWARD_START, UNCHANGED, UNPROVEN }

    private var active = false
    private var lastConfirmed: Viewport? = null

    @Volatile
    var position: MomoReceivedGreetingsEntryPosition = MomoReceivedGreetingsEntryPosition.UNKNOWN
        private set

    @Synchronized
    fun beginRun() {
        clear()
        active = true
    }

    @Synchronized
    fun endRun() {
        active = false
        clear()
    }

    @Synchronized
    fun observe(snapshot: ChatPageSnapshot): MomoReceivedGreetingsEntryPosition {
        // Child pages and temporary UNKNOWN observations neither establish nor erase a list position.
        if (!active || snapshot.pageKind != ChatPageKind.CHAT_LIST) return position

        val bounds = snapshot.chatListViewport
        val windowKey = snapshot.windowIdentityKey()
        if (bounds == null || bounds.isEmpty || windowKey == null ||
            snapshot.chatListExtractionTruncated || snapshot.chatListInvalidRowBoundsCount != 0 ||
            snapshot.chatListCandidates.isEmpty()
        ) {
            // Incomplete evidence cannot authorize a direction. A later complete observation
            // may still revalidate the previous viewport, without reusing any tap target.
            position = MomoReceivedGreetingsEntryPosition.UNKNOWN
            return position
        }

        val candidates = snapshot.chatListCandidates
        val entries = candidates.filter(::isEntry)
        if (entries.size > 1 || (entries.size == 1 && !hasVisibleName(entries.single()))) {
            clear()
            return position
        }
        val entry = entries.singleOrNull()
        val anchors = candidates.asSequence()
            .filter { !isEntry(it) && hasVisibleName(it) }
            .mapNotNull { row ->
                val key = ChatSessionManagementPolicy.stableSessionKeyForTitle(row.nameText)
                if (key.isBlank()) null else (row.selectionClass + ":" + key) to row
            }
            // Reject ambiguous titles before filtering clipped rows: a duplicate at the
            // edge of the viewport must not turn another holder into a unique anchor.
            .groupBy { it.first }.values.mapNotNull { it.singleOrNull() }
            .filter { (_, row) -> row.fullyInsideViewport && !row.rowClippedAtViewportEdge }
            .map { (key, row) -> Anchor(key, row.rowBounds.top, row.rowBounds.bottom) }
            .sortedBy { it.top }

        // Reuse the optional collection range already projected by the extractor as
        // corroboration, not as an entry identity or a stored row index.
        val count = snapshot.chatListCollectionRowCount
        val indices = snapshot.chatListVisibleCollectionRows
        val range = if (count != null && count > 0 && indices.isNotEmpty() &&
            indices.distinct().size == indices.size && indices.all { it in 0 until count } &&
            indices.zipWithNext().all { (a, b) -> b == a + 1 }
        ) indices.first()..indices.last() else null

        val current = Viewport(
            surface = snapshot.packageName to snapshot.activityClass,
            windowKey = windowKey,
            bounds = bounds,
            anchors = anchors,
            collectionCount = count,
            collectionRange = range,
            entryAboveKeys = if (entry == null) emptySet() else anchors
                .filter { it.bottom <= entry.rowBounds.top }.map { it.key }.toSet(),
            entryBelowKeys = if (entry == null) emptySet() else anchors
                .filter { it.top >= entry.rowBounds.bottom }.map { it.key }.toSet(),
            position = MomoReceivedGreetingsEntryPosition.UNKNOWN
        )
        position = if (entry != null) MomoReceivedGreetingsEntryPosition.VISIBLE
        else lastConfirmed?.let { infer(it, current) } ?: MomoReceivedGreetingsEntryPosition.UNKNOWN
        lastConfirmed = current.copy(position = position)
        return position
    }

    private fun infer(before: Viewport, after: Viewport): MomoReceivedGreetingsEntryPosition {
        val unknown = MomoReceivedGreetingsEntryPosition.UNKNOWN
        if (before.position == unknown || before.surface != after.surface || before.bounds != after.bounds ||
            (before.collectionCount != null && after.collectionCount != null &&
                before.collectionCount != after.collectionCount)
        ) return unknown

        val oldKeys = before.anchors.map { it.key }
        val newKeys = after.anchors.map { it.key }
        val common = oldKeys.filter { it in newKeys }
        if (common.isEmpty() || newKeys.filter { it in common } != common) return unknown
        val oldByKey = before.anchors.associateBy { it.key }
        val newByKey = after.anchors.associateBy { it.key }
        val deltas = common.flatMap {
            val old = oldByKey.getValue(it)
            val new = newByKey.getValue(it)
            listOf(new.top - old.top, new.bottom - old.bottom)
        }

        val rangeMovement = collectionMovement(before, after)
        val movement = when {
            oldKeys == newKeys && deltas.all { it == 0 } -> Movement.UNCHANGED
            deltas.all { it < 0 } && oldKeys.takeLast(common.size) == common &&
                newKeys.take(common.size) == common -> Movement.TOWARD_END
            deltas.all { it > 0 } && oldKeys.take(common.size) == common &&
                newKeys.takeLast(common.size) == common -> Movement.TOWARD_START
            else -> Movement.UNPROVEN
        }
        // A single common nickname is weak evidence; require the independent live
        // collection movement in the same window to corroborate it. Never infer a
        // crossing from a collection index or a gesture direction alone.
        if (movement == Movement.UNPROVEN ||
            (common.size < 2 && (rangeMovement != movement || movement == Movement.UNCHANGED)) ||
            (rangeMovement != Movement.UNPROVEN && rangeMovement != movement &&
                rangeMovement != Movement.UNCHANGED)
        ) return unknown

        if (before.position == MomoReceivedGreetingsEntryPosition.VISIBLE) {
            return when {
                movement == Movement.TOWARD_END && common.all { it in before.entryBelowKeys } ->
                    MomoReceivedGreetingsEntryPosition.ABOVE
                movement == Movement.TOWARD_START && common.all { it in before.entryAboveKeys } ->
                    MomoReceivedGreetingsEntryPosition.BELOW
                else -> unknown
            }
        }
        // Continuous overlapping observations cannot cross the remembered entry without
        // seeing it again. Reordering, disjoint viewports, or a different surface retire the hint.
        return before.position
    }

    private fun collectionMovement(before: Viewport, after: Viewport): Movement {
        val old = before.collectionRange ?: return Movement.UNPROVEN
        val new = after.collectionRange ?: return Movement.UNPROVEN
        if (before.windowKey != after.windowKey || before.collectionCount != after.collectionCount) {
            return Movement.UNPROVEN
        }
        return when {
            new.first > old.first && new.last > old.last -> Movement.TOWARD_END
            new.first < old.first && new.last < old.last -> Movement.TOWARD_START
            new == old -> Movement.UNCHANGED
            else -> Movement.UNPROVEN
        }
    }

    private fun isEntry(row: ChatListCandidateSnapshot): Boolean =
        row.selectionClass == MomoChatListRowClassification.SYSTEM_ENTRY &&
            row.nameText?.trim() == "收到的招呼"

    private fun hasVisibleName(row: ChatListCandidateSnapshot): Boolean =
        row.rowVisibleToUser && row.rowBoundsValid && row.intersectsViewport &&
            row.nameNodeVisibleToUser && row.nameBoundsValid && row.nameBoundsIntersectViewport &&
            !row.nameText.isNullOrBlank()

    private fun clear() {
        lastConfirmed = null
        position = MomoReceivedGreetingsEntryPosition.UNKNOWN
    }
}
