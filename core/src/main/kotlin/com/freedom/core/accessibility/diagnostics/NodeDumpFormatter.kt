package com.freedom.core.accessibility.diagnostics

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath

/** Export formats intentionally mirror AutoJs6 NodeDump: human-readable TEXT, hierarchical JSON and UiAutomator-style XML. */
enum class NodeDumpFormat { TEXT, JSON, XML }

data class NodeDumpOptions(
    val format: NodeDumpFormat = NodeDumpFormat.JSON,
    val maxDepth: Int = Int.MAX_VALUE,
    val properties: Set<String>? = null,
    val visibleOnly: Boolean = false,
    val maxNodes: Int = DEFAULT_MAX_NODES,
    val indent: String = "  ",
    /** Text, content descriptions and other user-facing strings are omitted unless explicitly enabled. */
    val includeText: Boolean = false
) {
    init {
        require(maxDepth >= 0) { "maxDepth cannot be negative" }
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(indent.length <= 8) { "indent is too long" }
        properties?.forEach { require(it in NodeDumpFormatter.ALL_PROPERTIES) { "Unknown node property: $it" } }
    }

    companion object {
        const val DEFAULT_MAX_NODES = 2_000
        const val DEEP_MAX_NODES = 10_000
    }
}

data class NodeDumpArtifact(
    val content: String,
    val manifestJson: String,
    val exportedNodeCount: Int,
    val truncated: Boolean,
    val stopReason: String?
)

/** Pure formatter: it consumes only the platform-neutral snapshot and cannot access Android nodes. */
object NodeDumpFormatter {
    val ALL_PROPERTIES: Set<String> = linkedSetOf(
        "className", "viewId", "text", "contentDescription", "bounds", "packageName", "windowId",
        "indexInParent", "depth", "childCount", "drawingOrder", "visible", "enabled", "clickable",
        "longClickable", "scrollable", "editable", "checkable", "checked", "selected", "focused",
        "focusable", "contextClickable", "password", "hintText", "stateDescription", "tooltipText",
        "collection", "collectionItem", "actions"
    )

    val DEFAULT_PROPERTIES: Set<String> = linkedSetOf(
        "className", "viewId", "bounds", "packageName", "windowId", "indexInParent", "depth", "childCount",
        "drawingOrder", "visible", "enabled", "clickable", "longClickable", "scrollable", "editable",
        "checkable", "checked", "selected", "focused", "focusable", "contextClickable", "password",
        "collection", "collectionItem", "actions"
    )

    fun dump(snapshot: AccessibilityTreeSnapshot, options: NodeDumpOptions = NodeDumpOptions()): NodeDumpArtifact {
        val selection = selectNodes(snapshot, options)
        val textProperties = setOf("text", "contentDescription", "hintText", "stateDescription", "tooltipText")
        val baseProperties = options.properties ?: DEFAULT_PROPERTIES
        val properties = when {
            !options.includeText -> baseProperties - textProperties
            options.properties == null -> baseProperties + textProperties
            else -> baseProperties
        }
        val truncated = snapshot.truncated || selection.truncated
        val output = when (options.format) {
            NodeDumpFormat.JSON -> renderJson(snapshot, selection.nodes, selection.children, properties, options)
            NodeDumpFormat.TEXT -> renderText(snapshot, selection.nodes, properties, options, truncated)
            NodeDumpFormat.XML -> renderXml(snapshot, selection.nodes, selection.children, properties, options)
        }
        val reason = when {
            snapshot.walkTruncated && snapshot.depthTruncated -> "NODE_AND_DEPTH_BUDGET"
            selection.nodeBudgetTruncated && snapshot.walkTruncated -> "NODE_AND_EXPORT_BUDGET"
            snapshot.walkTruncated -> "NODE_BUDGET"
            snapshot.depthTruncated || selection.depthTruncated -> "DEPTH_BUDGET"
            selection.nodeBudgetTruncated -> "EXPORT_NODE_BUDGET"
            else -> null
        }
        val manifest = renderManifest(snapshot, options, selection.nodes.size, truncated, reason, properties)
        return NodeDumpArtifact(output, manifest, selection.nodes.size, truncated, reason)
    }

