package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.ports.ChatUnreadEvidenceSource
import com.freedom.features.aireply.ports.ChatUnreadStatus

internal enum class MomoChatListTextSource {
    NODE_TEXT,
    CONTENT_DESCRIPTION,
    NONE
}

internal data class MomoChatListTextValue(
    val value: String?,
    val source: MomoChatListTextSource
)


internal enum class MomoStatusNewDisposition {
    NONE,
    UNREAD,
    READ,
    UNKNOWN_INVALID_BOUNDS,
    UNKNOWN_OUTSIDE_ROW,
    UNKNOWN_NOT_VISIBLE,
    READ_EMPTY_VALUE,
    UNKNOWN_NON_NUMERIC_VALUE
}

internal data class MomoChatUnreadEvidence(
    val status: ChatUnreadStatus = ChatUnreadStatus.UNKNOWN,
    val count: Int? = null,
    val source: ChatUnreadEvidenceSource = ChatUnreadEvidenceSource.NONE,
    val statusNewDisposition: MomoStatusNewDisposition = MomoStatusNewDisposition.NONE
)

internal data class MomoChatListRowClassification(
    val selectionClass: String,
    val reason: String
) {
    val selectable: Boolean get() = selectionClass == PRIVATE_CHAT

    companion object {
        const val PRIVATE_CHAT = "PRIVATE_CHAT"
        const val SYSTEM_ENTRY = "SYSTEM_ENTRY"
        const val UNCLASSIFIED = "UNCLASSIFIED"
    }
}

/** Pure selection and geometry rules shared by the Android extractor and local unit tests. */
internal object MomoChatListSessionPolicy {
    private const val AUXILIARY_RECYCLER_VIEW_ID = "active_rv"
    private const val NOTICE_HOLDER_VIEW_ID = "notice_layout_content_root"
    private const val AI_FINDER_FLOATING_VIEW_ID = "mf_view"

