package com.freedom.features.aireply.ai

/** AI-selected message boundaries. No punctuation-based fallback is performed. */
internal object ReplyMessageSegments {
    data class ParseResult(
        val messages: List<String>,
        val delimiterCount: Int,
        val lineBreakCount: Int,
        val fallbackToSingleMessage: Boolean
    )

    fun split(text: String): List<String> = parse(text).messages

    fun parse(text: String): ParseResult {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return ParseResult(emptyList(), 0, 0, false)
        val lineBreakCount = normalized.count { it == '\n' }
        val lines = normalized.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size > 1) {
            return ParseResult(lines, 0, lineBreakCount, false)
        }
        val delimiterCount = normalized.windowed("<MSG>".length).count { it == "<MSG>" }
        val messages = normalized.split("<MSG>").map(String::trim).filter(String::isNotEmpty)
        return ParseResult(messages, delimiterCount, lineBreakCount, delimiterCount == 0)
    }
}
