package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.runtime.RunMode

/** Named boundary between the Android event entry adapter and event normalization/routing. */
internal fun interface AccessibilityEventPipelinePort {
    fun process(event: AccessibilityEvent, runMode: RunMode)
}
