package com.freedom.core.accessibility.diagnostics

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.query.NodeQueryResult
import com.freedom.core.accessibility.query.NodeSelectorExplanation
import java.util.ArrayDeque

/** Bounded, text-free selector diagnostics retained only in memory until the user exports evidence. */
data class NodeSelectorDiagnosticRecord(
    val capturedAtEpochMs: Long,
    val window: WindowRef,
    val queryLabel: String,
    val matchCount: Int,
    val matchCountExact: Boolean,
    val visitedNodeCount: Int,
    val truncated: Boolean,
    val ambiguous: Boolean,
    val stopReason: String?,
    val elapsedMs: Long,
    val steps: List<Step>,
    val matchedPaths: List<String>,
    val nearMissPaths: List<String>
) {
    data class Step(val index: Int, val criterion: String, val matched: Int, val cumulative: Int)
}

/**
 * A small in-memory ring buffer for selector explanations. It deliberately excludes query text,
 * node text, content descriptions and user identifiers from the exported evidence.
 */
object NodeSelectorDiagnosticsBuffer {
    private const val MAX_RECORDS = 80
    private val records = ArrayDeque<NodeSelectorDiagnosticRecord>()
    private var droppedRecordCount = 0L

    @Synchronized
    fun record(
        window: WindowRef,
        queryLabel: String,
        queryResult: NodeQueryResult,
        explanation: NodeSelectorExplanation
    ) {
        val safeLabel = queryLabel.filter { it >= ' ' && it != '\u007f' }.take(96)
        if (safeLabel.isBlank()) return
        val steps = explanation.steps.map { step ->
            NodeSelectorDiagnosticRecord.Step(
                index = step.index,
                criterion = safeCriterion(step.filter),
                matched = step.matched,
                cumulative = step.cumulative
            )
        }
        records.addLast(
            NodeSelectorDiagnosticRecord(
                capturedAtEpochMs = System.currentTimeMillis(),
                window = window,
                queryLabel = safeLabel,
                matchCount = explanation.matchCount,
                matchCountExact = explanation.matchCountExact,
                visitedNodeCount = queryResult.visitedNodeCount,
                truncated = queryResult.truncated || explanation.snapshotTruncated || explanation.queryTruncated,
                ambiguous = queryResult.ambiguous || explanation.matchCount > 1,
                stopReason = queryResult.stopReason ?: explanation.stopReason,
                elapsedMs = maxOf(queryResult.queryElapsedMs, explanation.elapsedMs),
                steps = steps,
                matchedPaths = explanation.matches.map { it.childIndices.joinToString("/") },
                nearMissPaths = explanation.nearMisses.map { it.childIndices.joinToString("/") }
            )
        )
        while (records.size > MAX_RECORDS) {
            records.removeFirst()
            droppedRecordCount++
        }
    }

    @Synchronized
    fun snapshot(): List<NodeSelectorDiagnosticRecord> = records.toList()

    /** Number of older records evicted from this run's bounded ring buffer. */
    @Synchronized
    fun droppedCount(): Long = droppedRecordCount

    @Synchronized
    fun clear() {
        records.clear()
        droppedRecordCount = 0L
    }

    @Synchronized
    fun toJsonLines(window: WindowRef? = null): String {
        val selected = records.filter { window == null || it.window == window }
        return selected.joinToString(separator = "\n", postfix = if (selected.isEmpty()) "" else "\n") {
            it.toJson()
        }
    }

    private fun safeCriterion(value: String): String {
        val key = value.substringBefore('=')
        return when {
            key.startsWith("text") || key.startsWith("description") -> "$key=[REDACTED]"
            else -> value.take(120)
        }
    }

    private fun NodeSelectorDiagnosticRecord.toJson(): String = buildString {
        append('{')
        append("\"capturedAtEpochMs\":").append(capturedAtEpochMs).append(',')
        append("\"packageName\":").append(jsonQuote(window.packageName)).append(',')
        append("\"activityClass\":").append(jsonQuote(window.activityClass)).append(',')
        append("\"windowGeneration\":").append(window.generation).append(',')
        append("\"queryLabel\":").append(jsonQuote(queryLabel)).append(',')
        append("\"matchCount\":").append(matchCount).append(',')
        append("\"matchCountExact\":").append(matchCountExact).append(',')
        append("\"visitedNodeCount\":").append(visitedNodeCount).append(',')
        append("\"truncated\":").append(truncated).append(',')
        append("\"ambiguous\":").append(ambiguous).append(',')
        append("\"stopReason\":").append(stopReason?.let(::jsonQuote) ?: "null").append(',')
        append("\"elapsedMs\":").append(elapsedMs).append(',')
        append("\"steps\":[")
        append(steps.joinToString(",") { step ->
            "{\"index\":${step.index},\"criterion\":${jsonQuote(step.criterion)},\"matched\":${step.matched},\"cumulative\":${step.cumulative}}"
        })
        append("],\"matchedPaths\":")
        append(matchedPaths.joinToString(prefix = "[", postfix = "]") { jsonQuote(it) })
        append(",\"nearMissPaths\":")
        append(nearMissPaths.joinToString(prefix = "[", postfix = "]") { jsonQuote(it) })
        append('}')
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
