package com.freedom.data

import android.content.Context
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
import org.json.JSONObject

/** SharedPreferences storage for automatic-browse progress; stale checkpoints are ignored. */
internal class AccountBrowseCheckpointDataStore(context: Context) : AccountBrowseCheckpointRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized override fun load(nowEpochMs: Long): AccountBrowseCheckpoint? = runCatching {
        val json = preferences.getString(KEY, null)?.let(::JSONObject) ?: return null
        val saved = json.optLong("savedAtEpochMs", 0L)
        if (saved <= 0L || nowEpochMs < saved || nowEpochMs - saved > MAX_AGE_MS) {
            clear(); return null
        }
        AccountBrowseCheckpoint(
            json.optString("phase"), json.optString("sourceFingerprint"),
            json.optString("detailFingerprint"), json.optString("lastStableFingerprint"),
            json.optString("sourceSnapshot"), json.optString("currentActivityClass"),
            json.optBoolean("imageBrowserActive"), json.optInt("swipeCount"),
            json.optBoolean("feedRefreshRecoveryActive"), json.optString("feedRefreshLastSnapshot"), saved
        )
    }.getOrElse { clear(); null }

    @Synchronized override fun save(checkpoint: AccountBrowseCheckpoint) {
        val json = JSONObject()
            .put("phase", checkpoint.phase)
            .put("sourceFingerprint", checkpoint.sourceFingerprint)
            .put("detailFingerprint", checkpoint.detailFingerprint)
            .put("lastStableFingerprint", checkpoint.lastStableFingerprint)
            .put("sourceSnapshot", checkpoint.sourceSnapshot)
            .put("currentActivityClass", checkpoint.currentActivityClass)
            .put("imageBrowserActive", checkpoint.imageBrowserActive)
            .put("swipeCount", checkpoint.swipeCount)
            .put("feedRefreshRecoveryActive", checkpoint.feedRefreshRecoveryActive)
            .put("feedRefreshLastSnapshot", checkpoint.feedRefreshLastSnapshot)
            .put("savedAtEpochMs", System.currentTimeMillis())
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized override fun clear() { preferences.edit().remove(KEY).apply() }

    private companion object {
        const val PREFERENCES = "account_browse_checkpoint"
        const val KEY = "checkpoint_v1"
        const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
