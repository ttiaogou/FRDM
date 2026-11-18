package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.query.NodeTreeIndex
import java.util.Locale

/** A short-lived, geometry-bounded target resolved from one complete accessibility snapshot. */
internal data class MomoSayHiTapTarget(
    val path: NodePath,
    val targetBounds: ScreenRect,
    val preferredBounds: ScreenRect,
    /** Exact protected component geometry observed in the snapshot used to resolve a row tap. */
    val excludedBounds: List<ScreenRect> = emptyList()
)

/**
 * Resolves the avatar identity target independently from the larger received-greeting row target.
 * The row target keeps the avatar area eligible: an accidental avatar hit is detected as a profile
 * navigation and safely returned from. Only independent function controls are removed from the
 * row-entry hit area.
 */
internal object MomoSayHiAvatarTargetPolicy {
    private val avatarContainerIds = setOf("userlist_item_iv_face", "chatlist_item_iv_face")
    private val avatarImageIds = setOf("img_avatar", "icon")

    /** Resolves the other party's left-side message avatar in an already opened chat. */
    fun resolveConversationAvatarTarget(index: NodeTreeIndex): MomoSayHiTapTarget? {
        val container = index.snapshot.nodes.lastOrNull { node ->
            node.visible && localId(node.viewId) == "message_iv_avatar_container" &&
                !node.bounds.isEmpty && node.bounds.left < 180
        } ?: return null
        val image = index.descendantsOf(container.path, includeSelf = true).firstOrNull { node ->
            node.visible && localId(node.viewId) == "message_iv_userphoto" && !node.bounds.isEmpty
        } ?: return null
        return MomoSayHiTapTarget(image.path, image.bounds, image.bounds)
    }
    fun resolveAvatarTarget(
        index: NodeTreeIndex,
        rowPath: NodePath
    ): MomoSayHiTapTarget? {
        val row = index.nodeAt(rowPath) ?: return null
        val descendants = index.descendantsOf(rowPath, includeSelf = false).filter { it.visible }
        val candidates = descendants.filter { localId(it.viewId) in avatarContainerIds }
        if (candidates.size == 1) {
            val avatarContainer = candidates.single()
            if (avatarContainer.bounds.isEmpty || !contains(row.bounds, avatarContainer.bounds)) return null
            val direct = avatarContainer.takeIf { it.clickable }
            val ancestor = if (direct == null) {
                index.ancestorsOf(avatarContainer.path).firstOrNull { candidate ->
                    candidate.clickable && isWithin(candidate.path, rowPath) &&
                        isCompactAvatarAncestor(candidate.bounds, avatarContainer.bounds, row.bounds)
                }
            } else null
            val clickNode = direct ?: ancestor ?: return null
            if (!contains(row.bounds, clickNode.bounds) || clickNode.bounds.isEmpty) return null
            if (clickNode.bounds.width > avatarContainer.bounds.width * 2.5f ||
                clickNode.bounds.height > avatarContainer.bounds.height * 2.5f
            ) return null
            return MomoSayHiTapTarget(
                path = clickNode.path,
                targetBounds = clickNode.bounds,
                preferredBounds = avatarContainer.bounds
            )
        }
        if (candidates.size > 1) return null

        // The current NewSayHiSessionListActivity exposes the avatar directly as ImageView[id=icon]
        // inside clickable card_container. The image itself is not clickable, so tap its own
        // compact bounds rather than accepting the entire row-sized clickable ancestor.
        val imageCandidates = descendants.filter { localId(it.viewId) in avatarImageIds }
        if (imageCandidates.size != 1) return null
        val image = imageCandidates.single()
        if (image.bounds.isEmpty || !contains(row.bounds, image.bounds)) return null
        if (image.bounds.width > row.bounds.width * 0.45f ||
            image.bounds.height > row.bounds.height * 0.9f
        ) return null
        return MomoSayHiTapTarget(
            path = image.path,
            targetBounds = image.bounds,
            preferredBounds = image.bounds
        )
    }

