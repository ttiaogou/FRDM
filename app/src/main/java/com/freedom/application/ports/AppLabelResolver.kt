package com.freedom.application.ports

/** Resolves a platform package identifier to a user-facing application label. */
fun interface AppLabelResolver {
    fun resolve(packageName: String): String
}
