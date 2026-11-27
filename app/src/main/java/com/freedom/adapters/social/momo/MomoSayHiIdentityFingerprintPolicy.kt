package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Content-derived SayHi identities; platform IDs and raw text are never emitted to logs.
 * 使用与原逻辑一致的输入字段生成指纹；原始文本和平台 ID 不写入常规日志。
 */
internal object MomoSayHiIdentityFingerprintPolicy {
    fun normalizeIdentityDisplayName(value: String?): String =
        java.text.Normalizer.normalize(value.orEmpty().trim(), java.text.Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ")

    /** ChatActivity exposes the peer title as 与+昵称+对话; compare the extracted nickname. */
    fun extractChatPeerName(value: String?): String {
        val title = normalizeIdentityDisplayName(value)
        if (!title.startsWith("与") || !title.endsWith("对话")) return ""
        return normalizeIdentityDisplayName(title.removePrefix("与").removeSuffix("对话"))
    }

    fun sha256IdentityAnchor(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * A diagnostic hash of the CURRENT row's safe geometry and existing structural
     * fingerprint. No unredacted name, chat content, node handle, or click coordinate
     * survives beyond the current evidence check.
     */
    fun sayHiSafeTargetEvidenceHash(
        tree: AccessibilityTreeSnapshot, row: MomoSayHiListRow, target: MomoSayHiTapTarget
    ): String {
        val evidence = listOf(
            MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(tree),
            row.bounds.toString(), row.rowPath.joinToString("/"),
            target.path.childIndices.joinToString("/"),
            target.targetBounds.toString(), target.preferredBounds.toString(),
            target.excludedBounds.joinToString("|") { it.toString() }
        ).joinToString("\u0000")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(evidence.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun contactIdHash(contactId: String): String =
        sha256(contactId.trim())

    fun treeStructureFingerprint(tree: AccessibilityTreeSnapshot): String {
        val source = tree.nodes.joinToString("\u0000") { node ->
            listOf(
                node.path.childIndices.joinToString("/"), node.viewId, node.className,
                node.bounds.left.toString(), node.bounds.top.toString(),
                node.bounds.right.toString(), node.bounds.bottom.toString(),
                node.childCount.toString(), node.visible.toString(), node.clickable.toString(),
                node.text.orEmpty(), node.contentDescription.orEmpty()
            ).joinToString("|")
        }
        return sha256(source)
    }

    fun rowKey(row: MomoSayHiListRow): String {
        val source = listOf(
            row.displayName.value.orEmpty(), row.greetingText.value.orEmpty(),
            row.timestampText.value.orEmpty(), row.distanceText.value.orEmpty(),
            row.age.value?.toString().orEmpty(), row.gender.value?.name.orEmpty()
        ).joinToString("\u0000")
        return sha256(source)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
