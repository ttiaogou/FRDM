package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/**
 * 收到招呼任务的适配器执行边界。
 *
 * 所有可能推进页面或列表遍历的适配器调用都在这里统一检查当前任务租约；只读状态查询
 * 保持可用。具体重试节律、状态转换和完成后留在当前页面（A 方案）仍由上层任务协调逻辑负责。
 */
internal class ReceivedGreetingsTaskExecutor(
    private val pagePort: ChatPagePort,
    private val ownsTask: () -> Boolean
) {
    fun supportsWorkflow(): Boolean = pagePort.supportsReceivedGreetingsIdleWorkflow()

    fun observeManuallyOpenedPage(): ReceivedGreetingsPageArrivalStatus =
        runCatching { pagePort.observeManuallyOpenedReceivedGreetingsPage() }
            .getOrDefault(ReceivedGreetingsPageArrivalStatus.UNKNOWN)

    fun observePageReadOnly(): Boolean =
        runCatching { pagePort.observeReceivedGreetingsPage() }.getOrDefault(false)

    fun traversalStatus(): ReceivedGreetingsTraversalStatus =
        runCatching { pagePort.receivedGreetingsTraversalStatus() }
            .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)

    fun currentDisplayName(): String? = runCatching {
        pagePort.receivedGreetingsCurrentDisplayName()
    }.getOrNull()

    fun currentContactIdentity(): ContactRowIdentity? = runCatching {
        pagePort.receivedGreetingsCurrentContactIdentity()
    }.getOrNull()

    /** Resets adapter-owned search state; the caller retains lifecycle and retry decisions. */
    fun beginNavigationTask(): Boolean {
        if (!ownsTask()) return false
        // The previous runtime deliberately ignored adapter reset exceptions and proceeded to the
        // first bounded step. Preserve that behavior while keeping the call behind the lease gate.
        runCatching { pagePort.beginReceivedGreetingsNavigationTask() }
        return true
    }

    /** Executes exactly one adapter-bounded navigation/list step. */
    fun performIdleStep(): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.performReceivedGreetingsIdleStep() }.getOrDefault(false)
    }

    /** Starts a read-only traversal from the already confirmed current list. */
    fun beginFromConfirmedList(): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.beginReceivedGreetingsTaskFromCurrentList() }
            .getOrDefault(false)
    }

    /** Returns from a verified greetings boundary only when this task still owns UI operations. */
    fun returnForPriorityCheck(): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.returnFromReceivedGreetingsForPriorityCheck() }
            .getOrDefault(false)
    }

    /** Completing detail inspection can navigate back to the greetings list, so it is lease-gated. */
    fun completeDetailInspection(success: Boolean): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.completeReceivedGreetingDetailInspection(success) }
            .getOrDefault(false)
    }
}