    fun pathString(path: NodePath): String = path.childIndices.joinToString("/")

    private data class Selection(
        val nodes: List<AccessibilityNodeSnapshot>,
        val children: Map<NodePath, List<NodePath>>,
        val truncated: Boolean,
        val nodeBudgetTruncated: Boolean,
        val depthTruncated: Boolean
    )

    private fun selectNodes(snapshot: AccessibilityTreeSnapshot, options: NodeDumpOptions): Selection {
        val source = snapshot.nodes
        val sourceByPath = source.associateBy { it.path }
        val selected = ArrayList<AccessibilityNodeSnapshot>(minOf(source.size, options.maxNodes))
        val selectedPaths = LinkedHashSet<NodePath>()
        var depthTruncated = false
        var nodeBudgetTruncated = false
        for (node in source) {
            val depth = node.path.childIndices.size
            if (depth > options.maxDepth) {
                depthTruncated = true
                continue
            }
            if (options.visibleOnly && hasInvisibleAncestor(node.path, sourceByPath)) continue
            if (options.visibleOnly && !node.visible) continue
            if (selected.size >= options.maxNodes) {
                nodeBudgetTruncated = true
                break
            }
            selected += node
            selectedPaths += node.path
            if (depth == options.maxDepth && node.childCount > 0) depthTruncated = true
        }
        val children = LinkedHashMap<NodePath, MutableList<NodePath>>()
        selected.forEach { node ->
            if (node.path.childIndices.isEmpty()) return@forEach
            val parent = NodePath(node.path.childIndices.dropLast(1))
            if (parent in selectedPaths) children.getOrPut(parent) { ArrayList() }.add(node.path)
        }
        val truncated = nodeBudgetTruncated || depthTruncated || snapshot.truncated
        return Selection(selected, children.mapValues { it.value.toList() }, truncated, nodeBudgetTruncated, depthTruncated)
    }

    private fun hasInvisibleAncestor(
        path: NodePath,
        sourceByPath: Map<NodePath, AccessibilityNodeSnapshot>
    ): Boolean {
        var parent = if (path.childIndices.isEmpty()) null else NodePath(path.childIndices.dropLast(1))
        while (parent != null) {
            if (sourceByPath[parent]?.visible == false) return true
            parent = if (parent.childIndices.isEmpty()) null else NodePath(parent.childIndices.dropLast(1))
        }
        return false
    }

    private fun renderJson(
        snapshot: AccessibilityTreeSnapshot,
        nodes: List<AccessibilityNodeSnapshot>,
        children: Map<NodePath, List<NodePath>>,
        properties: Set<String>,
        options: NodeDumpOptions
    ): String {
        val byPath = nodes.associateBy { it.path }
        val roots = nodes.filter { node ->
            val path = node.path
            path.childIndices.isEmpty() || NodePath(path.childIndices.dropLast(1)) !in byPath
        }
        return buildString {
            append("{\n  \"schemaVersion\": 1,")
            append("\n  \"window\": {")
            append("\n    \"packageName\": ").append(jsonQuote(snapshot.window.packageName)).append(',')
            append("\n    \"activityClass\": ").append(jsonQuote(snapshot.window.activityClass)).append(',')
            append("\n    \"generation\": ").append(snapshot.window.generation).append(',')
            append("\n    \"windowId\": ").append(snapshot.windowId)
            append("\n  },")
            append("\n  \"capture\": {")
            append("\n    \"capturedAtEpochMs\": ").append(snapshot.capturedAtEpochMs).append(',')
            append("\n    \"requestedMaxNodes\": ").append(snapshot.requestedMaxNodes).append(',')
            append("\n    \"requestedMaxDepth\": ").append(snapshot.requestedMaxDepth).append(',')
            append("\n    \"visitedNodeCount\": ").append(snapshot.visitedNodeCount).append(',')
            append("\n    \"exportedNodeCount\": ").append(nodes.size).append(',')
            append("\n    \"walkTruncated\": ").append(snapshot.walkTruncated).append(',')
            append("\n    \"depthTruncated\": ").append(snapshot.depthTruncated).append(',')
            append("\n    \"includeText\": ").append(options.includeText)
            append("\n  },")
            append("\n  \"properties\": [")
            append(properties.sorted().joinToString(", ") { jsonQuote(it) })
            append("],")
            append("\n  \"nodes\": [")
            roots.forEachIndexed { index, root ->
                append("\n")
                append(renderJsonNode(root, children, byPath, properties, options, 2))
                if (index != roots.lastIndex) append(',')
            }
            if (roots.isNotEmpty()) append('\n')
            append("  ]\n}")
        }
    }

