package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef

internal data class MomoRememberedPageIdentity(
    val packageName: String,
    val pageKind: String,
    val windowId: Int,
    val generation: Long,
    val fingerprint: String,
    val confirmedAtUptimeMs: Long
)

/**
 * Short-lived page identity memory used only while a separately observed dialog window covers
 * the same underlying Momo window. It never authorizes clicks, scrolling, OCR or BACK.
 */
internal class MomoPageIdentityMemory(
    private val maxRetentionMs: Long = 6_000L
) {
    init { require(maxRetentionMs in 500L..15_000L) }

    @Volatile
    private var lastConfirmed: MomoRememberedPageIdentity? = null

    @Synchronized
    fun remember(window: WindowRef, pageKind: String, fingerprint: String, nowUptimeMs: Long) {
        if (window.packageName.isBlank() || window.windowId < 0 || pageKind.isBlank() || fingerprint.isBlank()) return
        lastConfirmed = MomoRememberedPageIdentity(
            packageName = window.packageName,
            pageKind = pageKind,
            windowId = window.windowId,
            generation = window.generation,
            fingerprint = fingerprint,
            confirmedAtUptimeMs = nowUptimeMs
        )
    }

    @Synchronized
    fun retainForDialog(
        window: WindowRef,
        dialogWindowId: Int,
        nowUptimeMs: Long
    ): MomoRememberedPageIdentity? {
        if (dialogWindowId < 0 || dialogWindowId == window.windowId) return null
        return retainForObstruction(window, nowUptimeMs)
    }

    /** Same-window popup/overlay path. It retains identity but never grants action permission. */
    @Synchronized
    fun retainForObstruction(
        window: WindowRef,
        nowUptimeMs: Long
    ): MomoRememberedPageIdentity? {
        val previous = lastConfirmed ?: return null
        if (window.packageName != previous.packageName ||
            window.windowId != previous.windowId ||
            window.generation != previous.generation
        ) return null
        val age = nowUptimeMs - previous.confirmedAtUptimeMs
        if (age < 0L || age > maxRetentionMs) return null
        return previous
    }

    @Synchronized
    fun clear() { lastConfirmed = null }
}
