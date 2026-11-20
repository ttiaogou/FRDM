package com.freedom.adapters.accessibility.android

import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.accountbrowse.ports.AccountBrowseGesturePort
import com.freedom.gesture.api.GestureApi

/** Binds the AccountBrowse gesture port to the shared, policy-validated GestureApi. */
internal class AccountBrowseGesturePortAdapter(
    private val delegate: GestureApi
) : AccountBrowseGesturePort {
    override fun captureContext(
        expectedWindow: WindowRef?,
        expectedCoordinates: com.freedom.core.accessibility.gesture.GestureCoordinateContext?
    ): GestureExecutionContext? = delegate.captureContext(expectedWindow, expectedCoordinates)

    override fun execute(
        request: GestureIntentRequest,
        listener: GestureExecutionListener
    ): GestureSubmission = delegate.execute(request, listener)

    override fun cancelActive(commandId: String, reason: String): Boolean =
        delegate.cancelActive(commandId, reason)
}
