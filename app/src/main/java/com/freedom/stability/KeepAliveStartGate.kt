package com.freedom.stability

/** Coalesces concurrent/repeated requests for the process-wide keep-alive Service. */
internal class KeepAliveStartGate {
    private var startRequestedOrActive = false

    @Synchronized
    fun tryAcquireStart(): Boolean {
        if (startRequestedOrActive) return false
        startRequestedOrActive = true
        return true
    }

    @Synchronized
    fun markStarted() {
        startRequestedOrActive = true
    }

    @Synchronized
    fun markStopped() {
        startRequestedOrActive = false
    }

    @Synchronized
    fun isStartRequestedOrActive(): Boolean = startRequestedOrActive
}
