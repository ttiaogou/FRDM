package com.freedom.adapters.social.momo

/** One independent page-identification route. Conditions inside a route are ANDed. */
internal data class MomoFingerprintPath(
    val name: String,
    val conditions: List<Boolean>
) {
    val matched: Boolean
        get() = name.isNotBlank() && conditions.isNotEmpty() && conditions.all { it }
}

/**
 * Evaluates independent identification routes for one page kind only.
 * Paths are ORed; conditions within each path are ANDed. Callers must never mix paths
 * belonging to different business pages in the same evaluation.
 */
internal object MomoAlternativeFingerprintPolicy {
    fun firstMatch(paths: List<MomoFingerprintPath>): String? =
        paths.firstOrNull { it.matched }?.name

    fun matchesAny(paths: List<MomoFingerprintPath>): Boolean = firstMatch(paths) != null
}
