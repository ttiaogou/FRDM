package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot

import com.freedom.core.accessibility.api.WindowRef

/**
 * Canonical identity key for a verified Momo page in one current Android window generation.
 * Activity names are intentionally omitted: they are diagnostics/capture hints, not page identity.
 * Structural signatures are supplied by the page-specific inspectors and exclude volatile content.
 */
internal object MomoWindowPageFingerprint {
    fun sameMomoWindowGeneration(left: WindowRef, right: WindowRef): Boolean =
        left.packageName == right.packageName &&
            left.windowId == right.windowId &&
            left.generation == right.generation

    fun sayHiWindowStabilityKey(tree: AccessibilityTreeSnapshot): String =
        sayHiWindowStabilityKey(tree.window.copy(windowId = tree.windowId))

    fun sayHiWindowStabilityKey(window: WindowRef): String = listOf(
        window.packageName,
        window.windowId,
        window.generation
    ).joinToString("|")

    fun create(
        window: WindowRef,
        targetPackage: String,
        pageKind: String,
        structuralSignature: String
    ): String? {
        if (targetPackage.isBlank() || window.packageName != targetPackage || window.windowId < 0 ||
            pageKind.isBlank() || structuralSignature.isBlank()
        ) return null
        return listOf(
            targetPackage,
            "windowId=${window.windowId}",
            "generation=${window.generation}",
            "page=$pageKind",
            structuralSignature
        ).joinToString("|")
    }

    fun windowIdentity(window: WindowRef, targetPackage: String, pageKind: String): String? =
        create(window, targetPackage, pageKind, "structure-confirmed-v1")
}
