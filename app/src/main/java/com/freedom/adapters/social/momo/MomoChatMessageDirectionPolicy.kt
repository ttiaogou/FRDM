package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.DirectionEvidence
import java.util.Locale

/** A bounded, row-local node identity and path captured while traversing one message_root row. */
internal data class MomoChatMessageLayoutNodeEvidence(
    val viewId: String,
    val className: String,
    val path: List<Int>,
    val viewIdTrail: List<String>
)

/** Direction and provenance resolved for one specific body node. */
internal data class MomoChatMessageDirectionResolution(
    /** Null means no explicit layout hint, allowing the parser's independent geometry fallback. */
    val direction: ChatMessageDirection?,
    val evidence: DirectionEvidence,
    val sourceViewId: String? = null,
    val messageRootPath: List<Int>? = null,
    val reason: String
)

/**
 * Momo-specific sender-direction evidence. Each body is resolved independently against avatar
 * markers in its nearest message_root. Markers from another message_root in the same RecyclerView
 * row are ignored; multiple markers in the same root remain ambiguous.
 */
internal object MomoChatMessageDirectionPolicy {
    private val outgoingHints = setOf(
        "msg_right", "message_right", "right_message", "send_msg", "sent_msg",
        "outgoing", "sender", "mine", "self", "message_layout_leftcontainer"
    )
    private val incomingHints = setOf(
        "msg_left", "message_left", "left_message", "receive_msg", "received_msg",
        "incoming", "receiver", "other", "message_layout_rightcontainer"
    )

    private const val MESSAGE_ROOT_ID = "message_root"
    private const val MESSAGE_CONTENT_LAYOUT_ID = "message_content_layout"
    private const val INCOMING_AVATAR_CONTAINER_ID = "message_iv_avatar_container"
    private const val OUTGOING_AVATAR_CONTAINER_ID = "message_layout_avatarcontainer"

    fun hasConflictingAvatarContainerMarkers(
        rowNodes: List<MomoChatMessageLayoutNodeEvidence>
    ): Boolean = rowNodes.asSequence()
        .filter { node ->
            resourceEntryName(node.viewId) in setOf(
                INCOMING_AVATAR_CONTAINER_ID, OUTGOING_AVATAR_CONTAINER_ID
            ) && isRelativeLayout(node.className)
        }
        .mapNotNull { node -> messageRootPath(node.path, node.viewIdTrail) }
        .groupingBy { it }
        .eachCount()
        .values
        .any { count -> count > 1 }

    fun resolveForBody(
        bodyPath: List<Int>,
        bodyViewIdTrail: List<String>,
        rowNodes: List<MomoChatMessageLayoutNodeEvidence>
    ): MomoChatMessageDirectionResolution {
        val bodyRootPath = messageRootPath(bodyPath, bodyViewIdTrail)
            ?: return unresolved("BODY_MESSAGE_ROOT_NOT_FOUND")
        val rootIndex = bodyViewIdTrail.indexOfLast {
            resourceEntryName(it) == MESSAGE_ROOT_ID
        }
        val hasContentLayout = bodyViewIdTrail.drop(rootIndex + 1).any {
            resourceEntryName(it) == MESSAGE_CONTENT_LAYOUT_ID
        }
        if (!hasContentLayout) return unresolved("BODY_CONTENT_LAYOUT_NOT_FOUND", bodyRootPath)
        val scopedAncestorIds = bodyViewIdTrail.drop(rootIndex)
        if (hasConflictingAncestorDirectionHints(scopedAncestorIds)) {
            return unknown("CONFLICTING_ANCESTOR_DIRECTION_HINTS", bodyRootPath)
        }

        val sameRootMarkers = rowNodes.mapNotNull { node ->
            if (!isRelativeLayout(node.className)) return@mapNotNull null
            val direction = when (resourceEntryName(node.viewId)) {
                INCOMING_AVATAR_CONTAINER_ID -> ChatMessageDirection.INCOMING
                OUTGOING_AVATAR_CONTAINER_ID -> ChatMessageDirection.OUTGOING
                else -> return@mapNotNull null
            }
            val markerRoot = messageRootPath(node.path, node.viewIdTrail)
                ?: return@mapNotNull null
            if (markerRoot != bodyRootPath) return@mapNotNull null
            Triple(node, direction, markerRoot)
        }
        if (sameRootMarkers.size > 1) {
            return unknown("MULTIPLE_AVATAR_MARKERS_IN_MESSAGE_ROOT", bodyRootPath)
        }
        if (sameRootMarkers.size == 1) {
            val (marker, direction, rootPath) = sameRootMarkers.single()
            val ancestorDirection = fromAncestorViewIds(scopedAncestorIds)
            if (ancestorDirection != null && ancestorDirection != direction) {
                return unknown("AVATAR_ANCESTOR_DIRECTION_CONFLICT", rootPath)
            }
            return MomoChatMessageDirectionResolution(
                direction = direction,
                evidence = DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
                sourceViewId = marker.viewId,
                messageRootPath = rootPath,
                reason = "SAME_MESSAGE_ROOT_AVATAR_CONTAINER"
            )
        }

        val ancestorDirection = fromAncestorViewIds(scopedAncestorIds)
        return if (ancestorDirection != null) {
            MomoChatMessageDirectionResolution(
                direction = ancestorDirection,
                evidence = DirectionEvidence.EXPLICIT_LAYOUT_HINT,
                sourceViewId = bodyViewIdTrail.lastOrNull(),
                messageRootPath = bodyRootPath,
                reason = "BODY_ANCESTOR_LAYOUT_HINT"
            )
        } else {
            unresolved("NO_DIRECTION_LAYOUT_EVIDENCE", bodyRootPath)
        }
    }

