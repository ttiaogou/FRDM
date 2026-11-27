package com.freedom.adapters.social.momo

/** Observation-only evidence; never reuse these bounds for a later gesture. */
internal data class MomoImageEntryEvidence(
    val viewId: String, val parentId: String, val className: String, val packageName: String,
    val visible: Boolean, val enabled: Boolean, val clickable: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int
)

internal object MomoImageEntryPolicy {
    const val ENTRY_ID = "com.immomo.momo:id/message_btn_selectpic"
    const val PARENT_ID = "com.immomo.momo:id/function_layout"

    fun resolve(targetPackage: String, candidates: List<MomoImageEntryEvidence>): MomoImageEntryEvidence? =
        resolveOwned(targetPackage, candidates)?.takeIf { it.parentId == PARENT_ID }

    /** The already admitted parent chat supplies context; no parent-layout fingerprint. */
    fun resolveOwned(targetPackage: String, candidates: List<MomoImageEntryEvidence>): MomoImageEntryEvidence? {
        val candidate = candidates.singleOrNull() ?: return null
        return candidate.takeIf {
            targetPackage == "com.immomo.momo" && it.packageName == targetPackage &&
                it.viewId == ENTRY_ID &&
                it.className == "android.widget.ImageView" &&
                it.visible && it.enabled && it.clickable &&
                it.right > it.left && it.bottom > it.top
        }
    }
}
