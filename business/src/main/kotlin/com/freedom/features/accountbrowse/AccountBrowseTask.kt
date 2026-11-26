package com.freedom.features.accountbrowse

/**
 * Platform-neutral lifecycle boundary for the automatic-browse business task.
 *
 * This class owns task activation only. Phase transitions, recovery verification and action
 * selection remain owned by [AccountBrowseRuntime]; Android adapters provide the actual actions.
 */
class AccountBrowseTask(
    private val runtime: AccountBrowseRuntime
) {
    enum class Status {
        IDLE,
        RUNNING
    }

    var status: Status = Status.IDLE
        private set

    fun start(): Boolean {
        if (status == Status.RUNNING) return false
        status = Status.RUNNING
        return true
    }

    fun performStep() {
        if (status == Status.RUNNING) runtime.performStep()
    }

    fun suspendForPageExit() {
        runtime.suspendSession()
        status = Status.IDLE
    }

    fun stop(
        beforeReset: () -> Unit,
        afterReset: () -> Unit
    ) {
        runtime.resetSession(beforeReset, afterReset)
        status = Status.IDLE
    }
}
