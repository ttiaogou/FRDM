package com.freedom.core.accessibility.overlay

/** Platform-neutral placement rules shared by the Android window adapter and JVM tests. */
object FloatingButtonPlacement {
    const val HIDDEN_WIDTH_RATIO = 0.25f

    data class Point(val x: Int, val y: Int)

    data class Size(val width: Int, val height: Int)

    fun initial(
        screen: Size,
        buttonSize: Int,
        position: FloatingButtonPosition,
        topInset: Int,
        bottomInset: Int
    ): Point {
        val hidden = (buttonSize * HIDDEN_WIDTH_RATIO).toInt()
        val x = if (position.rightEdge) screen.width - buttonSize + hidden else -hidden
        val y = yFromFraction(screen.height, buttonSize, position.normalizedYFraction, topInset, bottomInset)
        return Point(x, y)
    }

    fun snap(
        screen: Size,
        buttonSize: Int,
        currentX: Int,
        currentY: Int,
        topInset: Int,
        bottomInset: Int
    ): Point {
        val right = currentX + buttonSize / 2 >= screen.width / 2
        val hidden = (buttonSize * HIDDEN_WIDTH_RATIO).toInt()
        val x = if (right) screen.width - buttonSize + hidden else -hidden
        val y = currentY.coerceIn(
            topInset.coerceAtLeast(0),
            (screen.height - buttonSize - bottomInset).coerceAtLeast(topInset.coerceAtLeast(0))
        )
        return Point(x, y)
    }

    fun persistedPosition(screen: Size, buttonSize: Int, point: Point, topInset: Int, bottomInset: Int): FloatingButtonPosition {
        val snapped = snap(screen, buttonSize, point.x, point.y, topInset, bottomInset)
        val top = topInset.coerceAtLeast(0)
        val bottom = (screen.height - buttonSize - bottomInset).coerceAtLeast(top)
        val fraction = if (bottom == top) 0f else (snapped.y - top).toFloat() / (bottom - top)
        return FloatingButtonPosition(
            rightEdge = snapped.x >= 0,
            yFraction = fraction.coerceIn(0f, 1f)
        )
    }

    fun menuOrigin(
        screen: Size,
        button: Point,
        buttonSize: Int,
        menu: Size,
        topInset: Int,
        bottomInset: Int
    ): Point {
        val rightEdge = button.x >= 0
        val preferredX = if (rightEdge) {
            button.x + buttonSize / 2 - menu.width
        } else {
            button.x + buttonSize / 2
        }
        val preferredY = button.y + buttonSize / 2 - menu.height / 2
        return Point(
            preferredX.coerceIn(0, (screen.width - menu.width).coerceAtLeast(0)),
            preferredY.coerceIn(
                topInset.coerceAtLeast(0),
                (screen.height - menu.height - bottomInset).coerceAtLeast(topInset.coerceAtLeast(0))
            )
        )
    }

    private fun yFromFraction(
        screenHeight: Int,
        buttonSize: Int,
        fraction: Float,
        topInset: Int,
        bottomInset: Int
    ): Int {
        val top = topInset.coerceAtLeast(0)
        val bottom = (screenHeight - buttonSize - bottomInset).coerceAtLeast(top)
        return (top + (bottom - top) * fraction).toInt().coerceIn(top, bottom)
    }
}
