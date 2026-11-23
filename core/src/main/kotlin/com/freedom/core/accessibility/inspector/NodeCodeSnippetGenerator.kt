package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.query.NodeLocatorCandidateBuilder
import com.freedom.core.accessibility.query.NodeLocatorCandidateState
import com.freedom.core.accessibility.query.NodeSelector
import com.freedom.core.accessibility.query.NodeTreeIndex

/**
 * Builds a conservative AutoJs6 JavaScript selector from a candidate verified against the
 * captured window. The analysis is snapshot-only and never reads or performs actions on live nodes.
 */
object NodeCodeSnippetGenerator {
    fun generate(node: AccessibilityNodeSnapshot, snapshot: AccessibilityTreeSnapshot): String {
        val analysis = NodeLocatorCandidateBuilder.build(NodeTreeIndex(snapshot), node.path)
        val candidate = analysis.candidates.firstOrNull {
            it.state == NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT
        } ?: analysis.candidates.firstOrNull()
        val selector = candidate?.selector
        val query = selector?.let(::toAutoJsSelector)
        val confidenceNote = when (candidate?.state) {
            NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT ->
                "// 此定位条件在本次捕获窗口内唯一；不代表跨页面永久唯一。"
            NodeLocatorCandidateState.AMBIGUOUS ->
                "// 警告：此定位条件在当前快照中命中多个节点，请补充条件。"
            NodeLocatorCandidateState.INDETERMINATE ->
                "// 未能证明定位条件唯一；采集或分析预算可能不完整。"
            NodeLocatorCandidateState.NO_MATCH,
            NodeLocatorCandidateState.TARGET_NOT_MATCHED ->
                "// 此定位条件未通过当前快照验证，使用前请重新检查。"
            null -> "// 当前快照没有足够的已采集属性来生成可靠定位条件。"
        }
        val action = when {
            node.clickable -> "target.click();"
            node.longClickable -> "target.longClick();"
            node.scrollable -> "target.scrollForward();"
            else -> "// 已定位控件；请根据实际需要补充操作。"
        }
        return buildString {
            append("// Generated from the selected Freedom accessibility snapshot.\n")
            append("// 文本和描述字段未请求采集，不会用这些值构造选择器。\n")
            append(confidenceNote).append('\n')
            if (query == null) {
                append("// 资源 ID：").append(node.viewId.ifBlank { "无" })
                    .append("；控件类型：").append(node.className.ifBlank { "未知" })
                    .append('\n')
                append("// 边界：[").append(node.bounds.left).append(", ")
                    .append(node.bounds.top).append(", ").append(node.bounds.right).append(", ")
                    .append(node.bounds.bottom).append("]")
            } else {
                append("const target = ").append(query).append(".findOne(10000);\n")
                append("if (target) {\n    ").append(action)
                    .append("\n} else {\n    log(\"未找到目标控件\");\n}")
            }
        }
    }

    private fun toAutoJsSelector(selector: NodeSelector): String {
        val methods = buildList {
            selector.viewId?.let { add("id(${quote(it)})") }
            selector.className?.let { add("className(${quote(it)})") }
            selector.textEquals?.let { add("text(${quote(it)})") }
            selector.contentDescriptionEquals?.let { add("desc(${quote(it)})") }
            selector.clickable?.let { add("clickable($it)") }
            selector.longClickable?.let { add("longClickable($it)") }
            selector.scrollable?.let { add("scrollable($it)") }
            selector.editable?.let { add("editable($it)") }
            selector.checkable?.let { add("checkable($it)") }
            selector.enabled?.let { add("enabled($it)") }
        }
        return methods.joinToString(".")
            .ifBlank { "className(${quote(selector.className.orEmpty())})" }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20 || char == '\u2028' || char == '\u2029') {
                    append("\\u%04x".format(char.code))
                } else append(char)
            }
        }
        append('"')
    }
}