    fun resolveRowTarget(
        index: NodeTreeIndex,
        rowPath: NodePath,
        namePath: NodePath
    ): MomoSayHiTapTarget? {
        val row = index.nodeAt(rowPath) ?: return null
        val name = index.nodeAt(namePath) ?: return null
        if (!name.visible || !contains(row.bounds, name.bounds) || !isWithin(namePath, rowPath)) {
            return null
        }

        val ancestors = index.ancestorsOf(rowPath)
        val listRoot = (sequenceOf(row) + ancestors.asSequence()).firstOrNull {
            localId(it.viewId) == localId(MomoSayHiPageContract.LIST_VIEW_ID)
        } ?: return null
        val rowClickNode = (sequenceOf(row) + ancestors.asSequence()).firstOrNull { candidate ->
            candidate.visible && candidate.enabled && candidate.clickable &&
                isWithin(candidate.path, listRoot.path) &&
                contains(candidate.bounds, row.bounds) &&
                candidate.bounds.width <= row.bounds.width * 1.25f + 16f &&
                candidate.bounds.height <= row.bounds.height * 1.5f + 16f
        } ?: return null

        // Use the exact recognized row bounds, constrained by the clickable row container.
        // This preserves the full user-row region without expanding into adjacent list items.
        val baseBounds = intersection(rowClickNode.bounds, row.bounds) ?: return null
        if (baseBounds.isEmpty) return null
        val excludedBounds = index.descendantsOf(rowClickNode.path, includeSelf = false)
            .asSequence()
            .filter { candidate -> candidate.visible && !candidate.bounds.isEmpty }
            .filter { candidate -> overlaps(baseBounds, candidate.bounds) }
            .filter { candidate ->
                // A compact image without its own resource ID may still be the image child
                // of an identified avatar container. Preserve the avatar itself, but not
                // separately identified status dots or other protected overlays inside it.
                val avatarComponent = isAvatarComponent(candidate) ||
                    index.ancestorsOf(candidate.path).any(::isAvatarComponent)
                (candidate.clickable && !avatarComponent) ||
                    isProtectedRowComponent(candidate) ||
                    (isCompactImageControl(candidate) && !avatarComponent)
            }
            .map { candidate -> intersection(baseBounds, candidate.bounds) }
            .filterNotNull()
            .distinct()
            .toList()

        val safeBounds = largestSafeTapRectangle(baseBounds, excludedBounds) ?: return null
        return MomoSayHiTapTarget(
            path = rowClickNode.path,
            targetBounds = safeBounds,
            preferredBounds = safeBounds,
            excludedBounds = excludedBounds
        )
    }

    private fun largestSafeTapRectangle(
        base: ScreenRect,
        obstacles: List<ScreenRect>
    ): ScreenRect? {
        val xs = sortedSetOf(base.left, base.right)
        val ys = sortedSetOf(base.top, base.bottom)
        obstacles.forEach { obstacle ->
            xs += obstacle.left.coerceIn(base.left, base.right)
            xs += obstacle.right.coerceIn(base.left, base.right)
            ys += obstacle.top.coerceIn(base.top, base.bottom)
            ys += obstacle.bottom.coerceIn(base.top, base.bottom)
        }
        val x = xs.toList()
        val y = ys.toList()
        var best: ScreenRect? = null
        var bestArea = 0L
        for (leftIndex in 0 until x.lastIndex) {
            for (rightIndex in leftIndex + 1 until x.size) {
                val left = x[leftIndex]
                val right = x[rightIndex]
                if (right - left < MIN_SAFE_TAP_WIDTH) continue
                for (topIndex in 0 until y.lastIndex) {
                    for (bottomIndex in topIndex + 1 until y.size) {
                        val top = y[topIndex]
                        val bottom = y[bottomIndex]
                        if (bottom - top < MIN_SAFE_TAP_HEIGHT) continue
                        val candidate = ScreenRect(left, top, right, bottom)
                        if (obstacles.any { overlaps(candidate, it) }) continue
                        val area = candidate.width.toLong() * candidate.height.toLong()
                        if (area > bestArea) {
                            best = candidate
                            bestArea = area
                        }
                    }
                }
            }
        }
        return best
    }

