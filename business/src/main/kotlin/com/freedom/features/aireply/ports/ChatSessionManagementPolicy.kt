package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.ScreenRect
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Redacted character statistics; never contains title text or individual code points. */
data class SessionTitleProfile(
    val rawUtf16Length: Int,
    val rawCodePointCount: Int,
    val normalizedUtf16Length: Int,
    val normalizedCodePointCount: Int,
    val normalizedChanged: Boolean,
    val leadingWhitespaceCodePointCount: Int,
    val trailingWhitespaceCodePointCount: Int,
    val whitespaceCodePointCount: Int,
    val formatCodePointCount: Int,
    val variationSelectorCount: Int,
    val letterCodePointCount: Int,
    val digitCodePointCount: Int,
    val punctuationCodePointCount: Int,
    val symbolCodePointCount: Int,
    val combiningMarkCodePointCount: Int,
    val controlCodePointCount: Int,
    val otherCodePointCount: Int,
    val unicodeScriptCounts: Map<String, Int>
)

/** Shape-only comparison of normalized strings; it deliberately excludes their contents. */
data class SessionTitleComparison(
    val normalizedEqual: Boolean,
    val commonPrefixCodePointCount: Int,
    val commonSuffixCodePointCount: Int,
    val expectedUnmatchedCodePointCount: Int,
    val actualUnmatchedCodePointCount: Int
)

/** Exact, platform-specific ways the chat toolbar can represent the selected contact name. */
enum class ChatHeaderMatchMode {
    EXACT_NAME,
    WRAPPED_CONVERSATION_TITLE
}

/** Pure rules for selecting stable, geometrically safe session-row candidates. */
object ChatSessionManagementPolicy {
    fun isEligible(candidate: ChatListCandidateSnapshot): Boolean =
        candidate.selectionClass == "PRIVATE_CHAT" &&
            candidate.rowBoundsValid && candidate.intersectsViewport &&
            candidate.fullyInsideViewport && candidate.hasReadableName &&
            candidate.nameBoundsValid && candidate.nameBoundsIntersectViewport &&
            candidate.nameBoundsFullyInsideViewport &&
            candidate.nameBounds?.let { bounds ->
                bounds.left >= candidate.rowBounds.left && bounds.top >= candidate.rowBounds.top &&
                    bounds.right <= candidate.rowBounds.right && bounds.bottom <= candidate.rowBounds.bottom
            } == true &&
            candidate.hasReadablePreview && candidate.previewBoundsValid &&
            candidate.previewBoundsIntersectViewport && candidate.previewBoundsFullyInsideViewport &&
            candidate.previewBounds?.let { bounds ->
                bounds.left >= candidate.rowBounds.left && bounds.top >= candidate.rowBounds.top &&
                    bounds.right <= candidate.rowBounds.right && bounds.bottom <= candidate.rowBounds.bottom
            } == true

    /**
     * Conservative evidence for list-boundary stability. This is deliberately separate from
     * click/message eligibility: system rows may establish a stable viewport, but clipped rows,
     * incomplete name/preview geometry and dynamic non-session holders may not.
     */
    fun isCompleteBoundaryEvidence(
        candidate: ChatListCandidateSnapshot,
        viewport: ScreenRect?
    ): Boolean {
        val visibleViewport = viewport ?: return false
        if (!hasValidBounds(visibleViewport) || !candidate.rowVisibleToUser ||
            !candidate.rowBoundsValid || !candidate.intersectsViewport ||
            !candidate.fullyInsideViewport || !candidate.hasReadableName ||
            !candidate.nameNodeVisibleToUser || !candidate.nameBoundsIntersectViewport ||
            !candidate.nameBoundsValid || !candidate.nameBoundsFullyInsideViewport ||
            !candidate.hasReadablePreview || !candidate.previewNodeVisibleToUser ||
            !candidate.previewBoundsIntersectViewport || !candidate.previewBoundsValid ||
            !candidate.previewBoundsFullyInsideViewport
        ) return false
        val row = candidate.rowBounds
        val name = candidate.nameBounds ?: return false
        val preview = candidate.previewBounds ?: return false
        return fullyInside(row, visibleViewport) &&
            fullyInside(name, visibleViewport) && fullyInside(preview, visibleViewport) &&
            containedBy(name, row) && containedBy(preview, row)
    }

    private fun hasValidBounds(rect: ScreenRect): Boolean =
        rect.right > rect.left && rect.bottom > rect.top

    private fun fullyInside(inner: ScreenRect, outer: ScreenRect): Boolean =
        hasValidBounds(inner) && hasValidBounds(outer) &&
            inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom

