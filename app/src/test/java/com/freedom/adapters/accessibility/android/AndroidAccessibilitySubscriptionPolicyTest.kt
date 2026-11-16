package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.runtime.RunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilitySubscriptionPolicyTest {
    @Test
    fun aiReplySubscribesToGlobalTouchWhileServiceGuardsTargetApplicationEvents() {
        val subscription = AndroidAccessibilitySubscriptionPolicy.forRunMode(
            targetPackage = "com.immomo.momo",
            fallbackPackage = "com.freedom",
            runMode = RunMode.AI_REPLY
        )

        assertNull(subscription.packageNames)
        assertEquals(
            AndroidAccessibilitySubscriptionPolicy.targetApplicationEventTypes or
                AndroidAccessibilitySubscriptionPolicy.globalTouchEventTypes,
            subscription.eventTypes
        )
        assertTrue(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            null, AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, "com.immomo.momo", RunMode.AI_REPLY
        ))
        assertFalse(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            "com.other.app", AccessibilityEvent.TYPE_VIEW_SCROLLED, "com.immomo.momo", RunMode.AI_REPLY
        ))
    }

    @Test
    fun idleServiceUsesSelectedPackageAndDoesNotSubscribeToTouchInteraction() {
        val subscription = AndroidAccessibilitySubscriptionPolicy.forRunMode(
            targetPackage = "com.immomo.momo",
            fallbackPackage = "com.freedom",
            runMode = null
        )

        assertEquals(listOf("com.immomo.momo"), subscription.packageNames)
        assertEquals(AndroidAccessibilitySubscriptionPolicy.targetApplicationEventTypes, subscription.eventTypes)
    }

    @Test
    fun accountBrowseDocumentsTheGlobalTouchCompatibilityException() {
        val subscription = AndroidAccessibilitySubscriptionPolicy.forRunMode(
            targetPackage = "com.immomo.momo",
            fallbackPackage = "com.freedom",
            runMode = RunMode.ACCOUNT_BROWSE
        )

        assertNull(subscription.packageNames)
        assertEquals(
            AndroidAccessibilitySubscriptionPolicy.targetApplicationEventTypes or
                AndroidAccessibilitySubscriptionPolicy.globalTouchEventTypes,
            subscription.eventTypes
        )
        assertFalse(subscription.eventTypes and AccessibilityEvent.TYPE_ANNOUNCEMENT != 0)
    }

    @Test
    fun callbackGuardRejectsForeignPackagesBeforeSourceNormalization() {
        assertTrue(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            "com.immomo.momo", AccessibilityEvent.TYPE_VIEW_CLICKED,
            "com.immomo.momo", RunMode.ACCOUNT_BROWSE
        ))
        assertFalse(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            "com.other.app", AccessibilityEvent.TYPE_VIEW_CLICKED,
            "com.immomo.momo", RunMode.ACCOUNT_BROWSE
        ))
    }

    @Test
    fun globalTouchIsAcceptedInAiReplyAndAccountBrowseButNotIdleMode() {
        assertTrue(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            null, AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            "com.immomo.momo", RunMode.ACCOUNT_BROWSE
        ))
        assertTrue(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            null, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            "com.immomo.momo", RunMode.ACCOUNT_BROWSE
        ))
        assertTrue(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            null, AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            "com.immomo.momo", RunMode.AI_REPLY
        ))
        assertFalse(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            null, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            "com.immomo.momo", null
        ))
    }

    @Test
    fun globalTouchIsRejectedWhenNoTargetApplicationIsSelected() {
        assertFalse(AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            null, AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            "", RunMode.ACCOUNT_BROWSE
        ))
    }

    @Test
    fun blankTargetFallsBackToFreedomPackageWithoutOpeningGlobalSubscription() {
        val subscription = AndroidAccessibilitySubscriptionPolicy.forRunMode(
            targetPackage = "",
            fallbackPackage = "com.freedom",
            runMode = RunMode.AI_REPLY
        )
        assertEquals(listOf("com.freedom"), subscription.packageNames)
    }
}
