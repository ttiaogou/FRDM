package com.freedom.adapters.accessibility.android

import android.content.Context
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureVisualizationPort

/** Android accessibility-overlay adapter for the shared screen-gesture visualization port. */
class AndroidGestureVisualizer(
    private val serviceContext: Context
) : GestureVisualizationPort {
    override fun show(command: GestureCommand): Boolean =
        GestureTrailOverlay.showGesture(serviceContext, command)

    override fun onCompleted(command: GestureCommand) =
        GestureTrailOverlay.completeGesture(command.id)

    override fun onCancelled(command: GestureCommand) =
        GestureTrailOverlay.cancelGesture(command.id)

    override fun onRejected(command: GestureCommand, reason: String) =
        GestureTrailOverlay.rejectGesture(command.id, reason)

    override fun onFailed(command: GestureCommand, reason: String) =
        GestureTrailOverlay.failGesture(command.id, reason)

    override fun clear() = GestureTrailOverlay.clear()
}
