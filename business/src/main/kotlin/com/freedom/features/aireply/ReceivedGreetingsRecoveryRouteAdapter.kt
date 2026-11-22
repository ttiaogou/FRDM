package com.freedom.features.aireply

import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.PageRecoveryRouteAdapter

/** Route adapter for received greetings; page-specific fingerprinting remains in ChatPagePort. */
internal class ReceivedGreetingsRecoveryRouteAdapter(
    private val persist: () -> Unit
) : PageRecoveryRouteAdapter {
    override val routeId: String = "AI_REPLY_RECEIVED_GREETINGS"
    override val taskId: String = ReceivedGreetingsTaskController.TASK_ID

    override fun matches(fingerprint: PageFingerprint): Boolean =
        !fingerprint.adapterFingerprint.isNullOrBlank()

    override fun saveCheckpoint() = persist()
}
