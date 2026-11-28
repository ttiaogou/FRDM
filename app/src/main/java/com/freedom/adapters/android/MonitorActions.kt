package com.freedom.adapters.android

object MonitorActions {
    // Explicit external refresh: reconcile the run request and notify presentation.
    const val ACTION_REFRESH = "com.freedom.ACTION_REFRESH"
    // Logs and presentation updates must never wake a business runtime.
    const val ACTION_PRESENTATION_REFRESH = "com.freedom.ACTION_PRESENTATION_REFRESH"
    const val ACTION_BOOT_RECOVERY = "com.freedom.ACTION_BOOT_RECOVERY"
    const val ACTION_TARGET_CHANGED = "com.freedom.ACTION_TARGET_CHANGED"
    const val ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS = "com.freedom.ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS"
    const val ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS = "com.freedom.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS"
    const val ACTION_SHOW_GLOBAL_NODE_HIERARCHY = "com.freedom.ACTION_SHOW_GLOBAL_NODE_HIERARCHY"
    const val ACTION_OPEN_CAPTURE_EXPORT = "com.freedom.ACTION_OPEN_CAPTURE_EXPORT"
    const val ACTION_RETURN_TO_CONFIGURATION = "com.freedom.ACTION_RETURN_TO_CONFIGURATION"
}
