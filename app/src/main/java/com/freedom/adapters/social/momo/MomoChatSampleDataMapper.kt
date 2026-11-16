package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.SessionTitleProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pure sample-schema mapping for the Momo collection adapter.
 * This class owns no run lifecycle, filesystem access, counters, or Android capture resources.
 */
internal object MomoChatSampleDataMapper {
    fun candidateIdentityJson(candidate: ChatListCandidateSnapshot): JSONObject = JSONObject()
        .put("rowIndex", candidate.rowIndex)
        .put("rowViewId", "com.immomo.momo:id/recyclerview")
        .put("rowNodePath", candidate.rowTarget.path.childIndices.joinToString("/"))
        .put("nameSource", candidate.nameSource.name)
        .put("nameRawLength", candidate.nameText.orEmpty().length)
        .put("nameNodePath", candidate.nameNodePath?.childIndices?.joinToString("/"))
        .put("nameNodeVisibleToUser", candidate.nameNodeVisibleToUser)
        .put("nameBounds", candidate.nameBounds?.let(::rectJson))
        .put("nameBoundsValid", candidate.nameBoundsValid)
        .put("nameBoundsIntersectViewport", candidate.nameBoundsIntersectViewport)
        .put("nameBoundsFullyInsideViewport", candidate.nameBoundsFullyInsideViewport)
        .put("rowBounds", rectJson(candidate.rowBounds))
        .put("rowBoundsValid", candidate.rowBoundsValid)
        .put("rowVisibleToUser", candidate.rowVisibleToUser)

    fun titleProfileJson(profile: SessionTitleProfile): JSONObject {
        val scripts = JSONObject()
        profile.unicodeScriptCounts.forEach { (script, count) -> scripts.put(script, count) }
        return JSONObject()
            .put("rawUtf16Length", profile.rawUtf16Length)
            .put("rawCodePointCount", profile.rawCodePointCount)
            .put("normalizedUtf16Length", profile.normalizedUtf16Length)
            .put("normalizedCodePointCount", profile.normalizedCodePointCount)
            .put("normalizedChanged", profile.normalizedChanged)
            .put("leadingWhitespaceCodePointCount", profile.leadingWhitespaceCodePointCount)
            .put("trailingWhitespaceCodePointCount", profile.trailingWhitespaceCodePointCount)
            .put("whitespaceCodePointCount", profile.whitespaceCodePointCount)
            .put("formatCodePointCount", profile.formatCodePointCount)
            .put("variationSelectorCount", profile.variationSelectorCount)
            .put("letterCodePointCount", profile.letterCodePointCount)
            .put("digitCodePointCount", profile.digitCodePointCount)
            .put("punctuationCodePointCount", profile.punctuationCodePointCount)
            .put("symbolCodePointCount", profile.symbolCodePointCount)
            .put("combiningMarkCodePointCount", profile.combiningMarkCodePointCount)
            .put("controlCodePointCount", profile.controlCodePointCount)
            .put("otherCodePointCount", profile.otherCodePointCount)
            .put("unicodeScriptCounts", scripts)
    }

    fun evidenceJson(evidence: FieldEvidence<*>): JSONObject {
        val value: Any? = when (val item = evidence.value) {
            is Enum<*> -> item.name
            else -> item
        }
        return JSONObject()
            .put("value", value ?: JSONObject.NULL)
            .put("rawText", evidence.rawText ?: JSONObject.NULL)
            .put("status", evidence.status.name)
            .put("source", evidence.source)
    }

