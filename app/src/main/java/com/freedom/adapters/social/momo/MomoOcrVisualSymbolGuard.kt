package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.OcrContentKind
import com.freedom.features.aireply.domain.message.OcrContentRect
import com.freedom.features.aireply.domain.message.OcrContentToken
import com.freedom.features.aireply.domain.message.OcrTokenSource
import com.freedom.features.aireply.domain.message.OcrTokenStatus

/** A rectangle in OCR-crop-local pixel coordinates. */
internal data class OcrVisualRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersectionArea(other: OcrVisualRect): Int {
        val w = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val h = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        return w * h
    }
}

internal data class OcrVisualSymbol(
    val text: String,
    val bounds: OcrVisualRect,
    val confidence: Float
)

internal data class OcrVisualLine(
    val text: String,
    val bounds: OcrVisualRect,
    val symbols: List<OcrVisualSymbol>
)

/** The existing rendered OCR text plus typed content evidence. */
internal data class OcrVisualReconciliation(
    val text: String,
    val tokens: List<OcrContentToken>
)

/**
 * Conservative guard for colored emoji/pictograms inside Momo's custom-drawn message body.
 * This does not guess the emoji's meaning. If a colored glyph is omitted or misread as an ASCII
 * character (for example, a yellow face being returned as "e"), it inserts an explicit marker so
 * a misleading OCR character is not passed to the AI conversation layer.
 */
internal object MomoOcrVisualSymbolGuard {
    const val UNKNOWN_VISUAL_SYMBOL = "[表情/图形未识别]"
    const val UNCERTAIN_TEXT_SYMBOL = "[标点/符号待确认]"
    const val UNCERTAIN_SYMBOL_ONLY = "[图形/符号内容待确认]"

