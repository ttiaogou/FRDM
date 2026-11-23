package com.freedom.core.runtime

/**
 * The mode selected for a new run. The active mode is persisted separately by
 * MonitorStore so changing UI preferences cannot silently change a running session.
 */
enum class RunMode(val storageValue: String) {
    AI_REPLY("ai_reply"),
    ACCOUNT_BROWSE("account_browse");

    companion object {
        fun fromStorageValue(value: String?): RunMode? =
            values().firstOrNull { it.storageValue == value }

        fun fromSelection(browseEnabled: Boolean, aiEnabled: Boolean): RunMode? = when {
            browseEnabled && !aiEnabled -> ACCOUNT_BROWSE
            aiEnabled && !browseEnabled -> AI_REPLY
            else -> null
        }
    }
}
