package com.freedom.application.ports

import com.freedom.application.TaskLaunchParameters

/** Persistence boundary for the launch form; the model remains owned by Application. */
interface TaskLaunchParametersRepository {
    fun read(): TaskLaunchParameters
    fun write(parameters: TaskLaunchParameters)

    /** Separate durable consumption: unrelated form edits must not rearm the same request. */
    fun isImageSendTestRequestAvailable(requestId: String): Boolean = false
    fun claimImageSendTestRequest(requestId: String): Boolean = false
}
