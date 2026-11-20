package com.freedom.architecture

import java.io.File

/**
 * Resolves legacy app-source test paths against the real module source roots.
 * Tests keep their package-relative references while production sources remain modularized.
 */
object TestSourceResolver {
    fun resolve(projectRoot: File, requestedPath: String): File {
        val direct = File(projectRoot, requestedPath)
        if (direct.exists()) return direct

        val legacyPrefix = "app/src/main/java/"
        if (requestedPath.startsWith(legacyPrefix)) {
            val relative = requestedPath.removePrefix(legacyPrefix)
            val sourceRoots = listOf(
                "core/src/main/kotlin/",
                "business/src/main/kotlin/",
                "gesture/gesture-api/src/main/kotlin/",
                "gesture/gesture-core/src/main/kotlin/",
                "gesture/gesture-runtime/src/main/kotlin/",
                "gesture/gesture-android/src/main/kotlin/"
            )
            sourceRoots
                .asSequence()
                .map { File(projectRoot, it + relative) }
                .firstOrNull { it.exists() }
                ?.let { return it }
        }

        return direct
    }
}
