package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Boolean properties available in the in-place layout inspector's quick filter. */
enum class InspectorNodeAttribute(val label: String) {
    CLICKABLE("可点击"), LONG_CLICKABLE("可长按"), SCROLLABLE("可滚动"), EDITABLE("可编辑"),
    CHECKABLE("可勾选"), CHECKED("已勾选"), SELECTED("已选中"), ENABLED("已启用"),
    FOCUSED("有焦点"), FOCUSABLE("可聚焦"), VISIBLE("可见"),
    CONTEXT_CLICKABLE("可上下文点击"), DISMISSABLE("可关闭"), ACCESSIBILITY_FOCUSED("无障碍焦点"),
    HAS_ID("有资源 ID"), HAS_TEXT("有文本"), HAS_DESCRIPTION("有内容描述"), HAS_ACTIONS("有可用操作")
}

data class InspectorSearchCriteria(
    val query: String = "",
    val regularExpression: Boolean = false,
    val attributes: Set<InspectorNodeAttribute> = emptySet(),
    /** Screen-space rectangle selected by dragging on the bounds overlay. */
    val region: ScreenRect? = null
)

data class InspectorSearchResult(
    val matchingPaths: Set<List<Int>>,
    /** Matching nodes plus every captured ancestor needed to retain their tree branches. */
    val retainedPaths: Set<List<Int>>,
    val error: String? = null
) {
    val count: Int get() = matchingPaths.size
    val isFiltering: Boolean get() = error == null &&
        (matchingPaths.isNotEmpty() || retainedPaths.isNotEmpty())
}

/** Pure search/filter logic shared by the bounds and hierarchy presentations. */
object InspectorSearchEngine {
    fun evaluate(
        snapshot: AccessibilityTreeSnapshot,
        criteria: InspectorSearchCriteria,
        excludedPaths: Set<List<Int>> = emptySet()
    ): InspectorSearchResult {
        val query = criteria.query.trim()
        val pattern = if (query.isNotEmpty() && criteria.regularExpression) {
            try {
                Pattern.compile(query, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (error: PatternSyntaxException) {
                return InspectorSearchResult(emptySet(), emptySet(), error.description ?: "正则表达式无效")
            }
        } else null

        val matches = linkedSetOf<List<Int>>()
        snapshot.nodes.forEach { node ->
            val path = node.path.childIndices.toList()
            if (path in excludedPaths) return@forEach
            if (!matchesAttributes(node, criteria.attributes)) return@forEach
            if (criteria.region != null && !containsBounds(criteria.region, node.bounds)) return@forEach
            if (query.isNotEmpty() && !matchesQuery(snapshot, node, query, pattern)) return@forEach
            matches += path
        }
        val retained = linkedSetOf<List<Int>>()
        matches.forEach { path ->
            for (length in 0..path.size) retained += path.take(length)
        }
        return InspectorSearchResult(matches, retained)
    }

    fun matchesQuery(
        snapshot: AccessibilityTreeSnapshot,
        node: AccessibilityNodeSnapshot,
        query: String,
        pattern: Pattern? = null
    ): Boolean {
        val values = buildList {
            add(node.className)
            add(node.viewId)
            if (snapshot.textCapture == NodeTextCapture.EPHEMERAL) {
                add(node.text.orEmpty())
                add(node.contentDescription.orEmpty())
                add(node.hintText.orEmpty())
                add(node.stateDescription.orEmpty())
            }
        }.filter(String::isNotBlank)
        return values.any { value ->
            if (pattern != null) pattern.matcher(value).find()
            else value.contains(query, ignoreCase = true)
        }
    }

    fun firstMatchRange(
        snapshot: AccessibilityTreeSnapshot,
        node: AccessibilityNodeSnapshot,
        query: String,
        regularExpression: Boolean = false
    ): IntRange? {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return null
        val pattern = if (regularExpression) runCatching {
            Pattern.compile(normalizedQuery, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        }.getOrNull() else null
        val values = buildList {
            add(node.className)
            if (node.viewId.isNotBlank()) add(node.viewId)
            if (snapshot.textCapture == NodeTextCapture.EPHEMERAL) {
                add(node.text.orEmpty())
                add(node.contentDescription.orEmpty())
                add(node.hintText.orEmpty())
            }
        }
        values.forEach { value ->
            if (pattern != null) {
                val match = pattern.matcher(value)
                if (match.find()) return match.start() until match.end()
            } else {
                val index = value.indexOf(normalizedQuery, ignoreCase = true)
                if (index >= 0) return index until index + normalizedQuery.length
            }
        }
        return null
    }

    private fun matchesAttributes(
        node: AccessibilityNodeSnapshot,
        attributes: Set<InspectorNodeAttribute>
    ): Boolean = attributes.all { attribute ->
        when (attribute) {
            InspectorNodeAttribute.CLICKABLE -> node.clickable
            InspectorNodeAttribute.LONG_CLICKABLE -> node.longClickable
            InspectorNodeAttribute.SCROLLABLE -> node.scrollable
            InspectorNodeAttribute.EDITABLE -> node.editable
            InspectorNodeAttribute.CHECKABLE -> node.checkable
            InspectorNodeAttribute.CHECKED -> node.checked
            InspectorNodeAttribute.SELECTED -> node.selected
            InspectorNodeAttribute.ENABLED -> node.enabled
            InspectorNodeAttribute.FOCUSED -> node.focused
            InspectorNodeAttribute.FOCUSABLE -> node.focusable
            InspectorNodeAttribute.VISIBLE -> node.visible
            InspectorNodeAttribute.CONTEXT_CLICKABLE -> node.contextClickable
            InspectorNodeAttribute.DISMISSABLE -> node.dismissable
            InspectorNodeAttribute.ACCESSIBILITY_FOCUSED -> node.accessibilityFocused
            InspectorNodeAttribute.HAS_ID -> node.viewId.isNotBlank()
            InspectorNodeAttribute.HAS_TEXT -> !node.text.isNullOrBlank()
            InspectorNodeAttribute.HAS_DESCRIPTION -> node.contentDescription.isNullOrBlank().not()
            InspectorNodeAttribute.HAS_ACTIONS -> node.actions.isNotEmpty()
        }
    }

    /** A region selection retains nodes fully enclosed by the drag rectangle. */
    private fun containsBounds(region: ScreenRect, node: ScreenRect): Boolean =
        !region.isEmpty && !node.isEmpty && node.left >= region.left && node.top >= region.top &&
            node.right <= region.right && node.bottom <= region.bottom
}
