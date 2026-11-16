package com.freedom.core.accessibility.overlay

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect

/** Platform-neutral node hit target used by the interactive layout inspector overlay. */
data class NodeBoundsHitCandidate(
    val windowId: Int,
    val path: NodePath,
    val bounds: ScreenRect,
    /** Position in the captured-window list; later windows are treated as visually above earlier ones. */
    val windowOrder: Int = 0
)

/** Resolves overlapping node rectangles to the most specific node under a screen coordinate. */
object NodeBoundsHitTester {
    fun find(
        candidates: List<NodeBoundsHitCandidate>,
        screenX: Int,
        screenY: Int
    ): NodeBoundsHitCandidate? = candidates.asSequence()
        .filter { candidate ->
            val bounds = candidate.bounds
            !bounds.isEmpty && screenX >= bounds.left && screenX < bounds.right &&
                screenY >= bounds.top && screenY < bounds.bottom
        }
        .minWithOrNull(
            compareBy<NodeBoundsHitCandidate> { it.bounds.width.toLong() * it.bounds.height.toLong() }
                .thenByDescending { it.path.childIndices.size }
                .thenByDescending { it.windowOrder }
                .thenBy { it.windowId }
        )
}
