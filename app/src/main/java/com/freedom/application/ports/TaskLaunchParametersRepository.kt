package com.freedom.application.ports

import com.freedom.application.TaskLaunchParameters

/** Persistence boundary for the launch form; the model remains owned by Application. */
interface TaskLaunchParametersRepository {
    fun read(): TaskLaunchParameters
    fun write(parameters: TaskLaunchParameters)

}
