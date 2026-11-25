package com.freedom.features.accountbrowse

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.accountbrowse.ports.ImageIndexReadResult
import com.freedom.features.accountbrowse.ports.ImageIndexUnavailableReason
import com.freedom.features.accountbrowse.ports.ImageSequencePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageIndexReadResultTest {
    private val window = WindowRef("com.immomo.momo", "ImageBrowser", 7L, 2430)

    @Test
    fun acceptsOneBasedCountersIncludingSingleImageAndBothBoundaries() {
        val examples = mapOf(
            "8/9" to ImageSequencePosition(8, 9),
            "3/5" to ImageSequencePosition(3, 5),
            "1/1" to ImageSequencePosition(1, 1),
            "1/9" to ImageSequencePosition(1, 9),
            "9/9" to ImageSequencePosition(9, 9),
            " 3 / 5 " to ImageSequencePosition(3, 5),
            "2147483647/2147483647" to ImageSequencePosition(Int.MAX_VALUE, Int.MAX_VALUE)
        )
        examples.forEach { (text, expected) ->
            assertEquals(text, expected, ImageSequencePosition.parse(text))
        }
    }

    @Test
    fun rejectsMissingMalformedImpossibleAndOverflowingCounters() {
        listOf("", " ", "0/9", "1/0", "10/9", "-1/9", "1/-9",
            "3", "3/5/7", "3.0/5", "image 3/5", "3/5 pictures", "3/  ",
            "2147483648/2147483648", "1/9999999999999999999999").forEach {
            assertNull(it, ImageSequencePosition.parse(it))
        }
    }

    @Test
    fun acceptsOnlyUniqueValidLabelAndPreservesWindowEvidence() {
        val result = ImageIndexReadResult.fromVisibleLabels(listOf("8/9"), "window-7", window)
        assertEquals(ImageIndexReadResult.Confirmed(ImageSequencePosition(8, 9), "window-7", window), result)
        assertEquals(ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.INDEX_MISSING),
            ImageIndexReadResult.fromVisibleLabels(emptyList(), "window-7", window))
        assertEquals(ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.INDEX_INVALID),
            ImageIndexReadResult.fromVisibleLabels(listOf("10/9"), "window-7", window))
        for (labels in listOf(listOf("8/9", "7/9"), listOf("8/9", "8/9"))) {
            assertEquals(ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.INDEX_AMBIGUOUS),
                ImageIndexReadResult.fromVisibleLabels(labels, "window-7", window))
        }
        assertEquals(ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED),
            ImageIndexReadResult.fromVisibleLabels(listOf("8/9"), "", window))
        assertEquals(ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED),
            ImageIndexReadResult.fromVisibleLabels(listOf("8/9"), "window-7", window.copy(windowId = -1)))
    }
}
