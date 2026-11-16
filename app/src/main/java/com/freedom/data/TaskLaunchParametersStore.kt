package com.freedom.data

import com.freedom.application.TaskLaunchParameters
import com.freedom.application.ports.TaskLaunchParametersRepository
import android.content.Context

class TaskLaunchParametersStore(context: Context) : TaskLaunchParametersRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun read(): TaskLaunchParameters = TaskLaunchParameters(
        maxDistanceKm = preferences.getInt(KEY_MAX_DISTANCE_KM, 0).coerceAtLeast(0),
        minAge = preferences.getInt(KEY_MIN_AGE, 0).coerceIn(0, 120),
        maxAge = preferences.getInt(KEY_MAX_AGE, 0).coerceIn(0, 120),
        onlineOnly = preferences.getBoolean(KEY_ONLINE_ONLY, false),
        replyLimit = preferences.getInt(KEY_REPLY_LIMIT, 10).coerceAtLeast(0),
        imagePreReplyEnabled = preferences.getBoolean(KEY_IMAGE_PRE_REPLY_ENABLED, false)
    )

    override fun write(parameters: TaskLaunchParameters) {
        preferences.edit()
            .putInt(KEY_MAX_DISTANCE_KM, parameters.maxDistanceKm.coerceAtLeast(0))
            .putInt(KEY_MIN_AGE, parameters.minAge.coerceIn(0, 120))
            .putInt(KEY_MAX_AGE, parameters.maxAge.coerceIn(0, 120))
            .putBoolean(KEY_ONLINE_ONLY, parameters.onlineOnly)
            .putInt(KEY_REPLY_LIMIT, parameters.replyLimit.coerceAtLeast(0))
            .putBoolean(KEY_IMAGE_PRE_REPLY_ENABLED, parameters.imagePreReplyEnabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "freedom_task_launch_parameters"
        const val KEY_MAX_DISTANCE_KM = "max_distance_km"
        const val KEY_MIN_AGE = "min_age"
        const val KEY_MAX_AGE = "max_age"
        const val KEY_ONLINE_ONLY = "online_only"
        const val KEY_REPLY_LIMIT = "reply_limit"
        const val KEY_IMAGE_PRE_REPLY_ENABLED = "image_pre_reply_enabled"
    }
}
