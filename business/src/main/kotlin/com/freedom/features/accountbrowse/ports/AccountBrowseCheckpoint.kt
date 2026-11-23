package com.freedom.features.accountbrowse.ports

/** Durable, page-neutral progress needed to re-enter automatic browsing safely. */
data class AccountBrowseCheckpoint(
    val phase: String,
    val sourceFingerprint: String,
    val detailFingerprint: String,
    val lastStableFingerprint: String,
    val sourceSnapshot: String,
    val currentActivityClass: String,
    val imageBrowserActive: Boolean,
    val swipeCount: Int,
    val feedRefreshRecoveryActive: Boolean,
    val feedRefreshLastSnapshot: String,
    val savedAtEpochMs: Long
)

interface AccountBrowseCheckpointRepository {
    fun load(nowEpochMs: Long = System.currentTimeMillis()): AccountBrowseCheckpoint?
    fun save(checkpoint: AccountBrowseCheckpoint)
    fun clear()
}
