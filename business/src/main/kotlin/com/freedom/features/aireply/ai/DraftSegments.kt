package com.freedom.features.aireply.ai

import java.util.regex.Pattern

/** 按扩展字素簇切分；不得拆开表情、组合字符和变体选择符。 */
internal object DraftSegments {
    private val grapheme = Pattern.compile("\\X")
    private const val TARGET_MIN = 2
    private const val TARGET_MAX = 3

    fun split(text: String): List<String> {
        val matcher = grapheme.matcher(text)
        val units = mutableListOf<String>()
        while (matcher.find()) units.add(matcher.group())
        if (units.size <= 1) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < units.size) {
            val remaining = units.size - start
            val candidates = (TARGET_MIN..TARGET_MAX).filter { count ->
                val tail = remaining - count
                tail == 0 || tail >= TARGET_MIN
            }
            val count = candidates.maxWithOrNull(
                compareBy<Int> { count ->
                    if (start + count <= units.size && isPreferredBoundary(units[start + count - 1])) 1 else 0
                }.thenBy { count -> if (count == preferredCount(remaining)) 1 else 0 }
            ) ?: error("Unable to partition $remaining grapheme clusters into 2-3 sized segments")
            result.add(units.subList(start, start + count).joinToString(""))
            start += count
        }
        return result
    }

    private fun preferredCount(remaining: Int): Int = if (remaining % 3 == 1) TARGET_MIN else TARGET_MAX
    private fun isPreferredBoundary(unit: String): Boolean =
        unit.lastOrNull()?.let { it in "，。！？；：、,.!?;: " } == true
}
