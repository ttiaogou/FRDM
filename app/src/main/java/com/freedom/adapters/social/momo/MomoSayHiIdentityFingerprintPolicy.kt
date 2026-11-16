package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Content-derived SayHi identities; platform IDs and raw text are never emitted to logs.
 * 使用与原逻辑一致的输入字段生成指纹；原始文本和平台 ID 不写入常规日志。
 */
internal object MomoSayHiIdentityFingerprintPolicy {
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
