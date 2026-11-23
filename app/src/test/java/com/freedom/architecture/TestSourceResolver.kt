package com.freedom.architecture

import java.io.File

/**
 * Resolves legacy app-source test paths against the real module source roots.
 * Tests keep their package-relative references while production sources remain modularized.
 */
object TestSourceResolver {
    private val moduleSourceRoots = listOf(
        "core/src/main/kotlin/",
        "business/src/main/kotlin/",
        "gesture/gesture-api/src/main/kotlin/",
        "gesture/gesture-core/src/main/kotlin/",
        "gesture/gesture-runtime/src/main/kotlin/",
        "gesture/gesture-android/src/main/kotlin/"
    )
    private const val legacyPrefix = "app/src/main/java/"

    private fun candidates(projectRoot: File, requestedPath: String): List<File> {
        if (!requestedPath.startsWith(legacyPrefix)) return emptyList()
        val relative = requestedPath.removePrefix(legacyPrefix)
        return moduleSourceRoots.map { File(projectRoot, it + relative) }
    }

    fun resolve(projectRoot: File, requestedPath: String): File {
        val moduleFile = candidates(projectRoot, requestedPath).firstOrNull { it.isFile }
        if (moduleFile != null) return moduleFile
        val direct = File(projectRoot, requestedPath)
        return if (direct.exists()) direct else candidates(projectRoot, requestedPath)
            .firstOrNull { it.exists() } ?: direct
    }

    fun resolveAll(projectRoot: File, requestedPath: String): List<File> {
        val direct = File(projectRoot, requestedPath)
        val moduleMatches = candidates(projectRoot, requestedPath).filter { it.exists() }
        val results = if (requestedPath.startsWith(legacyPrefix)) {
            moduleMatches + if (direct.exists()) listOf(direct) else emptyList()
        } else {
            listOf(direct).filter { it.exists() }
        }
        return results.distinctBy { it.absolutePath }.ifEmpty { listOf(direct) }
    }
}
