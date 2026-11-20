package com.freedom.core.runtime

/**
 * Port implemented by the data layer using the existing MonitorStore keys and semantics.
 * A null active mode is an intentional manual-observation standby, not a missing selection error.
 */
interface RunStateRepository {
    fun isRunRequested(): Boolean
    fun activeMode(): RunMode?
    /** Null means an intentional manual-observation session with no AI/Browse runtime selected. */
    fun setActiveMode(mode: RunMode?)
    fun setRunRequested(requested: Boolean)

}

/** Reads the two existing UI selections without allowing a running session to change mode. */
fun interface RunModeSelectionSource {
    fun selectedMode(): RunMode?
}

/** Structured logging boundary; implementations decide storage and redaction. */
fun interface RuntimeLogSink {
    fun append(message: String)

    /** Preserve a throwable and bounded stack detail when an adapter catches an exception. */
    fun recordException(component: String, eventType: String, message: String, error: Throwable) {
        append("$component：$message；异常=${error.javaClass.simpleName}：${error.message.orEmpty()}")
    }

    /** Called after the owning runtime has completed its stop/reset cleanup. */
    fun finishRun(result: String, reason: String) = Unit
}