    private fun containedBy(inner: ScreenRect, outer: ScreenRect): Boolean =
        fullyInside(inner, outer)

    /**
     * The visible preview and timestamp are deliberately excluded: both can change after opening
     * a conversation, which would otherwise make the manager revisit the same session forever.
     * Momo does not currently expose a stable contact ID in the verified row nodes.
     */
    fun normalizeSessionTitle(value: String?): String = Normalizer
        .normalize(value.orEmpty(), Normalizer.Form.NFKC)
        // Accessibility labels can contain invisible formatting characters or variation
        // selectors. Remove only those non-rendering code points; preserve visible symbols,
        // emoji, punctuation and all meaningful name characters for exact identity matching.
        .filterNot { character ->
            Character.getType(character) == Character.FORMAT.toInt() ||
                character.code in 0xFE00..0xFE0F ||
                character.code in 0xE0100..0xE01EF
        }
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    /**
     * Matches the selected session name against the exact title formats observed in Momo.
     *
     * The chat toolbar uses "与{nickname}对话", while the message-list row exposes only
     * "{nickname}". This is a deterministic wrapper match, not substring or fuzzy matching:
     * every character in the nickname must be preserved and the complete title must match.
     */
    fun matchChatHeader(
        expectedSessionName: String?,
        actualChatTitle: String?
    ): ChatHeaderMatchMode? {
        val expected = normalizeSessionTitle(expectedSessionName)
        val actual = normalizeSessionTitle(actualChatTitle)
        if (expected.isBlank() || actual.isBlank()) return null

        return when (actual) {
            expected -> ChatHeaderMatchMode.EXACT_NAME
            normalizeSessionTitle("与${expected}对话") -> ChatHeaderMatchMode.WRAPPED_CONVERSATION_TITLE
            else -> null
        }
    }

    /** Returns Unicode-category and normalization metrics without returning title contents. */
    fun profileSessionTitle(value: String?): SessionTitleProfile {
        val raw = value.orEmpty()
        val normalized = normalizeSessionTitle(raw)
        val points = raw.codePoints().toArray()
        var whitespace = 0
        var format = 0
        var variation = 0
        var letters = 0
        var digits = 0
        var punctuation = 0
        var symbols = 0
        var combiningMarks = 0
        var controls = 0
        var other = 0
        val scripts = LinkedHashMap<String, Int>()

        points.forEach { codePoint ->
            val type = Character.getType(codePoint).toInt()
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) whitespace++
            if (type == Character.FORMAT.toInt()) format++
            if (codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF) variation++
            when (type) {
                Character.UPPERCASE_LETTER.toInt(),
                Character.LOWERCASE_LETTER.toInt(),
                Character.TITLECASE_LETTER.toInt(),
                Character.MODIFIER_LETTER.toInt(),
                Character.OTHER_LETTER.toInt() -> letters++
                Character.DECIMAL_DIGIT_NUMBER.toInt(),
                Character.LETTER_NUMBER.toInt(),
                Character.OTHER_NUMBER.toInt() -> digits++
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> punctuation++
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt() -> symbols++
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> combiningMarks++
                Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt() -> controls++
                else -> other++
            }
            val script = Character.UnicodeScript.of(codePoint).name
            scripts[script] = (scripts[script] ?: 0) + 1
        }

