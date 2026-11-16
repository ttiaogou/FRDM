package com.freedom.adapters.accessibility.android

import android.graphics.Path
import com.freedom.core.accessibility.api.GesturePathCommand

/** Single Android Path conversion shared by dispatch and gesture visualization. */
object AndroidGesturePathFactory {
    fun create(commands: List<GesturePathCommand>): Path? {
        if (commands.isEmpty()) return null
        val path = Path()
        var hasMove = false
        for (command in commands) {
            when (command) {
                is GesturePathCommand.MoveTo -> {
                    path.moveTo(command.point.x, command.point.y)
                    hasMove = true
                }
                is GesturePathCommand.LineTo -> {
                    if (!hasMove) return null
                    path.lineTo(command.point.x, command.point.y)
                }
                is GesturePathCommand.CubicTo -> {
                    if (!hasMove) return null
                    path.cubicTo(
                        command.control1.x, command.control1.y,
                        command.control2.x, command.control2.y,
                        command.end.x, command.end.y
                    )
                }
            }
        }
        return path.takeIf { hasMove }
    }
}
