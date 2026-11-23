package com.freedom.core.accessibility.api

/** Runtime-facing platform SPI. Business features must never depend on or invoke this port. */
interface GestureExecutionPort {
    fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission

    /**
     * Invokes [onPlatformDispatch] at the platform-dispatch boundary. Concrete platform
     * implementations should override this overload and call it only after readiness checks and
     * gesture construction, immediately before the platform dispatch API.
     */
    fun dispatchGesture(
        spec: GestureSpec,
        listener: GestureResultListener,
        onPlatformDispatch: () -> Unit
    ): GestureSubmission {
        val submission = dispatchGesture(spec, listener)
        if (submission == GestureSubmission.ACCEPTED) onPlatformDispatch()
        return submission
    }
}
