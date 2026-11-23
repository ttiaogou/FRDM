package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSelectorSnippetFormatterTest {
    @Test
    fun formatsObservedLocatorConditionsAsReusableKotlin() {
        val snippet = NodeSelectorSnippetFormatter.format(
            NodeSelector(
                viewId = "com.example:id/avatar",
                className = "android.widget.ImageView",
                clickable = true,
                enabled = true
            )
        )

        assertEquals(
            "NodeSelector(\n" +
                "    viewId = \"com.example:id/avatar\",\n" +
                "    className = \"android.widget.ImageView\",\n" +
                "    enabled = true,\n" +
                "    clickable = true\n" +
                ")",
            snippet
        )
    }

    @Test
    fun escapesKotlinStringLiteralsIncludingDollarAndControlCharacters() {
        val snippet = NodeSelectorSnippetFormatter.format(
            NodeSelector(viewId = "a\"b\$\nline\tend")
        )

        val expected = """NodeSelector(
    viewId = "a\"b\$\nline\tend"
)"""
        assertEquals(expected, snippet)
    }

    @Test
    fun formatsNestedConditionsAndBounds() {
        val snippet = NodeSelectorSnippetFormatter.format(
            NodeSelector(
                boundsIntersects = ScreenRect(1, 2, 30, 40),
                anyOf = listOf(NodeSelector(clickable = true), NodeSelector(longClickable = true))
            )
        )

        assertEquals(
            "NodeSelector(\n" +
                "    boundsIntersects = ScreenRect(left = 1, top = 2, right = 30, bottom = 40),\n" +
                "    anyOf = listOf(NodeSelector(\n    clickable = true\n), NodeSelector(\n    longClickable = true\n))\n" +
                ")",
            snippet
        )
    }
}
