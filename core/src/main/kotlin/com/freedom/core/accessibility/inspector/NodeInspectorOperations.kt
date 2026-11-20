package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef

/** Stable identity for a node inside one captured accessibility window. */
data class NodeInspectorIdentity(
    val window: WindowRef,
    val path: NodePath
)

data class NodeInspectorEntry(
    val identity: NodeInspectorIdentity,
    val bounds: ScreenRect
)

/** Pure selection/hide operations shared by the interactive bounds inspector and JVM tests. */
object NodeInspectorOperations {
    fun parentOf(
        selected: NodeInspectorIdentity,
        entries: List<NodeInspectorEntry>
    ): NodeInspectorIdentity? {
        val parentPath = selected.path.childIndices.dropLast(1)
        if (selected.path.childIndices.isEmpty()) return null
        return entries.firstOrNull { entry ->
            entry.identity.window == selected.window &&
                entry.identity.path.childIndices == parentPath
        }?.identity
    }

    fun sameBounds(
        selected: NodeInspectorIdentity,
        bounds: ScreenRect,
        entries: List<NodeInspectorEntry>
    ): Set<NodeInspectorIdentity> = entries.asSequence()
        .filter { it.identity.window == selected.window && it.bounds == bounds }
        .map { it.identity }
        .toSet()

    fun visibleEntries(
        entries: List<NodeInspectorEntry>,
        hidden: Set<NodeInspectorIdentity>,
        window: WindowRef? = null
    ): List<NodeInspectorEntry> = entries.filter { entry ->
        entry.identity !in hidden && (window == null || entry.identity.window == window)
    }
}
