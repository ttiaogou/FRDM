package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.ScreenRect

/**
 * Formats an inspected selector as source code that can be reviewed and reused by Freedom.
 * This formatter never evaluates or executes the generated expression.
 */
object NodeSelectorSnippetFormatter {
    fun format(selector: NodeSelector): String {
        val arguments = buildList {
            selector.viewId?.let { add("viewId = ${quote(it)}") }
            selector.className?.let { add("className = ${quote(it)}") }
            selector.textEquals?.let { add("textEquals = ${quote(it)}") }
            selector.textContains?.let { add("textContains = ${quote(it)}") }
            selector.textStartsWith?.let { add("textStartsWith = ${quote(it)}") }
            selector.textEndsWith?.let { add("textEndsWith = ${quote(it)}") }
            selector.textRegex?.let { add("textRegex = ${quote(it)}") }
            selector.contentDescriptionEquals?.let { add("contentDescriptionEquals = ${quote(it)}") }
            selector.contentDescriptionContains?.let { add("contentDescriptionContains = ${quote(it)}") }
            selector.visible?.let { add("visible = $it") }
            selector.enabled?.let { add("enabled = $it") }
            selector.clickable?.let { add("clickable = $it") }
            selector.longClickable?.let { add("longClickable = $it") }
            selector.scrollable?.let { add("scrollable = $it") }
            selector.editable?.let { add("editable = $it") }
            selector.checkable?.let { add("checkable = $it") }
            selector.checked?.let { add("checked = $it") }
            selector.selected?.let { add("selected = $it") }
            selector.focused?.let { add("focused = $it") }
            selector.focusable?.let { add("focusable = $it") }
            selector.contextClickable?.let { add("contextClickable = $it") }
            selector.boundsIntersects?.let { add("boundsIntersects = ${formatRect(it)}") }
            selector.minDepth?.let { add("minDepth = $it") }
            selector.maxDepth?.let { add("maxDepth = $it") }
            selector.childCount?.let { add("childCount = $it") }
            selector.dismissable?.let { add("dismissable = $it") }
            selector.accessibilityFocused?.let { add("accessibilityFocused = $it") }
            selector.multiLine?.let { add("multiLine = $it") }
            selector.contentInvalid?.let { add("contentInvalid = $it") }
            selector.importantForAccessibility?.let { add("importantForAccessibility = $it") }
            selector.screenReaderFocusable?.let { add("screenReaderFocusable = $it") }
            selector.heading?.let { add("heading = $it") }
            selector.showingHintText?.let { add("showingHintText = $it") }
            if (selector.anyOf.isNotEmpty()) {
                add("anyOf = listOf(${selector.anyOf.joinToString { format(it) }})")
            }
            if (selector.noneOf.isNotEmpty()) {
                add("noneOf = listOf(${selector.noneOf.joinToString { format(it) }})")
            }
        }
        if (arguments.isEmpty()) return "NodeSelector()"
        return arguments.joinToString(",\n    ", prefix = "NodeSelector(\n    ", postfix = "\n)")
    }

    private fun formatRect(rect: ScreenRect): String =
        "ScreenRect(left = ${rect.left}, top = ${rect.top}, right = ${rect.right}, bottom = ${rect.bottom})"

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\u000C")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).uppercase().padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }
}
