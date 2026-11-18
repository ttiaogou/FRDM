package com.freedom.features.aireply.ai

/** AI-selected message boundaries. No punctuation-based fallback is performed. */
internal object ReplyMessageSegments {
    fun split(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return emptyList()
        val lines = normalized.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size > 1) return lines
        return normalized.split("<MSG>").map(String::trim).filter(String::isNotEmpty)
    }
}