    /**
     * Detects compact, saturated color components from the already-cropped message body.
     * pixelAt must return ARGB. The caller owns the source bitmap and its lifetime.
     */
    fun detectColorRegions(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): List<OcrVisualRect> {
        if (width < MIN_IMAGE_EDGE || height < MIN_IMAGE_EDGE) return emptyList()
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        val regions = ArrayList<OcrVisualRect>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val start = y * width + x
                if (visited[start]) continue
                visited[start] = true
                if (!isSaturatedPixel(pixelAt(x, y))) continue

                var head = 0
                var tail = 0
                queue[tail++] = start
                var minX = x
                var minY = y
                var maxX = x
                var maxY = y
                var pixelCount = 0

                while (head < tail) {
                    val current = queue[head++]
                    val currentX = current % width
                    val currentY = current / width
                    pixelCount++
                    minX = minOf(minX, currentX)
                    minY = minOf(minY, currentY)
                    maxX = maxOf(maxX, currentX)
                    maxY = maxOf(maxY, currentY)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = currentX + dx
                            val ny = currentY + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val next = ny * width + nx
                            if (visited[next]) continue
                            visited[next] = true
                            if (isSaturatedPixel(pixelAt(nx, ny))) queue[tail++] = next
                        }
                    }
                }

                val boxWidth = maxX - minX + 1
                val boxHeight = maxY - minY + 1
                val aspect = boxWidth.toFloat() / boxHeight.toFloat()
                val boxArea = boxWidth * boxHeight
                val fillRatio = pixelCount.toFloat() / boxArea.coerceAtLeast(1)
                if (boxWidth >= MIN_GLYPH_EDGE &&
                    boxHeight >= MIN_GLYPH_EDGE &&
                    boxWidth <= MAX_GLYPH_EDGE &&
                    boxHeight <= MAX_GLYPH_EDGE &&
                    aspect in MIN_GLYPH_ASPECT..MAX_GLYPH_ASPECT &&
                    pixelCount >= MIN_COLOR_PIXELS &&
                    fillRatio >= MIN_COMPONENT_FILL_RATIO
                ) {
                    regions += OcrVisualRect(minX, minY, maxX + 1, maxY + 1)
                }
            }
        }
        return mergeNearbyRegions(regions)
    }

    /** Preserve exact legacy OCR rendering while collecting typed evidence. */
    fun reconcile(lines: List<OcrVisualLine>, colorRegions: List<OcrVisualRect>): String =
        reconcileWithTokens(lines, colorRegions).text

    fun reconcileWithTokens(
        lines: List<OcrVisualLine>,
        colorRegions: List<OcrVisualRect>
    ): OcrVisualReconciliation {
        if (lines.isEmpty()) {
            if (colorRegions.isEmpty()) return OcrVisualReconciliation("", emptyList())
            return OcrVisualReconciliation(
                UNKNOWN_VISUAL_SYMBOL,
                listOf(OcrContentToken(
                    kind = OcrContentKind.PLACEHOLDER,
                    status = OcrTokenStatus.UNVERIFIED,
                    text = UNKNOWN_VISUAL_SYMBOL,
                    source = OcrTokenSource.VISUAL_GUARD,
                    lineIndex = 0,
                    symbolBounds = colorRegions.singleOrNull()?.asContentRect()
                ))
            )
        }
        val sorted = lines.sortedWith(compareBy<OcrVisualLine> { it.bounds.top }.thenBy { it.bounds.left })
        val usedRegions = HashSet<Int>()
        val rendered = sorted.mapIndexed { index, line ->
            reconcileLine(line, colorRegions, usedRegions, index)
        }
        return OcrVisualReconciliation(
            rendered.joinToString("\n") { it.text },
            rendered.flatMap { it.tokens }
        )
    }

    private data class AnnotatedChar(
        val char: Char,
        val markerSource: OcrTokenSource? = null,
        val markerBounds: OcrVisualRect? = null
    )

    private data class ReconciledLine(val text: String, val tokens: List<OcrContentToken>)

    private fun OcrVisualRect.asContentRect(): OcrContentRect =
        OcrContentRect(left, top, right, bottom)

    private fun reconcileLine(
        line: OcrVisualLine,
        allRegions: List<OcrVisualRect>,
        usedRegions: MutableSet<Int>,
        lineIndex: Int
    ): ReconciledLine {
        if (line.text.isBlank()) return ReconciledLine("", emptyList())
        val indexedSymbols = indexSymbols(line)
        val edits = ArrayList<TextEdit>()

        // A low-confidence OCR symbol is an explicit uncertainty marker, not verified text.
        indexedSymbols.forEach { indexed ->
            val overlapsColorRegion = allRegions.any { region ->
                indexed.symbol.bounds.intersectionArea(region) > 0
            }
            val confidence = indexed.symbol.confidence
            if (!overlapsColorRegion && confidence > 0f &&
                confidence < LOW_CONFIDENCE_SYMBOL_THRESHOLD &&
                isPunctuationOrSymbol(indexed.symbol.text)
            ) {
                edits += TextEdit(
                    indexed.start, indexed.end, UNCERTAIN_TEXT_SYMBOL,
                    OcrTokenSource.OCR_SYMBOL, indexed.symbol.bounds
                )
            }
        }

        val lineRegions = allRegions.withIndex().filter { (_, region) ->
            region.centerY >= line.bounds.top - LINE_VERTICAL_TOLERANCE_PX &&
                region.centerY <= line.bounds.bottom + LINE_VERTICAL_TOLERANCE_PX &&
                region.centerX >= line.bounds.left - LINE_HORIZONTAL_TOLERANCE_PX &&
                region.centerX <= line.bounds.right + LINE_HORIZONTAL_TOLERANCE_PX
        }

        // Without per-character boxes, never guess a visual symbol's location in text.
        if (lineRegions.isNotEmpty() && indexedSymbols.isEmpty()) {
            val markerBounds = lineRegions.singleOrNull()?.value
            val chars = (UNCERTAIN_SYMBOL_ONLY.map {
                AnnotatedChar(it, OcrTokenSource.VISUAL_GUARD, markerBounds)
            } + AnnotatedChar(' ') + line.text.map { AnnotatedChar(it) })
            return renderLine(chars, lineIndex, line.bounds, ambiguous = true)
        }

        for ((regionIndex, region) in lineRegions) {
            if (regionIndex in usedRegions) continue
            val overlapping = indexedSymbols.mapNotNull { indexed ->
                val overlapArea = indexed.symbol.bounds.intersectionArea(region)
                if (overlapArea <= 0) return@mapNotNull null
                val symbolArea = indexed.symbol.bounds.area.coerceAtLeast(1)
                val regionArea = region.area.coerceAtLeast(1)
                val overlapRatio = overlapArea.toFloat() / minOf(symbolArea, regionArea)
                if (overlapRatio < MIN_SYMBOL_REGION_OVERLAP) null else indexed
            }

            val nonEmojiOverlaps = overlapping.filterNot { isUnicodeEmojiToken(it.symbol.text) }
            if (nonEmojiOverlaps.isNotEmpty()) {
                nonEmojiOverlaps.forEach { indexed ->
                    edits += TextEdit(indexed.start, indexed.end, "")
                }
                val insertionIndex = nonEmojiOverlaps.minOf { it.start }
                edits += TextEdit(
                    insertionIndex, insertionIndex, UNKNOWN_VISUAL_SYMBOL,
                    OcrTokenSource.VISUAL_GUARD, region
                )
            } else if (overlapping.isEmpty()) {
                val insertionIndex = indexedSymbols.firstOrNull {
                    it.symbol.bounds.centerX >= region.centerX
                }?.start ?: line.text.length
                edits += TextEdit(
                    insertionIndex, insertionIndex, UNKNOWN_VISUAL_SYMBOL,
                    OcrTokenSource.VISUAL_GUARD, region
                )
            }
            usedRegions += regionIndex
        }

        // Apply the same descending edits to annotated characters as the legacy StringBuilder.
        // Tracking their origin prevents literal OCR text matching a marker from being mislabeled.
        val chars = line.text.map { AnnotatedChar(it) }.toMutableList()
        val ordered = edits.distinctBy { Triple(it.start, it.end, it.replacement) }
            .sortedWith(compareByDescending<TextEdit> { it.start }.thenByDescending { it.end })
        for (edit in ordered) {
            if (edit.start !in 0..chars.size || edit.end !in edit.start..chars.size) continue
            chars.subList(edit.start, edit.end).clear()
            chars.addAll(edit.start, edit.replacement.map {
                AnnotatedChar(it, edit.source, edit.bounds)
            })
        }
        return renderLine(
            chars, lineIndex, line.bounds,
            ambiguous = lineRegions.isNotEmpty() ||
                edits.any { it.replacement == UNCERTAIN_TEXT_SYMBOL }
        )
    }

    private fun renderLine(
        chars: List<AnnotatedChar>,
        lineIndex: Int,
        bounds: OcrVisualRect,
        ambiguous: Boolean
    ): ReconciledLine {
        val text = chars.joinToString("") { it.char.toString() }
        val tokens = ArrayList<OcrContentToken>()
        var index = 0
        while (index < text.length) {
            val origin = chars[index].markerSource
            if (origin != null) {
                val markerBounds = chars[index].markerBounds
                var end = index + 1
                while (end < text.length &&
                    chars[end].markerSource == origin &&
                    chars[end].markerBounds == markerBounds
                ) end++
                tokens += OcrContentToken(
                    kind = OcrContentKind.PLACEHOLDER,
                    status = OcrTokenStatus.UNVERIFIED,
                    text = text.substring(index, end),
                    source = origin,
                    lineIndex = lineIndex,
                    lineBounds = bounds.asContentRect(),
                    symbolBounds = markerBounds?.asContentRect()
                )
                index = end
                continue
            }
            val emojiLength = unicodeEmojiSpanLength(text, index)
            if (emojiLength > 0) {
                tokens += OcrContentToken(
                    kind = OcrContentKind.EMOJI,
                    status = OcrTokenStatus.UNVERIFIED,
                    text = text.substring(index, index + emojiLength),
                    source = OcrTokenSource.UNICODE_EMOJI,
                    lineIndex = lineIndex,
                    lineBounds = bounds.asContentRect()
                )
                index += emojiLength
                continue
            }
            var end = index
            while (end < text.length && chars[end].markerSource == null &&
                unicodeEmojiSpanLength(text, end) == 0
            ) end += Character.charCount(text.codePointAt(end))
            tokens += OcrContentToken(
                kind = OcrContentKind.TEXT,
                status = if (ambiguous) OcrTokenStatus.AMBIGUOUS else OcrTokenStatus.RECOGNIZED,
                text = text.substring(index, end),
                source = OcrTokenSource.OCR_LINE,
                lineIndex = lineIndex,
                lineBounds = bounds.asContentRect()
            )
            index = end
        }
        return ReconciledLine(text, tokens)
    }

    private fun unicodeEmojiSpanLength(text: String, offset: Int): Int {
        if (offset >= text.length) return 0
        val first = text.codePointAt(offset)
        var end = offset + Character.charCount(first)
        if (first in '0'.code..'9'.code || first == '#'.code || first == '*'.code) {
            if (end < text.length && text.codePointAt(end) == 0xFE0F) end++
            return if (end < text.length && text.codePointAt(end) == 0x20E3) end + 1 - offset else 0
        }
        if (!isLikelyEmojiCodePoint(first) && first !in 0x2300..0x23FF) return 0
        while (end < text.length) {
            val next = text.codePointAt(end)
            if (next != 0xFE0F && next != 0xFE0E && next != 0x200D &&
                next !in 0x1F3FB..0x1F3FF &&
                !isLikelyEmojiCodePoint(next) && next !in 0x2300..0x23FF
            ) break
            end += Character.charCount(next)
        }
        return end - offset
    }

    private fun isUnicodeEmojiToken(value: String): Boolean {
        if (value.isEmpty()) return false
        var offset = 0
        var hasEmojiBase = false
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val isEmojiPart = isLikelyEmojiCodePoint(codePoint) ||
                codePoint in 0x1F3FB..0x1F3FF ||
                codePoint == 0xFE0E || codePoint == 0xFE0F || codePoint == 0x200D
            if (!isEmojiPart) return false
            if (codePoint != 0xFE0E && codePoint != 0xFE0F && codePoint != 0x200D) {
                hasEmojiBase = true
            }
            offset += Character.charCount(codePoint)
        }
        return hasEmojiBase
    }

    private fun indexSymbols(line: OcrVisualLine): List<IndexedSymbol> {
        var cursor = 0
        return line.symbols.sortedBy { it.bounds.left }.mapNotNull { symbol ->
            if (symbol.text.isEmpty()) return@mapNotNull null
            val index = line.text.indexOf(symbol.text, cursor)
            if (index < 0) return@mapNotNull null
            val end = index + symbol.text.length
            cursor = end
            IndexedSymbol(symbol, index, end)
        }
    }

    private fun isPunctuationOrSymbol(value: String): Boolean {
        if (value.isEmpty()) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (isLikelyEmojiCodePoint(codePoint)) return false
            when (Character.getType(codePoint)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt() -> Unit
                else -> return false
            }
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun isLikelyEmojiCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x1F1E6..0x1F1FF ||
            codePoint == 0xFE0E ||
            codePoint == 0xFE0F ||
            codePoint == 0x200D

    private fun isSaturatedPixel(argb: Int): Boolean {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < MIN_ALPHA) return false
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        return maximum >= MIN_COLOR_BRIGHTNESS && (maximum - minimum) >= MIN_COLOR_SPREAD
    }

    private fun mergeNearbyRegions(regions: List<OcrVisualRect>): List<OcrVisualRect> {
        if (regions.size < 2) return regions
        val pending = regions.toMutableList()
        val merged = ArrayList<OcrVisualRect>()
        while (pending.isNotEmpty()) {
            var current = pending.removeAt(0)
            var changed: Boolean
            do {
                changed = false
                val iterator = pending.listIterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (horizontalGap(current, candidate) <= MERGE_GAP_PX &&
                        verticalGap(current, candidate) <= MERGE_GAP_PX
                    ) {
                        current = OcrVisualRect(
                            minOf(current.left, candidate.left),
                            minOf(current.top, candidate.top),
                            maxOf(current.right, candidate.right),
                            maxOf(current.bottom, candidate.bottom)
                        )
                        iterator.remove()
                        changed = true
                    }
                }
            } while (changed)
            merged += current
        }
        return merged
    }

    private fun horizontalGap(first: OcrVisualRect, second: OcrVisualRect): Int =
        maxOf(0, maxOf(first.left - second.right, second.left - first.right))

    private fun verticalGap(first: OcrVisualRect, second: OcrVisualRect): Int =
        maxOf(0, maxOf(first.top - second.bottom, second.top - first.bottom))

    private data class IndexedSymbol(
        val symbol: OcrVisualSymbol,
        val start: Int,
        val end: Int
    )

    private data class TextEdit(
        val start: Int,
        val end: Int,
        val replacement: String,
        val source: OcrTokenSource? = null,
        val bounds: OcrVisualRect? = null
    )

    private const val MIN_IMAGE_EDGE = 8
    private const val MIN_GLYPH_EDGE = 6
    private const val MAX_GLYPH_EDGE = 128
    private const val MIN_COLOR_PIXELS = 18
    private const val MIN_ALPHA = 160
    private const val MIN_COLOR_BRIGHTNESS = 125
    private const val MIN_COLOR_SPREAD = 65
    private const val MIN_GLYPH_ASPECT = 0.45f
    private const val MAX_GLYPH_ASPECT = 2.2f
    private const val MIN_COMPONENT_FILL_RATIO = 0.04f
    private const val MIN_SYMBOL_REGION_OVERLAP = 0.18f
    private const val LOW_CONFIDENCE_SYMBOL_THRESHOLD = 0.42f
    private const val LINE_VERTICAL_TOLERANCE_PX = 6
    private const val LINE_HORIZONTAL_TOLERANCE_PX = 4
    private const val MERGE_GAP_PX = 3
}