    /** Compatibility wrapper retained for existing callers and focused unit tests. */
    fun fromObservedAvatarContainerLayout(
        bodyPath: List<Int>,
        bodyViewIdTrail: List<String>,
        rowNodes: List<MomoChatMessageLayoutNodeEvidence>
    ): ChatMessageDirection? = resolveForBody(bodyPath, bodyViewIdTrail, rowNodes)
        .direction.takeUnless { it == ChatMessageDirection.UNKNOWN }

    /** Legacy explicit direction IDs on the body ancestor chain, kept as a semantic fallback. */
    fun fromAncestorViewIds(viewIds: List<String>): ChatMessageDirection? {
        val joined = viewIds.joinToString(" ").lowercase(Locale.ROOT)
        val outgoing = outgoingHints.any(joined::contains)
        val incoming = incomingHints.any(joined::contains)
        return when {
            outgoing && !incoming -> ChatMessageDirection.OUTGOING
            incoming && !outgoing -> ChatMessageDirection.INCOMING
            else -> null
        }
    }

    private fun messageRootPath(path: List<Int>, viewIdTrail: List<String>): List<Int>? {
        // The reader records exactly one viewIdTrail entry for every path node plus its row root.
        if (viewIdTrail.size != path.size + 1) return null
        val rootIndex = viewIdTrail.indexOfLast {
            resourceEntryName(it) == MESSAGE_ROOT_ID
        }
        if (rootIndex < 0 || rootIndex > path.size) return null
        return path.take(rootIndex)
    }

    private fun isRelativeLayout(className: String): Boolean =
        className.substringAfterLast('.').equals("relativelayout", ignoreCase = true)

    private fun hasConflictingAncestorDirectionHints(viewIds: List<String>): Boolean {
        val joined = viewIds.joinToString(" ").lowercase(Locale.ROOT)
        return outgoingHints.any(joined::contains) && incomingHints.any(joined::contains)
    }

    private fun unresolved(reason: String, rootPath: List<Int>? = null) =
        MomoChatMessageDirectionResolution(
            direction = null,
            evidence = DirectionEvidence.AMBIGUOUS,
            messageRootPath = rootPath,
            reason = reason
        )

    private fun unknown(reason: String, rootPath: List<Int>? = null) =
        MomoChatMessageDirectionResolution(
            direction = ChatMessageDirection.UNKNOWN,
            evidence = DirectionEvidence.AMBIGUOUS,
            messageRootPath = rootPath,
            reason = reason
        )

    private fun resourceEntryName(rawId: String): String = rawId
        .substringAfterLast('/')
        .substringAfterLast(':')
        .lowercase(Locale.ROOT)
}