        return SessionTitleProfile(
            rawUtf16Length = raw.length,
            rawCodePointCount = points.size,
            normalizedUtf16Length = normalized.length,
            normalizedCodePointCount = normalized.codePointCount(0, normalized.length),
            normalizedChanged = raw != normalized,
            leadingWhitespaceCodePointCount = countEdgeWhitespace(points, fromStart = true),
            trailingWhitespaceCodePointCount = countEdgeWhitespace(points, fromStart = false),
            whitespaceCodePointCount = whitespace,
            formatCodePointCount = format,
            variationSelectorCount = variation,
            letterCodePointCount = letters,
            digitCodePointCount = digits,
            punctuationCodePointCount = punctuation,
            symbolCodePointCount = symbols,
            combiningMarkCodePointCount = combiningMarks,
            controlCodePointCount = controls,
            otherCodePointCount = other,
            unicodeScriptCounts = scripts.toMap()
        )
    }

    /** Counts shared prefix/suffix and unmatched segment lengths, without exposing characters. */
    fun compareSessionTitles(expected: String?, actual: String?): SessionTitleComparison {
        val expectedPoints = normalizeSessionTitle(expected).codePoints().toArray()
        val actualPoints = normalizeSessionTitle(actual).codePoints().toArray()
        var prefix = 0
        while (prefix < expectedPoints.size && prefix < actualPoints.size &&
            expectedPoints[prefix] == actualPoints[prefix]
        ) prefix++

        var suffix = 0
        while (suffix < expectedPoints.size - prefix && suffix < actualPoints.size - prefix &&
            expectedPoints[expectedPoints.lastIndex - suffix] == actualPoints[actualPoints.lastIndex - suffix]
        ) suffix++

        return SessionTitleComparison(
            normalizedEqual = expectedPoints.contentEquals(actualPoints),
            commonPrefixCodePointCount = prefix,
            commonSuffixCodePointCount = suffix,
            expectedUnmatchedCodePointCount = expectedPoints.size - prefix - suffix,
            actualUnmatchedCodePointCount = actualPoints.size - prefix - suffix
        )
    }

    fun stableSessionKey(candidate: ChatListCandidateSnapshot): String {
        val identity = candidate.contactIdentity
        if (identity?.confidence == com.freedom.features.conversation.domain.contact.ContactIdentityConfidence.PLATFORM_ID_VERIFIED) {
            val contactId = identity.contactId?.takeIf(String::isNotBlank)
            if (contactId != null) return stableSessionKeyForVerifiedContact(contactId)
        }
        // Name-only keys are deliberately weak, run-scoped traversal heuristics. They must not
        // become durable contact IDs or merge locally persisted conversation histories.
        return stableSessionKeyForTitle(candidate.nameText)
    }

    /** Stable opaque key available only after the platform user ID has been verified. */
    fun stableSessionKeyForVerifiedContact(contactId: String?): String {
        val normalized = contactId?.trim()?.takeIf(String::isNotEmpty) ?: return ""
        return stableOpaqueKey("verified-contact:$normalized")
    }

    /**
     * Returns display-name keys that identify more than one eligible row in the same snapshot.
     * A normalized display name is not a contact ID, so every row in a collision group must be
     * excluded rather than selecting the first row and silently collapsing the others.
     */
    fun ambiguousSessionIdentityKeys(
        candidates: List<ChatListCandidateSnapshot>
    ): Set<String> = candidates
        .asSequence()
        .filter(::isEligible)
        .groupBy(::stableSessionKey)
        .filter { (key, rows) ->
            key.isNotBlank() && rows.map { it.rowIndex }.distinct().size > 1
        }
        .keys

    /**
     * Opaque, normalized display-name traversal key only. The inbox tree does not expose a
     * verified account/contact ID, so this must never be used as a durable contact or message-store
     * identity. Duplicate names in the same observed viewport are blocked as ambiguous.
     */
    fun stableSessionKeyForTitle(sessionName: String?): String {
        val normalizedName = normalizeSessionTitle(sessionName)
        if (normalizedName.isBlank()) return ""
        return stableOpaqueKey("display-name:$normalizedName")
    }

    private fun stableOpaqueKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Orders rows by physical screen position, bottom-most first; rowIndex is only a tie-breaker. */
    fun orderBottomToTop(
        candidates: List<ChatListCandidateSnapshot>
    ): List<ChatListCandidateSnapshot> = candidates.sortedWith(
        compareByDescending<ChatListCandidateSnapshot> {
            it.rowBounds.top.toLong() + it.rowBounds.bottom.toLong()
        }.thenByDescending { it.rowIndex }
    )

    /**
     * Only explicitly confirmed UNREAD rows may be opened.
     *
     * READ and UNKNOWN are both excluded: absence of an accessible unread marker is not proof
     * of unread status. The remaining confirmed-unread rows are ordered by physical screen
     * position, bottom-most first. This keeps unread eligibility separate from traversal order.
     */
    fun selectNext(
        candidates: List<ChatListCandidateSnapshot>,
        attemptedKeys: Set<String>,
        blockedIdentityKeys: Set<String> = emptySet()
    ): ChatListCandidateSnapshot? {
        val ambiguousKeys = ambiguousSessionIdentityKeys(candidates) + blockedIdentityKeys
        return orderBottomToTop(candidates)
            .asSequence()
            .filter(::isEligible)
            .filter { it.unreadStatus == ChatUnreadStatus.UNREAD }
            .firstOrNull {
                val key = stableSessionKey(it)
                key !in attemptedKeys && key !in ambiguousKeys
            }
    }

    private fun countEdgeWhitespace(points: IntArray, fromStart: Boolean): Int {
        var count = 0
        var index = if (fromStart) 0 else points.lastIndex
        val step = if (fromStart) 1 else -1
        while (index in points.indices &&
            (Character.isWhitespace(points[index]) || Character.isSpaceChar(points[index]))
        ) {
            count++
            index += step
        }
        return count
    }
}
