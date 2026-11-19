package com.freedom.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpointRepository

/** SharedPreferences storage for message-list traversal identity/state only. */
internal class PrivateMessagesTraversalCheckpointDataStore(context: Context) : PrivateMessagesTraversalCheckpointRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint? {
        val raw = preferences.getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val saved = json.optLong("savedAtEpochMs", 0L)
            if (saved <= 0L || nowEpochMs < saved || nowEpochMs - saved > MAX_AGE_MS) {
                clear(); return null
            }
            PrivateMessagesTraversalCheckpoint(
                json.readSet("attemptedSessionKeys"), json.readSet("lockedUnreadSessionKeys"),
                json.readSet("ambiguousSessionIdentityKeys"), json.readList("lastStableVisibleKeys"),
                json.optString("phase", "POSITIONING_AT_BOTTOM"), json.optBoolean("atTop"),
                json.optBoolean("initialInboxPositionCheckCompleted"),
                json.optBoolean("initialInboxTopPositioningCompleted"),
                json.optBoolean("initialUnreadGateSwipeCompleted"),
                json.optBoolean("lockedUnreadSetFinalized"), json.optBoolean("normalInboxHadUnread"),
                json.optBoolean("normalInboxUnreadStateUnknown"), saved
            )
        }.getOrElse { clear(); null }
    }

    @Synchronized override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) {
        val json = JSONObject()
            .put("savedAtEpochMs", System.currentTimeMillis())
            .put("attemptedSessionKeys", JSONArray(checkpoint.attemptedSessionKeys.toList().takeLast(MAX_KEYS)))
            .put("lockedUnreadSessionKeys", JSONArray(checkpoint.lockedUnreadSessionKeys.toList().takeLast(MAX_KEYS)))
            .put("ambiguousSessionIdentityKeys", JSONArray(checkpoint.ambiguousSessionIdentityKeys.toList().takeLast(MAX_KEYS)))
            .put("lastStableVisibleKeys", JSONArray(checkpoint.lastStableVisibleKeys.takeLast(MAX_KEYS)))
            .put("phase", checkpoint.phase).put("atTop", checkpoint.atTop)
            .put("initialInboxPositionCheckCompleted", checkpoint.initialInboxPositionCheckCompleted)
            .put("initialInboxTopPositioningCompleted", checkpoint.initialInboxTopPositioningCompleted)
            .put("initialUnreadGateSwipeCompleted", checkpoint.initialUnreadGateSwipeCompleted)
            .put("lockedUnreadSetFinalized", checkpoint.lockedUnreadSetFinalized)
            .put("normalInboxHadUnread", checkpoint.normalInboxHadUnread)
            .put("normalInboxUnreadStateUnknown", checkpoint.normalInboxUnreadStateUnknown)
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized override fun clear() { preferences.edit().remove(KEY).apply() }

    private fun JSONObject.readList(key: String): List<String> = readSet(key).toList()
    private fun JSONObject.readSet(key: String): LinkedHashSet<String> {
        val result = LinkedHashSet<String>(); val array = optJSONArray(key) ?: return result
        for (i in 0 until array.length().coerceAtMost(MAX_KEYS)) {
            array.optString(i).takeIf(String::isNotBlank)?.let(result::add)
        }
        return result
    }

    private companion object { const val PREFERENCES = "private_messages_traversal"; const val KEY = "checkpoint_v1"; const val MAX_KEYS = 600; const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L }
}
