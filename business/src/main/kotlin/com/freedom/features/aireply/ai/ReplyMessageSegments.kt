package com.freedom.features.aireply.ai

/** 仅按换行拆分模型回复；不使用其他分隔符或标点符号作为备用依据。 */
internal object ReplyMessageSegments {
    data class ParseResult(
        val messages: List<String>,
        val lineBreakCount: Int,
        val fallbackToSingleMessage: Boolean
    )

    fun split(text: String): List<String> = parse(text).messages

    fun parse(text: String): ParseResult {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return ParseResult(emptyList(), 0, false)
        val lineBreakCount = normalized.count { it == '\n' }
        val messages = normalized.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return ParseResult(messages, lineBreakCount, messages.size == 1)
    }
}
