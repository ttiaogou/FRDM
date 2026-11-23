package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.ScreenRect

/** A platform-neutral, snapshot-only selector. All populated fields are AND conditions. */
data class NodeSelector(
    val viewId: String? = null,
    val className: String? = null,
    val textEquals: String? = null,
    val textContains: String? = null,
    val textStartsWith: String? = null,
    val textEndsWith: String? = null,
    val textRegex: String? = null,
    val contentDescriptionEquals: String? = null,
    val contentDescriptionContains: String? = null,
    val visible: Boolean? = null,
    val enabled: Boolean? = null,
    val clickable: Boolean? = null,
    val longClickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val editable: Boolean? = null,
    val checkable: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val focused: Boolean? = null,
    val focusable: Boolean? = null,
    val contextClickable: Boolean? = null,
    val boundsIntersects: ScreenRect? = null,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val childCount: Int? = null,
    val anyOf: List<NodeSelector> = emptyList(),
    val noneOf: List<NodeSelector> = emptyList(),
    // Appended to preserve the positional constructor order used by older call sites.
    val dismissable: Boolean? = null,
    val accessibilityFocused: Boolean? = null,
    val multiLine: Boolean? = null,
    val contentInvalid: Boolean? = null,
    val importantForAccessibility: Boolean? = null,
    val screenReaderFocusable: Boolean? = null,
    val heading: Boolean? = null,
    val showingHintText: Boolean? = null,
) {
    init {
        require(minDepth == null || minDepth >= 0) { "minDepth cannot be negative" }
        require(maxDepth == null || maxDepth >= 0) { "maxDepth cannot be negative" }
        require(minDepth == null || maxDepth == null || minDepth <= maxDepth) {
            "minDepth cannot exceed maxDepth"
        }
        require(textRegex == null || runCatching { Regex(textRegex.orEmpty()) }.isSuccess) {
            "textRegex is not a valid regular expression"
        }
    }

    internal val compiledTextRegex: Regex? = textRegex?.let(::Regex)

    fun matches(node: AccessibilityNodeSnapshot): Boolean {
        val text = node.text
        val description = node.contentDescription
        val depth = node.path.childIndices.size
        return (viewId == null || node.hasCapturedField(AccessibilityNodeField.VIEW_ID) && node.viewId == viewId) &&
            (className == null || node.hasCapturedField(AccessibilityNodeField.CLASS_NAME) && node.className == className) &&
            (textEquals == null || node.hasCapturedField(AccessibilityNodeField.TEXT) && text != null && text == textEquals) &&
            (textContains == null || node.hasCapturedField(AccessibilityNodeField.TEXT) && text != null && text.contains(textContains)) &&
            (textStartsWith == null || node.hasCapturedField(AccessibilityNodeField.TEXT) && text != null && text.startsWith(textStartsWith)) &&
            (textEndsWith == null || node.hasCapturedField(AccessibilityNodeField.TEXT) && text != null && text.endsWith(textEndsWith)) &&
            (compiledTextRegex == null || node.hasCapturedField(AccessibilityNodeField.TEXT) && text != null && compiledTextRegex.containsMatchIn(text)) &&
            (contentDescriptionEquals == null || node.hasCapturedField(AccessibilityNodeField.CONTENT_DESCRIPTION) && description != null && description == contentDescriptionEquals) &&
            (contentDescriptionContains == null || node.hasCapturedField(AccessibilityNodeField.CONTENT_DESCRIPTION) && description != null && description.contains(contentDescriptionContains)) &&
            (visible == null || node.hasCapturedField(AccessibilityNodeField.VISIBLE) && node.visible == visible) &&
            (enabled == null || node.hasCapturedField(AccessibilityNodeField.ENABLED) && node.enabled == enabled) &&
            (clickable == null || node.hasCapturedField(AccessibilityNodeField.CLICKABLE) && node.clickable == clickable) &&
            (longClickable == null || node.hasCapturedField(AccessibilityNodeField.LONG_CLICKABLE) && node.longClickable == longClickable) &&
            (scrollable == null || node.hasCapturedField(AccessibilityNodeField.SCROLLABLE) && node.scrollable == scrollable) &&
            (editable == null || node.hasCapturedField(AccessibilityNodeField.EDITABLE) && node.editable == editable) &&
            (checkable == null || node.hasCapturedField(AccessibilityNodeField.CHECKABLE) && node.checkable == checkable) &&
            (checked == null || node.hasCapturedField(AccessibilityNodeField.CHECKED) && node.checked == checked) &&
            (selected == null || node.hasCapturedField(AccessibilityNodeField.SELECTED) && node.selected == selected) &&
            (focused == null || node.hasCapturedField(AccessibilityNodeField.FOCUSED) && node.focused == focused) &&
            (focusable == null || node.hasCapturedField(AccessibilityNodeField.FOCUSABLE) && node.focusable == focusable) &&
            (contextClickable == null || node.hasCapturedField(AccessibilityNodeField.CONTEXT_CLICKABLE) && node.contextClickable == contextClickable) &&
            (dismissable == null || node.hasCapturedField(AccessibilityNodeField.DISMISSABLE) && node.dismissable == dismissable) &&
            (accessibilityFocused == null || node.hasCapturedField(AccessibilityNodeField.ACCESSIBILITY_FOCUSED) && node.accessibilityFocused == accessibilityFocused) &&
            (multiLine == null || node.hasCapturedField(AccessibilityNodeField.MULTI_LINE) && node.multiLine == multiLine) &&
            (contentInvalid == null || node.hasCapturedField(AccessibilityNodeField.CONTENT_INVALID) && node.contentInvalid == contentInvalid) &&
            (importantForAccessibility == null || node.hasCapturedField(AccessibilityNodeField.IMPORTANT_FOR_ACCESSIBILITY) && node.importantForAccessibility == importantForAccessibility) &&
            (screenReaderFocusable == null || node.hasCapturedField(AccessibilityNodeField.SCREEN_READER_FOCUSABLE) && node.screenReaderFocusable == screenReaderFocusable) &&
            (heading == null || node.hasCapturedField(AccessibilityNodeField.HEADING) && node.heading == heading) &&
            (showingHintText == null || node.hasCapturedField(AccessibilityNodeField.SHOWING_HINT_TEXT) && node.showingHintText == showingHintText) &&
            (boundsIntersects == null || node.hasCapturedField(AccessibilityNodeField.BOUNDS) && node.bounds.intersects(boundsIntersects)) &&
            (minDepth == null || depth >= minDepth) &&
            (maxDepth == null || depth <= maxDepth) &&
            (childCount == null || node.hasCapturedField(AccessibilityNodeField.CHILD_COUNT) && node.childCount == childCount) &&
            (anyOf.isEmpty() || anyOf.any { it.matches(node) }) &&
            noneOf.none { it.matches(node) }
    }

    fun describe(): String = buildList {
        viewId?.let { add("viewId=$it") }
        className?.let { add("className=$it") }
        textEquals?.let { add("text==$it") }
        textContains?.let { add("text~=$it") }
        textStartsWith?.let { add("text^=$it") }
        textEndsWith?.let { add("text$=$it") }
        textRegex?.let { add("text~/$it/") }
        contentDescriptionEquals?.let { add("description==$it") }
        contentDescriptionContains?.let { add("description~=$it") }
        visible?.let { add("visible=$it") }
        enabled?.let { add("enabled=$it") }
        clickable?.let { add("clickable=$it") }
        longClickable?.let { add("longClickable=$it") }
        scrollable?.let { add("scrollable=$it") }
        editable?.let { add("editable=$it") }
        checkable?.let { add("checkable=$it") }
        checked?.let { add("checked=$it") }
        selected?.let { add("selected=$it") }
        focused?.let { add("focused=$it") }
        focusable?.let { add("focusable=$it") }
        contextClickable?.let { add("contextClickable=$it") }
        dismissable?.let { add("dismissable=$it") }
        accessibilityFocused?.let { add("accessibilityFocused=$it") }
        multiLine?.let { add("multiLine=$it") }
        contentInvalid?.let { add("contentInvalid=$it") }
        importantForAccessibility?.let { add("importantForAccessibility=$it") }
        screenReaderFocusable?.let { add("screenReaderFocusable=$it") }
        heading?.let { add("heading=$it") }
        showingHintText?.let { add("showingHintText=$it") }
        boundsIntersects?.let { add("boundsIntersects=${it.left},${it.top},${it.right},${it.bottom}") }
        minDepth?.let { add("minDepth=$it") }
        maxDepth?.let { add("maxDepth=$it") }
        childCount?.let { add("childCount=$it") }
        if (anyOf.isNotEmpty()) add("anyOf=[${anyOf.joinToString(" OR ") { it.describe() }}]")
        if (noneOf.isNotEmpty()) add("noneOf=[${noneOf.joinToString(" AND NOT ") { it.describe() }}]")
    }.joinToString(" AND ").ifBlank { "*" }
}