    private fun renderJsonNode(
        node: AccessibilityNodeSnapshot,
        children: Map<NodePath, List<NodePath>>,
        byPath: Map<NodePath, AccessibilityNodeSnapshot>,
        properties: Set<String>,
        options: NodeDumpOptions,
        level: Int
    ): String = buildString {
        val pad = options.indent.repeat(level)
        append(pad).append('{')
        val fields = nodeFields(node, properties, options.includeText).toMutableList()
        fields += "path" to jsonQuote(pathString(node.path))
        fields += "parentPath" to (node.path.childIndices.dropLast(1).takeIf { node.path.childIndices.isNotEmpty() }?.let { jsonQuote(pathString(NodePath(it))) } ?: "null")
        fields += "depth" to node.path.childIndices.size.toString()
        fields += "indexInParent" to (node.indexInParent.takeIf { it >= 0 }?.toString() ?: "null")
        fields += "childCount" to node.childCount.toString()
        fields += "children" to "__CHILDREN__"
        val all = fields.distinctBy { it.first }
        all.forEachIndexed { index, (key, value) ->
            append("\n").append(pad).append(options.indent).append(jsonQuote(key)).append(": ")
            if (value == "__CHILDREN__") {
                val childNodes = children[node.path].orEmpty().mapNotNull(byPath::get)
                append('[')
                childNodes.forEachIndexed { childIndex, child ->
                    append("\n")
                    append(renderJsonNode(child, children, byPath, properties, options, level + 2))
                    if (childIndex != childNodes.lastIndex) append(',')
                }
                if (childNodes.isNotEmpty()) append('\n').append(pad).append(options.indent)
                append(']')
            } else append(value)
            if (index != all.lastIndex) append(',')
        }
        append('\n').append(pad).append('}')
    }

    private fun nodeFields(
        node: AccessibilityNodeSnapshot,
        properties: Set<String>,
        includeText: Boolean
    ): List<Pair<String, String>> = buildList {
        fun string(name: String, value: String) { if (name in properties) add(name to jsonQuote(value)) }
        fun bool(name: String, value: Boolean) { if (name in properties) add(name to value.toString()) }
        fun int(name: String, value: Int) { if (name in properties) add(name to value.toString()) }
        string("className", node.className)
        string("viewId", node.viewId)
        if (includeText) {
            node.text?.let { string("text", it) }
            node.contentDescription?.let { string("contentDescription", it) }
            node.hintText?.let { string("hintText", it) }
            node.stateDescription?.let { string("stateDescription", it) }
            node.tooltipText?.let { string("tooltipText", it) }
        }
        if ("bounds" in properties) add("bounds" to "{\"left\":${node.bounds.left},\"top\":${node.bounds.top},\"right\":${node.bounds.right},\"bottom\":${node.bounds.bottom}}")
        string("packageName", node.packageName)
        int("windowId", node.windowId)
        int("drawingOrder", node.drawingOrder)
        bool("visible", node.visible)
        bool("enabled", node.enabled)
        bool("clickable", node.clickable)
        bool("longClickable", node.longClickable)
        bool("scrollable", node.scrollable)
        bool("editable", node.editable)
        bool("checkable", node.checkable)
        bool("checked", node.checked)
        bool("selected", node.selected)
        bool("focused", node.focused)
        bool("focusable", node.focusable)
        bool("contextClickable", node.contextClickable)
        bool("password", node.password)
        if ("collection" in properties) {
            node.collection?.let { info ->
                add("collection" to "{\"rowCount\":${info.rowCount},\"columnCount\":${info.columnCount},\"hierarchical\":${info.hierarchical}}")
            }
        }
        if ("collectionItem" in properties) {
            node.collectionItem?.let { info ->
                add("collectionItem" to "{\"rowIndex\":${info.rowIndex},\"rowSpan\":${info.rowSpan},\"columnIndex\":${info.columnIndex},\"columnSpan\":${info.columnSpan},\"heading\":${info.heading},\"selected\":${info.selected}}")
            }
        }
        if ("actions" in properties) {
            add("actions" to node.actions.joinToString(prefix = "[", postfix = "]") { action ->
                "{\"id\":${action.id},\"label\":${if (includeText) action.label?.let(::jsonQuote) ?: "null" else "null"}}"
            })
        }
    }

