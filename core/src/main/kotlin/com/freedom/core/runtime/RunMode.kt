package com.freedom.core.runtime

/**
 * Runtime category used by page-selected tasks, event handling and safety policy.
 * This type does not select or persist a user's startup mode.
 */
enum class RunMode(val storageValue: String) {
    AI_REPLY("ai_reply"),
    ACCOUNT_BROWSE("account_browse");

    companion object {
        fun fromStorageValue(value: String?): RunMode? =
            values().firstOrNull { it.storageValue == value }

    }
}
