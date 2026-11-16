package com.freedom.adapters.android

import android.content.Context
import com.freedom.application.ports.AppLabelResolver

/** Android PackageManager adapter for application label resolution. */
internal class AndroidAppLabelResolver(context: Context) : AppLabelResolver {
    private val packageManager = context.applicationContext.packageManager

    override fun resolve(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