    /** Avatar nodes stay eligible for a row-area tap; a profile Activity is handled by the
     * navigation guard and returned from once. This predicate prevents their geometry from being
     * subtracted as if they were VIP/wealth/shortcut controls.
     */
    private fun isAvatarComponent(node: AccessibilityNodeSnapshot): Boolean {
        val id = localId(node.viewId)
        return id in avatarContainerIds || id in avatarImageIds ||
            id in setOf("avatar_container", "avatar_layout", "img_avatar")
    }

    /**
     * Some small row shortcuts expose no resource ID/clickable flag in the accessibility tree.
     * Exclude compact ImageView/ImageButton children as a conservative fallback; this covers the
     * small green shortcut observed beside the profile label without relying on screen coordinates.
     */
    private fun isCompactImageControl(node: AccessibilityNodeSnapshot): Boolean {
        val className = node.className.substringAfterLast('.').lowercase(Locale.ROOT)
        return className in setOf("imageview", "imagebutton") &&
            node.bounds.width in 1..64 && node.bounds.height in 1..64
    }

    private fun isProtectedRowComponent(node: AccessibilityNodeSnapshot): Boolean {
        val id = localId(node.viewId).lowercase(Locale.ROOT)
        return id in PROTECTED_ROW_COMPONENT_IDS || isVipOrWealthComponent(node)
    }

    private fun isVipOrWealthComponent(node: AccessibilityNodeSnapshot): Boolean {
        val id = localId(node.viewId).lowercase(Locale.ROOT)
        val label = sequenceOf(node.text, node.contentDescription)
            .filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        return id.contains("vip") || id.contains("wealth") || id.contains("rich_level") ||
            id.contains("level_badge") || label.contains("svip") || label.contains("vip") ||
            label.contains("财富") || label.contains("等级")
    }

    private val PROTECTED_ROW_COMPONENT_IDS = setOf(
        // These containers cover the full visible wealth/VIP badge strip, including portions
        // that are non-clickable or expose no text in the accessibility snapshot.
        "userlist_item_layout_badgecontainer",
        "view_badge",
        // Independently rendered status/marker widgets must remain excluded even when they are
        // non-clickable and are nested inside the avatar subtree.
        "img_online_status_dot",
        "green_widget",
        "hilist_tv_red",
        "real_man_auth_icon"
    )

    private fun intersection(first: ScreenRect, second: ScreenRect): ScreenRect? {
        val result = ScreenRect(
            left = maxOf(first.left, second.left),
            top = maxOf(first.top, second.top),
            right = minOf(first.right, second.right),
            bottom = minOf(first.bottom, second.bottom)
        )
        return result.takeUnless { it.isEmpty }
    }

    fun sameTarget(first: MomoSayHiTapTarget, second: MomoSayHiTapTarget): Boolean =
        first.path == second.path && first.targetBounds == second.targetBounds &&
            first.preferredBounds == second.preferredBounds &&
            first.excludedBounds == second.excludedBounds

    private fun isCompactAvatarAncestor(
        candidate: ScreenRect,
        avatar: ScreenRect,
        row: ScreenRect
    ): Boolean {
        if (candidate.isEmpty || !contains(row, candidate) || !contains(candidate, avatar)) return false
        return candidate.width <= avatar.width * 2.5f && candidate.height <= avatar.height * 2.5f
    }

    private fun isWithin(path: NodePath, root: NodePath): Boolean =
        path.childIndices.size >= root.childIndices.size &&
            path.childIndices.take(root.childIndices.size) == root.childIndices

    private fun overlaps(first: ScreenRect, second: ScreenRect): Boolean =
        !first.isEmpty && !second.isEmpty && first.left < second.right &&
            first.right > second.left && first.top < second.bottom && first.bottom > second.top

    private const val MIN_SAFE_TAP_WIDTH = 24
    private const val MIN_SAFE_TAP_HEIGHT = 20

    private fun contains(outer: ScreenRect, inner: ScreenRect): Boolean =
        !outer.isEmpty && !inner.isEmpty && inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom

    private fun localId(viewId: String): String = viewId.substringAfterLast('/')
}
