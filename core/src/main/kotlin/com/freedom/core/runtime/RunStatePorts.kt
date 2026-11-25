package com.freedom.core.runtime

/** Persists only the user's authorization to run, independent of any business page. */
interface RunStateRepository {
    fun isRunRequested(): Boolean
    fun setRunRequested(requested: Boolean)
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
