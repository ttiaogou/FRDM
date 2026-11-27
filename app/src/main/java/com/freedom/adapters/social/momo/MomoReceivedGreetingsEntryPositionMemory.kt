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

/** Stable diagnostic codes only; no row titles, coordinates or contact identities. */
internal enum class MomoReceivedGreetingsPositionReason {
    RUN_RESET, ENTRY_VISIBLE, ENTRY_CROSSED, CONTINUOUS_VIEWPORT,
    PARTIAL_ANCHOR_SET_REVALIDATED, INCOMPLETE_VIEWPORT, WEAK_STATIONARY_OVERLAP,
    AMBIGUOUS_ENTRY, NO_CONFIRMED_BASELINE, SURFACE_CHANGED, VIEWPORT_CHANGED,
    COLLECTION_COUNT_CHANGED, DISJOINT_ANCHORS, ANCHOR_ORDER_CHANGED,
    MOVEMENT_UNPROVEN, INSUFFICIENT_ANCHORS, COLLECTION_MOVEMENT_CONFLICT,
    ENTRY_CROSSING_UNPROVEN
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

    private data class Inference(
        val position: MomoReceivedGreetingsEntryPosition,
        val reason: MomoReceivedGreetingsPositionReason,
        val retainBaseline: Boolean = false
    )

    private var active = false
    private var lastConfirmed: Viewport? = null

    @Volatile
    var position: MomoReceivedGreetingsEntryPosition = MomoReceivedGreetingsEntryPosition.UNKNOWN
        private set

    @Volatile
    var reason = MomoReceivedGreetingsPositionReason.RUN_RESET
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
            reason = MomoReceivedGreetingsPositionReason.INCOMPLETE_VIEWPORT
            position = MomoReceivedGreetingsEntryPosition.UNKNOWN
            return position
        }

        val candidates = snapshot.chatListCandidates
        val entries = candidates.filter(::isEntry)
        if (entries.size > 1 || (entries.size == 1 && !hasVisibleName(entries.single()))) {
            clear()
            reason = MomoReceivedGreetingsPositionReason.AMBIGUOUS_ENTRY
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
        val inference = if (entry != null) Inference(
            MomoReceivedGreetingsEntryPosition.VISIBLE, MomoReceivedGreetingsPositionReason.ENTRY_VISIBLE
        ) else lastConfirmed?.let { infer(it, current) } ?: Inference(
            MomoReceivedGreetingsEntryPosition.UNKNOWN, MomoReceivedGreetingsPositionReason.NO_CONFIRMED_BASELINE
        )
        // A held baseline is evidence for a future independent observation, never a
        // currently usable position. A contradictory complete observation retires it.
        if (!inference.retainBaseline) {
            lastConfirmed = current.copy(position = inference.position)
        }
        reason = inference.reason
        position = inference.position
        return position
    }

    private fun infer(before: Viewport, after: Viewport): Inference {
        val unknown = MomoReceivedGreetingsEntryPosition.UNKNOWN
        fun reject(reason: MomoReceivedGreetingsPositionReason, retain: Boolean = false) =
            Inference(unknown, reason, retain)
        if (before.position == unknown) return reject(MomoReceivedGreetingsPositionReason.NO_CONFIRMED_BASELINE)
        if (before.surface != after.surface) return reject(MomoReceivedGreetingsPositionReason.SURFACE_CHANGED)
        if (before.bounds != after.bounds) return reject(MomoReceivedGreetingsPositionReason.VIEWPORT_CHANGED)
        if (before.collectionCount != null && after.collectionCount != null &&
            before.collectionCount != after.collectionCount
        ) return reject(MomoReceivedGreetingsPositionReason.COLLECTION_COUNT_CHANGED)

        val oldKeys = before.anchors.map { it.key }
        val newKeys = after.anchors.map { it.key }
        val common = oldKeys.filter { it in newKeys }
        if (common.isEmpty()) return reject(MomoReceivedGreetingsPositionReason.DISJOINT_ANCHORS)
        if (newKeys.filter { it in common } != common) return reject(MomoReceivedGreetingsPositionReason.ANCHOR_ORDER_CHANGED)
        val oldByKey = before.anchors.associateBy { it.key }
        val newByKey = after.anchors.associateBy { it.key }
        val deltas = common.flatMap {
            val old = oldByKey.getValue(it)
            val new = newByKey.getValue(it)
            listOf(new.top - old.top, new.bottom - old.bottom)
        }

        val rangeMovement = collectionMovement(before, after)
        val knownOffscreen = before.position == MomoReceivedGreetingsEntryPosition.ABOVE ||
            before.position == MomoReceivedGreetingsEntryPosition.BELOW
        // Clipped/temporarily omitted anchors can shrink or expand the observed set
        // while the independently observed collection range and surviving rows stay put.
        // Never use this rule to infer the initial entry crossing or across windows.
        val stationarySubset = before.windowKey == after.windowKey &&
            deltas.all { it == 0 } &&
            (common.size == oldKeys.size || common.size == newKeys.size) &&
            rangeMovement in setOf(Movement.UNCHANGED, Movement.UNPROVEN)
        if (knownOffscreen && stationarySubset &&
            (oldKeys != newKeys || common.size < 2)
        ) {
            if (common.size >= 2 && rangeMovement == Movement.UNCHANGED) {
                return Inference(before.position, MomoReceivedGreetingsPositionReason.PARTIAL_ANCHOR_SET_REVALIDATED)
            }
            // No direction is authorized from weak evidence. Preserve only the previous
            // baseline until a later complete, independently overlapping observation.
            return reject(MomoReceivedGreetingsPositionReason.WEAK_STATIONARY_OVERLAP, retain = true)
        }
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
        if (movement == Movement.UNPROVEN)
            return reject(MomoReceivedGreetingsPositionReason.MOVEMENT_UNPROVEN)
        if (common.size < 2 && (rangeMovement != movement || movement == Movement.UNCHANGED))
            return reject(MomoReceivedGreetingsPositionReason.INSUFFICIENT_ANCHORS)
        if (rangeMovement != Movement.UNPROVEN && rangeMovement != movement &&
            rangeMovement != Movement.UNCHANGED)
            return reject(MomoReceivedGreetingsPositionReason.COLLECTION_MOVEMENT_CONFLICT)

        if (before.position == MomoReceivedGreetingsEntryPosition.VISIBLE) {
            return when {
                movement == Movement.TOWARD_END && common.all { it in before.entryBelowKeys } ->
                    Inference(MomoReceivedGreetingsEntryPosition.ABOVE, MomoReceivedGreetingsPositionReason.ENTRY_CROSSED)
                movement == Movement.TOWARD_START && common.all { it in before.entryAboveKeys } ->
                    Inference(MomoReceivedGreetingsEntryPosition.BELOW, MomoReceivedGreetingsPositionReason.ENTRY_CROSSED)
                else -> reject(MomoReceivedGreetingsPositionReason.ENTRY_CROSSING_UNPROVEN)
            }
        }
        // Continuous overlapping observations cannot cross the remembered entry without
        // seeing it again. Reordering, disjoint viewports, or a different surface retire the hint.
        return Inference(before.position, MomoReceivedGreetingsPositionReason.CONTINUOUS_VIEWPORT)
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
        reason = MomoReceivedGreetingsPositionReason.RUN_RESET
        position = MomoReceivedGreetingsEntryPosition.UNKNOWN
    }
}
