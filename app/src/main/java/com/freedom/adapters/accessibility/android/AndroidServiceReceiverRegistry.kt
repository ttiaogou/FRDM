package com.freedom.adapters.accessibility.android

import android.content.BroadcastReceiver
import android.content.IntentFilter

/**
 * Idempotent receiver registration for one Android Service instance.
 * Android API-level registration details are supplied by the Service lifecycle owner.
 */
internal class AndroidServiceReceiverRegistry(
    private val registerReceiver: (BroadcastReceiver, IntentFilter) -> Unit,
    private val unregisterReceiver: (BroadcastReceiver) -> Unit
) {
    private val registeredReceivers = mutableListOf<BroadcastReceiver>()

    fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (registeredReceivers.any { it === receiver }) return
        registerReceiver(receiver, filter)
        registeredReceivers += receiver
    }

    /** Unregisters in registration order and isolates failures so every receiver is attempted. */
    fun unregisterAll() {
        val receivers = registeredReceivers.toList()
        registeredReceivers.clear()
        receivers.forEach { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
    }

    internal fun isRegistered(receiver: BroadcastReceiver): Boolean =
        registeredReceivers.any { it === receiver }
}