    fun sayHiInspectionJson(inspection: MomoSayHiPageInspection): JSONObject {
        val rows = JSONArray().apply {
            inspection.listRows.forEach { row ->
                put(JSONObject()
                    .put("rowPath", JSONArray(row.rowPath))
                    .put("bounds", rectJson(row.bounds))
                    .put("displayName", evidenceJson(row.displayName))
                    .put("age", evidenceJson(row.age))
                    .put("gender", evidenceJson(row.gender))
                    .put("distanceText", evidenceJson(row.distanceText))
                    .put("distanceKm", evidenceJson(row.distanceKm))
                    .put("online", evidenceJson(row.online))
                    .put("greetingText", evidenceJson(row.greetingText))
                    .put("timestampText", evidenceJson(row.timestampText))
                    .put("unread", evidenceJson(row.unread))
                    .put("hasAvatarNode", row.hasAvatarNode)
                    .put("namePath", row.namePath?.childIndices?.let(::JSONArray) ?: JSONObject.NULL)
                    .put("nameBounds", row.nameBounds?.let(::rectJson) ?: JSONObject.NULL)
                    .put("avatarTapPath", row.avatarTapTarget?.path?.childIndices?.let(::JSONArray) ?: JSONObject.NULL)
                    .put("avatarTapBounds", row.avatarTapTarget?.targetBounds?.let(::rectJson) ?: JSONObject.NULL)
                    .put("avatarPreferredBounds", row.avatarTapTarget?.preferredBounds?.let(::rectJson) ?: JSONObject.NULL)
                    .put("rowTapPath", row.rowTapTarget?.path?.childIndices?.let(::JSONArray) ?: JSONObject.NULL)
                    .put("rowTapBounds", row.rowTapTarget?.targetBounds?.let(::rectJson) ?: JSONObject.NULL)
                    .put("rowPreferredBounds", row.rowTapTarget?.preferredBounds?.let(::rectJson) ?: JSONObject.NULL))
            }
        }
        val profile = inspection.profile?.let { value ->
            JSONObject()
                .put("displayName", evidenceJson(value.displayName))
                .put("age", evidenceJson(value.age))
                .put("gender", evidenceJson(value.gender))
                .put("distanceKm", evidenceJson(value.distanceKm))
                .put("online", evidenceJson(value.online))
                .put("biography", evidenceJson(value.biography))
                .put("otherVisibleText", JSONArray(value.otherVisibleText))
                .put("nodeTextFields", JSONObject().apply {
                    value.nodeTextFields.forEach { (viewId, field) ->
                        put(viewId, JSONObject()
                            .put("candidateCount", field.candidateCount)
                            .put("evidence", evidenceJson(field.evidence)))
                    }
                })
        } ?: JSONObject.NULL

        return JSONObject()
            .put("activityClass", inspection.activityClass)
            .put("pageKind", inspection.kind.name)
            .put("nodeCount", inspection.nodeCount)
            .put("visibleNodeCount", inspection.visibleNodeCount)
            .put("containerFound", inspection.containerFound)
            .put("containerNodeCount", inspection.containerNodeCount)
            .put("textNodeCount", inspection.textNodeCount)
            .put("containerMatchCount", inspection.containerMatchCount)
            .put("containerMatchCountExact", inspection.containerMatchCountExact)
            .put("containerAmbiguous", inspection.containerAmbiguous)
            .put("containerResolutionStrategy", inspection.containerResolutionStrategy)
            .put("queryBudgetExceeded", inspection.queryBudgetExceeded)
            .put("queryVisitedNodeCount", inspection.queryVisitedNodeCount)
            .put("queryElapsedMs", inspection.queryElapsedMs)
            .put("queryStopReason", inspection.queryStopReason ?: JSONObject.NULL)
            .put("truncated", inspection.truncated)
            .put("diagnostic", inspection.diagnostic)
            .put("excludedRecommendationCardCount", inspection.excludedRecommendationCardCount)
            .put("listRows", rows)
            .put("profile", profile)
    }

    fun viewportFingerprint(snapshot: ChatPageSnapshot): String {
        val material = buildString {
            append(snapshot.activityClass).append('|')
            snapshot.chatListViewport?.let { append(rectJson(it).toString()) }
            snapshot.chatListCandidates.sortedBy { it.rowIndex }.forEach { candidate ->
                append('|').append(candidate.structuralKey())
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun structuralNodeTreeSignature(snapshot: AccessibilityTreeSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(listOf(
            snapshot.window.packageName, snapshot.window.activityClass,
            snapshot.window.generation.toString(), snapshot.visitedNodeCount.toString(),
            snapshot.requestedMaxNodes.toString(), snapshot.requestedMaxDepth.toString(),
            snapshot.walkTruncated.toString(), snapshot.depthTruncated.toString()
        ).joinToString("|").toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        snapshot.nodes.forEach { node ->
            val collection = node.collection?.let {
                "${it.rowCount},${it.columnCount},${it.hierarchical}"
            }.orEmpty()
            val item = node.collectionItem?.let {
                "${it.rowIndex},${it.rowSpan},${it.columnIndex},${it.columnSpan},${it.heading},${it.selected}"
            }.orEmpty()
            val actionIds = node.actions.joinToString(",") { it.id.toString() }
            val part = listOf(
                node.path.childIndices.joinToString("/"), node.indexInParent.toString(),
                node.className, node.viewId, node.packageName, node.windowId.toString(),
                node.bounds.left.toString(), node.bounds.top.toString(),
                node.bounds.right.toString(), node.bounds.bottom.toString(),
                node.visible.toString(), node.enabled.toString(), node.clickable.toString(),
                node.longClickable.toString(), node.scrollable.toString(), node.editable.toString(),
                node.checkable.toString(), node.checked.toString(), node.selected.toString(),
                node.focused.toString(), node.focusable.toString(), node.contextClickable.toString(),
                node.password.toString(), node.drawingOrder.toString(), node.childCount.toString(),
                collection, item, actionIds
            ).joinToString("|")
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun rectJson(rect: ScreenRect): JSONObject = JSONObject()
        .put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)

    fun floatMapJson(values: Map<Int, Float>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key.toString(), value.toDouble()) }
    }

    fun intMapJson(values: Map<Int, Int>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key.toString(), value) }
    }

    fun stringMapJson(values: Map<Int, String>?): JSONObject {
        val result = JSONObject()
        values.orEmpty().forEach { (index, value) -> result.put(index.toString(), value) }
        return result
    }
}
