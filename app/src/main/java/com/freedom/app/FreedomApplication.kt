package com.freedom.app

import android.app.Application

/** Creates the process-wide dependency composition root before any Activity, Service or Receiver. */
class FreedomApplication : Application() {
    lateinit var compositionRoot: AppCompositionRoot
        private set

    override fun onCreate() {
        super.onCreate()
        compositionRoot = AppCompositionRoot(this)
    }

    private fun requireCompositionRoot(): AppCompositionRoot {
        check(::compositionRoot.isInitialized) { "AppCompositionRoot is not initialized" }
        return compositionRoot
    }

    companion object {
        fun root(context: android.content.Context): AppCompositionRoot {
            val app = context.applicationContext as? FreedomApplication
                ?: error("FreedomApplication is not configured in AndroidManifest.xml")
            return app.requireCompositionRoot()
        }
    }
}
