package com.freedom.adapters.accessibility.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Owns the lifetime of Freedom's app-overlay windows independently of MainActivity and of any
 * monitoring run. START_STICKY lets Android recreate the host after reclaiming its process; the
 * persisted floating-button preference is reconciled again from onCreate/onStartCommand.
 */
class FloatingWindowHostService : Service() {

    override fun onCreate() {
        super.onCreate()
        running = true
        Log.i(TAG, "Floating-window host service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: also repairs the window pair when the service already existed but a window
        // was detached before a new ensure request arrived.
        if (!MonitorOverlay.onHostServiceReady(applicationContext)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        MonitorOverlay.onHostServiceDestroyed()
        Log.i(TAG, "Floating-window host service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingWindowHost"

        @Volatile
        private var running = false

        fun ensureStarted(context: android.content.Context): Boolean {
            if (running) return true
            return runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, FloatingWindowHostService::class.java)
                )
                true
            }.getOrElse { error ->
                Log.e(TAG, "Unable to start floating-window host service", error)
                false
            }
        }

        fun stop(context: android.content.Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, FloatingWindowHostService::class.java)
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to stop floating-window host service", error)
            }
        }
    }
}
