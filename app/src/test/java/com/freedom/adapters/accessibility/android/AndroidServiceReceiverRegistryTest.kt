package com.freedom.adapters.accessibility.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidServiceReceiverRegistryTest {
    @Test
    fun duplicateConnectionRegistrationRegistersEachReceiverOnlyOnce() {
        val registered = mutableListOf<BroadcastReceiver>()
        val unregistered = mutableListOf<BroadcastReceiver>()
        val registry = AndroidServiceReceiverRegistry(
            registerReceiver = { receiver, _ -> registered += receiver },
            unregisterReceiver = { receiver -> unregistered += receiver }
        )
        val receiver = FakeReceiver()

        registry.register(receiver, IntentFilter("first"))
        registry.register(receiver, IntentFilter("second"))

        assertEquals(1, registered.size)
        assertTrue(registry.isRegistered(receiver))
        assertTrue(unregistered.isEmpty())
    }

    @Test
    fun destructionUnregistersEveryReceiverOnceInRegistrationOrder() {
        val registered = mutableListOf<BroadcastReceiver>()
        val unregistered = mutableListOf<BroadcastReceiver>()
        val registry = AndroidServiceReceiverRegistry(
            registerReceiver = { receiver, _ -> registered += receiver },
            unregisterReceiver = { receiver -> unregistered += receiver }
        )
        val first = FakeReceiver()
        val second = FakeReceiver()
        registry.register(first, IntentFilter("first"))
        registry.register(second, IntentFilter("second"))

        registry.unregisterAll()
        registry.unregisterAll()

        assertEquals(listOf(first, second), registered)
        assertEquals(listOf(first, second), unregistered)
        assertFalse(registry.isRegistered(first))
        assertFalse(registry.isRegistered(second))
    }

    @Test
    fun unregisterFailureDoesNotPreventOtherReceiversFromBeingReleased() {
        val attempted = mutableListOf<BroadcastReceiver>()
        val firstReceiver = FakeReceiver()
        val secondReceiver = FakeReceiver()
        val registry = AndroidServiceReceiverRegistry(
            registerReceiver = { _, _ -> Unit },
            unregisterReceiver = { receiver ->
                attempted += receiver
                if (receiver === firstReceiver) error("already unregistered")
            }
        )
        registry.register(firstReceiver, IntentFilter("first"))
        registry.register(secondReceiver, IntentFilter("second"))

        registry.unregisterAll()

        assertEquals(listOf(firstReceiver, secondReceiver), attempted)
        assertFalse(registry.isRegistered(firstReceiver))
        assertFalse(registry.isRegistered(secondReceiver))
    }

    @Test
    fun failedRegistrationIsNotRememberedAndCanBeRetried() {
        var attempts = 0
        val registry = AndroidServiceReceiverRegistry(
            registerReceiver = { _, _ ->
                attempts++
                if (attempts == 1) error("registration rejected")
            },
            unregisterReceiver = { }
        )
        val receiver = FakeReceiver()

        runCatching { registry.register(receiver, IntentFilter("retry")) }
        assertFalse(registry.isRegistered(receiver))
        registry.register(receiver, IntentFilter("retry"))

        assertEquals(2, attempts)
        assertTrue(registry.isRegistered(receiver))
    }

    private class FakeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = Unit
    }
}
