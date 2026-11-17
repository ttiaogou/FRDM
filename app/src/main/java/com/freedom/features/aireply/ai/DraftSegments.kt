package com.freedom.features.aireply.ai

import java.util.regex.Pattern

/** 按扩展字素簇切分，避免拆开表情、组合字符和变体选择符。 */
internal object DraftSegments {
    private val grapheme = Pattern.compile("\\X")
    private const val MAX = 8
    private const val MIN = 3

    fun split(text: String): List<String> {
        val matcher = grapheme.matcher(text)
        val units = mutableListOf<String>()
        while (matcher.find()) units.add(matcher.group())
        if (units.size <= 1) return listOf(text)
        if (units.size <= MAX) {
            val middle = units.size / 2
            return listOf(units.subList(0, middle).joinToString(""), units.subList(middle, units.size).joinToString(""))
        }
        val result = mutableListOf<String>()
        var start = 0
        while (start < units.size) {
            val remaining = units.size - start
            if (remaining <= MAX) {
                result.add(units.subList(start, units.size).joinToString(""))
                break
            }
            val limit = minOf(MAX, remaining - MIN)
            var count = limit
            for (i in limit downTo MIN) {
                if (units[start + i - 1].last() in "，。！？；、,.!?; ") {
                    count = i
                    break
                }
            }
            result.add(units.subList(start, start + count).joinToString(""))
            start += count
        }
        return result
    }
}
