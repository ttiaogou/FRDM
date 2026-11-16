package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot

internal enum class MomoSayHiPlatformIdentityStatus {
    CONFIRMED,
    MISSING,
    AMBIGUOUS
}

internal data class MomoSayHiPlatformIdentityReadout(
    val value: String?,
    val status: MomoSayHiPlatformIdentityStatus,
    val candidateCount: Int,
    val source: String
)

/**
 * Reads only explicitly-labelled Momo/user identifiers from a profile reached by a verified
 * avatar tap. Display names, numeric ages and arbitrary digits are never promoted to identity.
 */
internal object MomoSayHiPlatformIdentityParser {
    private val labelledId = Regex(
        "(?:陌陌号|陌陌ID|用户ID|用户号|MOMO\\s*ID|ACCOUNT\\s*ID|USER\\s*ID)\\s*[:：#]?\\s*([A-Za-z0-9_-]{4,32})",
        RegexOption.IGNORE_CASE
    )
    private val explicitIdViewIds = setOf(
        "user_id", "tv_user_id", "momo_id", "tv_momo_id", "profile_user_id", "tv_uid", "uid"
    )
    private val exactIdLabels = setOf("陌陌号", "陌陌ID", "用户ID", "用户号", "MOMO ID", "USER ID", "ACCOUNT ID")
    private val valueOnlyId = Regex("^[A-Za-z0-9_-]{4,32}$")
    private val numericProfileId = Regex("^\\d{6,12}$")

    fun parse(nodes: List<AccessibilityNodeSnapshot>): MomoSayHiPlatformIdentityReadout {
        val candidates = linkedSetOf<String>()
        var evidenceSources = 0
        val visible = nodes.filter { it.visible }
        val byParent = visible.groupBy { it.path.childIndices.dropLast(1) }
        val byPath = visible.associateBy { it.path.childIndices }
        visible.forEach { node ->
            val values = sequenceOf(node.text, node.contentDescription)
                .filterNotNull().map(String::trim).filter(String::isNotBlank).toList()
            values.forEach { value ->
                labelledId.findAll(value).forEach { match ->
                    candidates += match.groupValues[1]
                    evidenceSources++
                }
                if (node.viewId.substringAfterLast('/') in explicitIdViewIds &&
                    valueOnlyId.matches(value)
                ) {
                    candidates += value
                    evidenceSources++
                }
            }
            // Some Momo profile layouts expose the field label and value as adjacent sibling
            // TextViews. Accept the value only when its immediate preceding sibling is this
            // explicit identity label; never infer identity from an arbitrary nearby number.
            val labelValues = values.map {
                it.uppercase().replace(Regex("\\s+"), " ").trimEnd(':', '：', '#', ' ')
            }
            if (labelValues.any { it in exactIdLabels }) {
                val siblings = byParent[node.path.childIndices.dropLast(1)].orEmpty()
                val currentIndex = siblings.indexOfFirst { it.path == node.path }
                if (currentIndex >= 0) {
                    siblings.drop(currentIndex + 1).take(2).forEach { sibling ->
                        val siblingValues = sequenceOf(sibling.text, sibling.contentDescription)
                            .filterNotNull().map(String::trim).filter(String::isNotBlank)
                        siblingValues.filter(valueOnlyId::matches).forEach { candidate ->
                            candidates += candidate
                            evidenceSources++
                        }
                    }
                }
            }

            // The current Momo profile layout exposes the platform ID in a TextView[id=name]
            // beside ImageView[id=icon]. This weak-looking resource ID is accepted only when
            // its direct parent is the observed clickable+long-clickable LinearLayout shown by
            // the inspector. Numeric values elsewhere on the page remain ordinary profile data.
            if (localId(node.viewId) == "name" &&
                node.className.substringAfterLast('.') == "TextView" &&
                values.any(numericProfileId::matches)
            ) {
                val parentPath = node.path.childIndices.dropLast(1)
                val parent = byPath[parentPath]
                val hasIconSibling = byParent[parentPath].orEmpty().any { sibling ->
                    sibling.path != node.path &&
                        localId(sibling.viewId) == "icon" &&
                        sibling.className.substringAfterLast('.') == "ImageView" &&
                        isDirectChild(sibling.path.childIndices, parentPath)
                }
                if (parent != null &&
                    parent.className.substringAfterLast('.') == "LinearLayout" &&
                    parent.clickable && parent.longClickable &&
                    parent.childCount >= 2 && hasIconSibling &&
                    node.path.childIndices.size == parentPath.size + 1
                ) {
                    values.filter(numericProfileId::matches).forEach { candidate ->
                        candidates += candidate
                        evidenceSources++
                    }
                }
            }
        }
        return when (candidates.size) {
            0 -> MomoSayHiPlatformIdentityReadout(null, MomoSayHiPlatformIdentityStatus.MISSING, 0, "EXPLICIT_ID_LABEL_NOT_FOUND")
            1 -> MomoSayHiPlatformIdentityReadout(candidates.single(), MomoSayHiPlatformIdentityStatus.CONFIRMED, 1, "MOMO_PROFILE_ID_EVIDENCE:$evidenceSources")
            else -> MomoSayHiPlatformIdentityReadout(null, MomoSayHiPlatformIdentityStatus.AMBIGUOUS, candidates.size, "CONFLICTING_EXPLICIT_IDS")
        }
    }

    private fun localId(viewId: String): String = viewId.substringAfterLast('/')

    private fun isDirectChild(path: List<Int>, parentPath: List<Int>): Boolean =
        path.size == parentPath.size + 1 && path.take(parentPath.size) == parentPath
}