internal fun ScreenRect.intersects(other: ScreenRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

internal data class SelectorCriterion(
    val description: String,
    val matches: (AccessibilityNodeSnapshot) -> Boolean
)

internal fun NodeSelector.criteria(): List<SelectorCriterion> = buildList {
    fun addIf(label: String, present: Boolean, test: (AccessibilityNodeSnapshot) -> Boolean) {
        if (present) add(SelectorCriterion(label, test))
    }
    addIf("viewId=$viewId", viewId != null) { it.hasCapturedField(AccessibilityNodeField.VIEW_ID) && it.viewId == viewId }
    addIf("className=$className", className != null) { it.hasCapturedField(AccessibilityNodeField.CLASS_NAME) && it.className == className }
    addIf("text==$textEquals", textEquals != null) {
        it.hasCapturedField(AccessibilityNodeField.TEXT) && it.text != null && it.text == textEquals
    }
    addIf("text~=$textContains", textContains != null) {
        it.hasCapturedField(AccessibilityNodeField.TEXT) && it.text?.contains(textContains.orEmpty()) == true
    }
    addIf("text^=$textStartsWith", textStartsWith != null) {
        it.hasCapturedField(AccessibilityNodeField.TEXT) && it.text?.startsWith(textStartsWith.orEmpty()) == true
    }
    addIf("text$=$textEndsWith", textEndsWith != null) {
        it.hasCapturedField(AccessibilityNodeField.TEXT) && it.text?.endsWith(textEndsWith.orEmpty()) == true
    }
    addIf("text~/$textRegex/", textRegex != null) { node ->
        node.hasCapturedField(AccessibilityNodeField.TEXT) &&
            node.text?.let { text -> compiledTextRegex?.containsMatchIn(text) == true } == true
    }
    addIf("description==$contentDescriptionEquals", contentDescriptionEquals != null) {
        it.hasCapturedField(AccessibilityNodeField.CONTENT_DESCRIPTION) &&
            it.contentDescription != null && it.contentDescription == contentDescriptionEquals
    }
    addIf("description~=$contentDescriptionContains", contentDescriptionContains != null) {
        it.hasCapturedField(AccessibilityNodeField.CONTENT_DESCRIPTION) &&
            it.contentDescription?.contains(contentDescriptionContains.orEmpty()) == true
    }
    addIf("visible=$visible", visible != null) { it.hasCapturedField(AccessibilityNodeField.VISIBLE) && it.visible == visible }
    addIf("enabled=$enabled", enabled != null) { it.hasCapturedField(AccessibilityNodeField.ENABLED) && it.enabled == enabled }
    addIf("clickable=$clickable", clickable != null) { it.hasCapturedField(AccessibilityNodeField.CLICKABLE) && it.clickable == clickable }
    addIf("longClickable=$longClickable", longClickable != null) {
        it.hasCapturedField(AccessibilityNodeField.LONG_CLICKABLE) && it.longClickable == longClickable
    }
    addIf("scrollable=$scrollable", scrollable != null) { it.hasCapturedField(AccessibilityNodeField.SCROLLABLE) && it.scrollable == scrollable }
    addIf("editable=$editable", editable != null) { it.hasCapturedField(AccessibilityNodeField.EDITABLE) && it.editable == editable }
    addIf("checkable=$checkable", checkable != null) { it.hasCapturedField(AccessibilityNodeField.CHECKABLE) && it.checkable == checkable }
    addIf("checked=$checked", checked != null) { it.hasCapturedField(AccessibilityNodeField.CHECKED) && it.checked == checked }
    addIf("selected=$selected", selected != null) { it.hasCapturedField(AccessibilityNodeField.SELECTED) && it.selected == selected }
    addIf("focused=$focused", focused != null) { it.hasCapturedField(AccessibilityNodeField.FOCUSED) && it.focused == focused }
    addIf("focusable=$focusable", focusable != null) { it.hasCapturedField(AccessibilityNodeField.FOCUSABLE) && it.focusable == focusable }
    addIf("contextClickable=$contextClickable", contextClickable != null) {
        it.hasCapturedField(AccessibilityNodeField.CONTEXT_CLICKABLE) && it.contextClickable == contextClickable
    }
    addIf("dismissable=$dismissable", dismissable != null) {
        it.hasCapturedField(AccessibilityNodeField.DISMISSABLE) && it.dismissable == dismissable
    }
    addIf("accessibilityFocused=$accessibilityFocused", accessibilityFocused != null) {
        it.hasCapturedField(AccessibilityNodeField.ACCESSIBILITY_FOCUSED) && it.accessibilityFocused == accessibilityFocused
    }
    addIf("multiLine=$multiLine", multiLine != null) {
        it.hasCapturedField(AccessibilityNodeField.MULTI_LINE) && it.multiLine == multiLine
    }
    addIf("contentInvalid=$contentInvalid", contentInvalid != null) {
        it.hasCapturedField(AccessibilityNodeField.CONTENT_INVALID) && it.contentInvalid == contentInvalid
    }
    addIf("importantForAccessibility=$importantForAccessibility", importantForAccessibility != null) {
        it.hasCapturedField(AccessibilityNodeField.IMPORTANT_FOR_ACCESSIBILITY) &&
            it.importantForAccessibility == importantForAccessibility
    }
    addIf("screenReaderFocusable=$screenReaderFocusable", screenReaderFocusable != null) {
        it.hasCapturedField(AccessibilityNodeField.SCREEN_READER_FOCUSABLE) &&
            it.screenReaderFocusable == screenReaderFocusable
    }
    addIf("heading=$heading", heading != null) {
        it.hasCapturedField(AccessibilityNodeField.HEADING) && it.heading == heading
    }
    addIf("showingHintText=$showingHintText", showingHintText != null) {
        it.hasCapturedField(AccessibilityNodeField.SHOWING_HINT_TEXT) && it.showingHintText == showingHintText
    }
    addIf("boundsIntersects=$boundsIntersects", boundsIntersects != null) {
        it.hasCapturedField(AccessibilityNodeField.BOUNDS) && boundsIntersects != null && it.bounds.intersects(boundsIntersects)
    }
    addIf("minDepth=$minDepth", minDepth != null) { it.path.childIndices.size >= (minDepth ?: 0) }
    addIf("maxDepth=$maxDepth", maxDepth != null) { it.path.childIndices.size <= (maxDepth ?: Int.MAX_VALUE) }
    addIf("childCount=$childCount", childCount != null) {
        it.hasCapturedField(AccessibilityNodeField.CHILD_COUNT) && it.childCount == childCount
    }
    addIf("anyOf", anyOf.isNotEmpty()) { node -> anyOf.any { it.matches(node) } }
    addIf("noneOf", noneOf.isNotEmpty()) { node -> noneOf.none { it.matches(node) } }
}

/** True only when the source field was actually read rather than supplied by an exception fallback. */
internal fun AccessibilityNodeSnapshot.hasCapturedField(field: AccessibilityNodeField): Boolean =
    field !in captureEvidence.failedFields &&
        field !in captureEvidence.unsupportedFields &&
        field !in captureEvidence.notCapturedFields