    private fun renderText(
        snapshot: AccessibilityTreeSnapshot,
        nodes: List<AccessibilityNodeSnapshot>,
        properties: Set<String>,
        options: NodeDumpOptions,
        truncated: Boolean
    ): String = buildString {
        append("Freedom Accessibility Node Dump\n")
        append("Package: ").append(snapshot.window.packageName).append('\n')
        append("Activity: ").append(snapshot.window.activityClass).append('\n')
        append("Generation: ").append(snapshot.window.generation).append('\n')
        append("WindowId: ").append(snapshot.windowId).append('\n')
        append("Visited: ").append(snapshot.visitedNodeCount)
            .append(" | Exported: ").append(nodes.size)
            .append(" | MaxNodes: ").append(options.maxNodes).append('\n')
        append("Truncated: ").append(truncated).append('\n')
        append("Text included: ").append(options.includeText).append("\n\n")
        nodes.forEach { node ->
            append(options.indent.repeat(node.path.childIndices.size.coerceAtMost(options.maxDepth)))
            append(pathString(node.path)).append(" ")
            append(node.className.ifBlank { "Unknown" })
            if ("viewId" in properties && node.viewId.isNotBlank()) append(" id=").append(node.viewId)
            if ("bounds" in properties) append(" bounds=[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
            val states = buildList {
                if ("visible" in properties) add("visible=${node.visible}")
                if ("enabled" in properties) add("enabled=${node.enabled}")
                if ("clickable" in properties) add("clickable=${node.clickable}")
                if ("longClickable" in properties) add("longClickable=${node.longClickable}")
                if ("scrollable" in properties) add("scrollable=${node.scrollable}")
                if ("editable" in properties) add("editable=${node.editable}")
                if ("checked" in properties) add("checked=${node.checked}")
                if ("selected" in properties) add("selected=${node.selected}")
                if ("focused" in properties) add("focused=${node.focused}")
            }
            if (states.isNotEmpty()) append(" {").append(states.joinToString(", ")).append('}')
            if (options.includeText && "text" in properties) {
                node.text?.takeIf(String::isNotBlank)?.let { append(" text=").append(quoted(it)) }
            }
            if (options.includeText && "contentDescription" in properties) {
                node.contentDescription?.takeIf(String::isNotBlank)?.let { append(" desc=").append(quoted(it)) }
            }
            append('\n')
        }
    }

    private fun renderXml(
        snapshot: AccessibilityTreeSnapshot,
        nodes: List<AccessibilityNodeSnapshot>,
        children: Map<NodePath, List<NodePath>>,
        properties: Set<String>,
        options: NodeDumpOptions
    ): String {
        val byPath = nodes.associateBy { it.path }
        val roots = nodes.filter { it.path.childIndices.isEmpty() || NodePath(it.path.childIndices.dropLast(1)) !in byPath }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<hierarchy package=\"")
            append(xmlEscape(snapshot.window.packageName)).append("\" generation=\"")
                .append(snapshot.window.generation).append("\">\n")
            roots.forEach { append(renderXmlNode(it, children, byPath, properties, options, 1)) }
            append("</hierarchy>")
        }
    }

    private fun renderXmlNode(
        node: AccessibilityNodeSnapshot,
        children: Map<NodePath, List<NodePath>>,
        byPath: Map<NodePath, AccessibilityNodeSnapshot>,
        properties: Set<String>,
        options: NodeDumpOptions,
        level: Int
    ): String = buildString {
        val pad = options.indent.repeat(level)
        append(pad).append("<node")
        fun attr(name: String, value: Any?) {
            if (value != null) append(' ').append(name).append("=\"").append(xmlEscape(value.toString())).append('"')
        }
        attr("index", node.indexInParent.takeIf { it >= 0 })
        attr("text", if (options.includeText && "text" in properties) node.text else null)
        attr("resource-id", node.viewId.takeIf { "viewId" in properties && it.isNotBlank() })
        attr("class", node.className.takeIf { "className" in properties })
        attr("package", node.packageName.takeIf { "packageName" in properties && it.isNotBlank() })
        attr("content-desc", if (options.includeText && "contentDescription" in properties) node.contentDescription else null)
        listOf(
            Triple("checkable", "checkable", node.checkable),
            Triple("checked", "checked", node.checked),
            Triple("clickable", "clickable", node.clickable),
            Triple("enabled", "enabled", node.enabled),
            Triple("focusable", "focusable", node.focusable),
            Triple("focused", "focused", node.focused),
            Triple("scrollable", "scrollable", node.scrollable),
            Triple("longClickable", "long-clickable", node.longClickable),
            Triple("selected", "selected", node.selected),
            Triple("visible", "visible-to-user", node.visible),
            Triple("contextClickable", "context-clickable", node.contextClickable),
            Triple("editable", "editable", node.editable),
            Triple("password", "password", node.password)
        ).forEach { (property, attribute, value) -> if (property in properties) attr(attribute, value) }
        if ("bounds" in properties) attr("bounds", "[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
        val childNodes = children[node.path].orEmpty().mapNotNull(byPath::get)
        if (childNodes.isEmpty()) append(" />\n") else {
            append(">\n")
            childNodes.forEach { append(renderXmlNode(it, children, byPath, properties, options, level + 1)) }
            append(pad).append("</node>\n")
        }
    }

    private fun renderManifest(
        snapshot: AccessibilityTreeSnapshot,
        options: NodeDumpOptions,
        exported: Int,
        truncated: Boolean,
        reason: String?,
        properties: Set<String>
    ): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"capturedAtEpochMs\": ").append(snapshot.capturedAtEpochMs).append(",\n")
        append("  \"packageName\": ").append(jsonQuote(snapshot.window.packageName)).append(",\n")
        append("  \"activityClass\": ").append(jsonQuote(snapshot.window.activityClass)).append(",\n")
        append("  \"windowGeneration\": ").append(snapshot.window.generation).append(",\n")
        append("  \"windowId\": ").append(snapshot.windowId).append(",\n")
        append("  \"requestedMaxNodes\": ").append(options.maxNodes).append(",\n")
        append("  \"requestedMaxDepth\": ").append(options.maxDepth).append(",\n")
        append("  \"visitedNodeCount\": ").append(snapshot.visitedNodeCount).append(",\n")
        append("  \"exportedNodeCount\": ").append(exported).append(",\n")
        append("  \"walkTruncated\": ").append(snapshot.walkTruncated).append(",\n")
        append("  \"depthTruncated\": ").append(snapshot.depthTruncated).append(",\n")
        append("  \"dumpTruncated\": ").append(truncated).append(",\n")
        append("  \"stopReason\": ").append(reason?.let(::jsonQuote) ?: "null").append(",\n")
        append("  \"includeText\": ").append(options.includeText).append(",\n")
        append("  \"redactionMode\": ").append(jsonQuote(if (options.includeText) "USER_OPT_IN_UNREDACTED" else "TEXT_FIELDS_OMITTED")).append(",\n")
        append("  \"includedProperties\": [")
        append(properties.sorted().joinToString(", ") { jsonQuote(it) })
        append("]\n}")
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
}