    fun selectText(text: String?, contentDescription: String?): MomoChatListTextValue {
        val normalizedText = text?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedText != null) {
            return MomoChatListTextValue(normalizedText, MomoChatListTextSource.NODE_TEXT)
        }
        val normalizedDescription = contentDescription?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedDescription != null) {
            return MomoChatListTextValue(
                normalizedDescription,
                MomoChatListTextSource.CONTENT_DESCRIPTION
            )
        }
        return MomoChatListTextValue(null, MomoChatListTextSource.NONE)
    }

    /** Hamming distance for 64-bit hexadecimal dHash values; never an identity verdict. */
    fun avatarHashHammingDistance(first: String?, second: String?): Int? {
        val a = first?.takeIf { it.length == 16 } ?: return null
        val b = second?.takeIf { it.length == 16 } ?: return null
        return runCatching {
            java.lang.Long.bitCount(
                java.lang.Long.parseUnsignedLong(a, 16) xor
                    java.lang.Long.parseUnsignedLong(b, 16)
            )
        }.getOrNull()
    }

    /**
     * A different avatar fingerprint for the same name-only key is a collision warning, not an
     * identity verdict. Callers may quarantine the key, but must never use a matching hash to
     * verify identity or merge contacts.
     */
    fun isAvatarFingerprintConflictCandidate(
        distance: Int?,
        similarityThreshold: Int,
        platformIdVerified: Boolean = false
    ): Boolean = !platformIdVerified && distance != null &&
        similarityThreshold >= 0 && distance > similarityThreshold

    /** A valid rectangle must have strictly positive width and height. */
    fun hasValidBounds(rect: ScreenRect): Boolean =
        rect.right > rect.left && rect.bottom > rect.top

    /** Bounds are physical screen pixels. Partial intersection is kept distinct from full visibility. */
    fun intersectsViewport(rect: ScreenRect, viewport: ScreenRect): Boolean =
        hasValidBounds(rect) && hasValidBounds(viewport) &&
            rect.left < viewport.right && rect.right > viewport.left &&
            rect.top < viewport.bottom && rect.bottom > viewport.top

    fun fullyInsideViewport(rect: ScreenRect, viewport: ScreenRect): Boolean =
        hasValidBounds(rect) && hasValidBounds(viewport) &&
            rect.left >= viewport.left && rect.top >= viewport.top &&
            rect.right <= viewport.right && rect.bottom <= viewport.bottom

    /**
     * Returns the largest full-width vertical band not covered by any excluded nested viewport.
     * Used for main-inbox gestures so a nested horizontal RecyclerView cannot consume the swipe.
     */
    fun largestVerticalBandExcluding(
        bounds: ScreenRect,
        excludedBounds: List<ScreenRect>,
        minimumWidth: Int,
        minimumHeight: Int
    ): ScreenRect? {
        if (!hasValidBounds(bounds) || minimumWidth <= 0 || minimumHeight <= 0 ||
            bounds.width < minimumWidth
        ) return null
        val intervals = excludedBounds.asSequence()
            .filter { hasValidBounds(it) && intersectsViewport(it, bounds) }
            .map { maxOf(bounds.top, it.top) to minOf(bounds.bottom, it.bottom) }
            .filter { (top, bottom) -> bottom > top }
            .sortedBy { it.first }
            .toList()
        if (intervals.isEmpty()) {
            return bounds.takeIf { it.height >= minimumHeight }
        }
        val merged = ArrayList<Pair<Int, Int>>()
        intervals.forEach { interval ->
            val last = merged.lastOrNull()
            if (last != null && interval.first <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, interval.second)
            } else {
                merged += interval
            }
        }
        val available = ArrayList<ScreenRect>()
        var cursor = bounds.top
        merged.forEach { (top, bottom) ->
            if (top - cursor >= minimumHeight) {
                available += ScreenRect(bounds.left, cursor, bounds.right, top)
            }
            cursor = maxOf(cursor, bottom)
        }
        if (bounds.bottom - cursor >= minimumHeight) {
            available += ScreenRect(bounds.left, cursor, bounds.right, bounds.bottom)
        }
        return available.filter { it.width >= minimumWidth && it.height >= minimumHeight }
            .maxByOrNull { it.height.toLong() * it.width.toLong() }
    }

    /** Exact Momo ViewIds observed for dynamic horizontal cards and notice-only holders. */
    fun isAuxiliaryRecyclerView(viewId: String?): Boolean =
        viewId?.substringAfterLast('/') == AUXILIARY_RECYCLER_VIEW_ID

    fun isKnownNonSessionHolder(viewId: String?): Boolean =
        viewId?.substringAfterLast('/') == NOTICE_HOLDER_VIEW_ID

    /** The delayed "一键AI找人" floating control is outside the inbox and is never a row. */
    fun isAiFinderFloatingView(viewId: String?): Boolean =
        viewId?.substringAfterLast('/')?.equals(AI_FINDER_FLOATING_VIEW_ID, ignoreCase = true) == true

    /**
     * Returns the largest rectangular part of [bounds] that does not intersect the live floating
     * control. The result remains a rectangle because GestureGeometryPolicy samples and validates
     * a complete gesture path inside one rectangle. No coordinate clipping or fallback is used.
     */
    fun largestSafeSubRect(
        bounds: ScreenRect,
        excludedBounds: ScreenRect?,
        minimumWidth: Int,
        minimumHeight: Int
    ): ScreenRect? {
        if (!hasValidBounds(bounds) || minimumWidth <= 0 || minimumHeight <= 0) return null
        if (excludedBounds == null || !hasValidBounds(excludedBounds) ||
            !intersectsViewport(excludedBounds, bounds)
        ) {
            return bounds.takeIf { it.width >= minimumWidth && it.height >= minimumHeight }
        }

        val cutLeft = maxOf(bounds.left, excludedBounds.left)
        val cutTop = maxOf(bounds.top, excludedBounds.top)
        val cutRight = minOf(bounds.right, excludedBounds.right)
        val cutBottom = minOf(bounds.bottom, excludedBounds.bottom)
        val candidates = listOf(
            ScreenRect(bounds.left, bounds.top, bounds.right, cutTop),
            ScreenRect(bounds.left, cutBottom, bounds.right, bounds.bottom),
            ScreenRect(bounds.left, bounds.top, cutLeft, bounds.bottom),
            ScreenRect(cutRight, bounds.top, bounds.right, bounds.bottom)
        ).filter { candidate ->
            hasValidBounds(candidate) && candidate.width >= minimumWidth &&
                candidate.height >= minimumHeight
        }
        return candidates.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    fun isSessionCandidate(
        hasAvatar: Boolean,
        name: MomoChatListTextValue,
        preview: MomoChatListTextValue
    ): Boolean = hasAvatar || !name.value.isNullOrBlank() || !preview.value.isNullOrBlank()

    /**
     * Reads only explicit unread semantics. Ordinary preview text such as "我还没看" must not
     * become a badge signal. Within a confirmed private-user item_layout, Momo's exact
     * status_new TextView is authoritative only when the node is visible, geometrically valid,
     * intersects and is contained by the exact row. The session_fire feature icon is explicitly
     * excluded: it does not indicate unread state. A hidden, clipped, or malformed status_new
     * node remains UNKNOWN. An empty value is READ only after those checks; absence is considered
     * READ only after the row and all identifying fields are fully visible within the viewport.
     */
    fun inspectUnreadEvidence(
        viewId: String?,
        text: String?,
        contentDescription: String?,
        boundsValid: Boolean,
        intersectsRow: Boolean,
        isKnownContentField: Boolean = false,
        containedInRow: Boolean = true,
        visibleToUser: Boolean = true
    ): MomoChatUnreadEvidence {
        val normalizedViewId = viewId.orEmpty().lowercase(java.util.Locale.ROOT)
        val normalizedLeafViewId = normalizedViewId.substringAfterLast('/')
        // session_fire is the fire-feature icon, not an unread badge. Its presence alone
        // must never create UNREAD evidence, regardless of the node's accessible label.
        if (normalizedLeafViewId == FIRE_FEATURE_VIEW_ID_TOKEN) return MomoChatUnreadEvidence()
        val isStatusNewField = normalizedLeafViewId == STATUS_NEW_VIEW_ID_TOKEN
        if (!boundsValid) {
            return MomoChatUnreadEvidence(
                statusNewDisposition = if (isStatusNewField) {
                    MomoStatusNewDisposition.UNKNOWN_INVALID_BOUNDS
                } else MomoStatusNewDisposition.NONE
            )
        }
        if (!intersectsRow) {
            return MomoChatUnreadEvidence(
                statusNewDisposition = if (isStatusNewField) {
                    MomoStatusNewDisposition.UNKNOWN_OUTSIDE_ROW
                } else MomoStatusNewDisposition.NONE
            )
        }
        // Text and semantic markers must belong to this exact row and be actually visible.
        // A node outside the row or hidden by clipping/virtualization cannot grant READ/UNREAD.
        if (!containedInRow) {
            return MomoChatUnreadEvidence(
                statusNewDisposition = if (isStatusNewField) {
                    MomoStatusNewDisposition.UNKNOWN_OUTSIDE_ROW
                } else MomoStatusNewDisposition.NONE
            )
        }
        if (!visibleToUser) {
            return MomoChatUnreadEvidence(
                statusNewDisposition = if (isStatusNewField) {
                    MomoStatusNewDisposition.UNKNOWN_NOT_VISIBLE
                } else MomoStatusNewDisposition.NONE
            )
        }
        val semanticViewId = isStatusNewField ||
            UNREAD_VIEW_ID_TOKENS.any(normalizedViewId::contains)
        if (isStatusNewField && text.isNullOrBlank()) {
            return MomoChatUnreadEvidence(
                status = ChatUnreadStatus.READ,
                count = 0,
                source = ChatUnreadEvidenceSource.STATUS_BADGE_EMPTY,
                statusNewDisposition = MomoStatusNewDisposition.READ_EMPTY_VALUE
            )
        }
        // Momo's status_new field can expose a count in node.text while contentDescription
        // may contain a time/status phrase. Prefer the actual text value for this exact field.
        val labels = if (isStatusNewField) {
            // The user-confirmed contract is based on TextView.text, not its description.
            listOfNotNull(text?.trim()?.takeIf(String::isNotEmpty))
        } else {
            listOfNotNull(
                contentDescription?.trim()?.takeIf(String::isNotEmpty),
                text?.trim()?.takeIf(String::isNotEmpty)
            )
        }
        val unreadLabel = if (isKnownContentField) null else labels.firstOrNull(::containsUnreadLabel)
        val readLabel = if (isKnownContentField) null else labels.firstOrNull(::containsExplicitReadLabel)

        if (unreadLabel != null) {
            val count = parseUnreadCount(unreadLabel)
            return MomoChatUnreadEvidence(
                status = if (count == 0) ChatUnreadStatus.READ else ChatUnreadStatus.UNREAD,
                count = count,
                source = ChatUnreadEvidenceSource.ACCESSIBILITY_LABEL,
                statusNewDisposition = if (isStatusNewField) {
                    if (count == 0) MomoStatusNewDisposition.READ else MomoStatusNewDisposition.UNREAD
                } else MomoStatusNewDisposition.NONE
            )
        }
        if (readLabel != null) {
            return MomoChatUnreadEvidence(
                status = ChatUnreadStatus.READ,
                count = 0,
                source = ChatUnreadEvidenceSource.ACCESSIBILITY_LABEL,
                statusNewDisposition = if (isStatusNewField) MomoStatusNewDisposition.READ
                    else MomoStatusNewDisposition.NONE
            )
        }
        if (!semanticViewId) return MomoChatUnreadEvidence()

        val badgeValue = labels.firstOrNull()
        if (badgeValue == null) {
            // A present, in-bounds node with an explicitly unread-specific ID is sufficient
            // evidence for a dot-style badge even when it has no accessible text.
            return MomoChatUnreadEvidence(
                status = ChatUnreadStatus.UNREAD,
                source = ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID
            )
        }
        val trimmedBadgeValue = badgeValue.trim()
        if (trimmedBadgeValue.endsWith("+") &&
            trimmedBadgeValue.dropLast(1).toIntOrNull() != null
        ) {
            return MomoChatUnreadEvidence(
                status = ChatUnreadStatus.UNREAD,
                source = ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID,
                statusNewDisposition = if (isStatusNewField) MomoStatusNewDisposition.UNREAD
                    else MomoStatusNewDisposition.NONE
            )
        }
        val numeric = trimmedBadgeValue.toIntOrNull()
        return when {
            numeric != null && numeric == 0 -> MomoChatUnreadEvidence(
                status = ChatUnreadStatus.READ,
                count = 0,
                source = ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID,
                statusNewDisposition = if (isStatusNewField) MomoStatusNewDisposition.READ
                    else MomoStatusNewDisposition.NONE
            )
            numeric != null && numeric > 0 -> MomoChatUnreadEvidence(
                status = ChatUnreadStatus.UNREAD,
                count = numeric,
                source = ChatUnreadEvidenceSource.SEMANTIC_VIEW_ID,
                statusNewDisposition = if (isStatusNewField) MomoStatusNewDisposition.UNREAD
                    else MomoStatusNewDisposition.NONE
            )
            else -> MomoChatUnreadEvidence(
                statusNewDisposition = if (isStatusNewField) MomoStatusNewDisposition.UNKNOWN_NON_NUMERIC_VALUE
                    else MomoStatusNewDisposition.NONE
            )
        }
    }

    /**
     * Confirms READ from an absent status_new node only for a completely scanned private-user
     * item_layout. System/unclassified rows and truncated scans never inherit this rule.
     */
    fun missingStatusNewMeansRead(
        selectionClass: String,
        statusNewNodeCount: Int,
        scanTruncated: Boolean,
        otherUnreadMarkerObserved: Boolean,
        rowVisibleToUser: Boolean,
        rowFullyInsideViewport: Boolean,
        nameFullyVisible: Boolean,
        previewFullyVisible: Boolean,
        timestampFullyVisible: Boolean
    ): Boolean = selectionClass == MomoChatListRowClassification.PRIVATE_CHAT &&
        statusNewNodeCount == 0 && !scanTruncated && !otherUnreadMarkerObserved &&
        rowVisibleToUser && rowFullyInsideViewport && nameFullyVisible &&
        previewFullyVisible && timestampFullyVisible

    /** A red status point is trusted only for the specifically observed system row, never a private chat. */
    fun isConfirmedSubscriptionEntryStatusPointUnread(
        selectionClass: String,
        normalizedRowName: String?,
        rowFullyVisible: Boolean,
        statusPointNodeCount: Int,
        statusPointBoundsValid: Boolean,
        statusPointContainedInVisibleContainer: Boolean,
        statusPointContainerVisible: Boolean
    ): Boolean = selectionClass == MomoChatListRowClassification.SYSTEM_ENTRY &&
        normalizedRowName.orEmpty().trim().replace(Regex("\\s+"), " ") == "订阅内容" &&
        rowFullyVisible && statusPointNodeCount == 1 && statusPointBoundsValid &&
        statusPointContainedInVisibleContainer && statusPointContainerVisible

    /** Ordinary private-chat previews are message bodies, not independent unread badges. */
    fun isOtherUnreadMarker(selectionClass: String, viewIdResourceName: String): Boolean {
        val viewId = viewIdResourceName.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        if (viewId == STATUS_NEW_VIEW_ID_TOKEN || viewId == FIRE_FEATURE_VIEW_ID_TOKEN) return false
        if (selectionClass == MomoChatListRowClassification.PRIVATE_CHAT &&
            viewId == PREVIEW_VIEW_ID_TOKEN
        ) return false
        return viewId.isNotBlank()
    }

    /** Combines badge observations conservatively; contradictory signals become UNKNOWN. */
    fun mergeUnreadEvidence(
        current: MomoChatUnreadEvidence,
        next: MomoChatUnreadEvidence
    ): MomoChatUnreadEvidence {
        if (next.status == ChatUnreadStatus.UNKNOWN) return current
        if (current.status == ChatUnreadStatus.UNKNOWN) return next
        if (current.status != next.status) {
            return MomoChatUnreadEvidence(
                status = ChatUnreadStatus.UNKNOWN,
                source = ChatUnreadEvidenceSource.CONFLICTING
            )
        }
        val mergedSource = if (current.source == next.source) current.source
        else ChatUnreadEvidenceSource.COMBINED
        val mergedCount = when (current.status) {
            ChatUnreadStatus.UNREAD -> listOfNotNull(current.count, next.count).maxOrNull()
            ChatUnreadStatus.READ -> 0
            ChatUnreadStatus.UNKNOWN -> null
        }
        return MomoChatUnreadEvidence(current.status, mergedCount, mergedSource)
    }

    private fun containsUnreadLabel(value: String): Boolean {
        val normalized = value.replace(Regex("\\s+"), "")
        if (normalized.contains("无未读") || normalized.contains("没有未读")) return false
        return normalized.contains("未读") || normalized.contains("新消息") ||
            normalized.contains("新信息") || normalized.contains("新招呼")
    }

    private fun containsExplicitReadLabel(value: String): Boolean {
        val normalized = value.replace(Regex("\\s+"), "")
        return normalized == "已读" || normalized.contains("已读消息") ||
            normalized.contains("无未读") || normalized.contains("没有未读")
    }

    private fun parseUnreadCount(value: String): Int? {
        val normalized = value.replace(Regex("\\s+"), "")
        val patterns = listOf(
            Regex("(\\d{1,4})条?(?:未读|新消息|新信息)"),
            Regex("(\\d{1,4})个?新招呼"),
            Regex("(?:未读(?:消息)?|新消息|新信息|新招呼)(\\d{1,4})")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    /**
     * Classifies only from evidence observed in the supplied Momo inbox. Known feature rows are
     * explicitly excluded. A normal chat row must also expose a valid timestamp node; rows that
     * do not meet this minimum are retained for diagnostics but are never clicked.
     */
    fun classifyRow(
        name: String?,
        preview: String?,
        timestamp: String?,
        timestampBoundsValid: Boolean,
        timestampBoundsWithinRow: Boolean = timestampBoundsValid
    ): MomoChatListRowClassification {
        val normalizedName = name.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (normalizedName in SYSTEM_ENTRY_NAMES) {
            return MomoChatListRowClassification(
                MomoChatListRowClassification.SYSTEM_ENTRY,
                "KNOWN_SYSTEM_ENTRY_NAME"
            )
        }
        if (isKnownSystemNotificationPreview(preview)) {
            return MomoChatListRowClassification(
                MomoChatListRowClassification.SYSTEM_ENTRY,
                "KNOWN_SYSTEM_NOTIFICATION_PREVIEW"
            )
        }
        if (normalizedName.isBlank() || preview.isNullOrBlank()) {
            return MomoChatListRowClassification(
                MomoChatListRowClassification.UNCLASSIFIED,
                "NAME_OR_PREVIEW_MISSING"
            )
        }
        if (timestamp.isNullOrBlank() || !timestampBoundsValid || !timestampBoundsWithinRow) {
            return MomoChatListRowClassification(
                MomoChatListRowClassification.UNCLASSIFIED,
                when {
                    timestamp.isNullOrBlank() || !timestampBoundsValid ->
                        "VALID_TIMESTAMP_EVIDENCE_MISSING"
                    !timestampBoundsWithinRow -> "TIMESTAMP_BOUNDS_OUTSIDE_ROW"
                    else -> "VALID_TIMESTAMP_EVIDENCE_MISSING"
                }
            )
        }
        if (!isPlausibleTimestamp(timestamp)) {
            return MomoChatListRowClassification(
                MomoChatListRowClassification.UNCLASSIFIED,
                "TIMESTAMP_FORMAT_NOT_RECOGNIZED"
            )
        }
        return MomoChatListRowClassification(
            MomoChatListRowClassification.PRIVATE_CHAT,
            "CHAT_ROW_MINIMUM_STRUCTURE_MATCHED"
        )
    }

    /**
     * Accept only timestamp-like labels observed in Momo session rows. A readable node in the
     * timestamp slot is not sufficient by itself: recycled or mis-mapped nodes can contain
     * arbitrary text. Unknown formats remain diagnostic-only until confirmed by a device sample.
     */
    private fun isPlausibleTimestamp(value: String): Boolean {
        val normalized = value.trim().replace(Regex("\\s+"), "")
        return TIMESTAMP_PATTERNS.any { it.matches(normalized) }
    }

    private fun isKnownSystemNotificationPreview(preview: String?): Boolean {
        val normalizedPreview = preview.orEmpty().trim().replace(Regex("\\s+"), " ")
        // The captured inbox sample exposes achievement notifications as "新成就：...".
        // Do not classify solely by a generic display name such as "陌陌".
        return normalizedPreview.startsWith("新成就：") ||
            normalizedPreview.startsWith("新成就:")
    }

    private val SYSTEM_ENTRY_NAMES = setOf(
        "订阅内容",
        "收到的招呼",
        "谁看过我",
        "猜你喜欢",
        "超级抢车位"
    )
    private val TIMESTAMP_PATTERNS = listOf(
        Regex("\\d{1,2}:\\d{2}"),
        Regex("(?:昨天|前天)\\d{1,2}:\\d{2}"),
        Regex("(?:昨天|前天)\\d{1,2}月\\d{1,2}日?"),
        Regex("\\d{1,2}月\\d{1,2}日?"),
        Regex("\\d{1,2}-\\d{1,2}"),
        Regex("(?:周[一二三四五六日天])\\d{1,2}:\\d{2}"),
        // Momo inbox samples expose relative age labels such as "13分钟前".
        Regex("\\d{1,4}分钟前"),
        Regex("\\d{1,3}小时前"),
        Regex("\\d{1,3}天前")
    )
    private const val STATUS_NEW_VIEW_ID_TOKEN = "chatlist_item_tv_status_new"
    private const val FIRE_FEATURE_VIEW_ID_TOKEN = "session_fire"
    private const val PREVIEW_VIEW_ID_TOKEN = "chatlist_item_tv_content"
    private val UNREAD_VIEW_ID_TOKENS = listOf(
        "unread", "msg_count", "message_count", "msg_badge", "message_badge",
        "new_msg", "new_message", "unread_badge", "unread_count",
        "未读", "未读数", "未读角标"
    )
}
