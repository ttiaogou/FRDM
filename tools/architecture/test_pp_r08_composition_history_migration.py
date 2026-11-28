"""R08-F: exact runtime-local preload migration, no new task or composition authority."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob
from tools.architecture.test_pp_d01d3_legacy_node_cache_retirement import restore_d01d3_source

ROOT = Path(__file__).resolve().parents[2]
COMPOSITION = "app/src/main/java/com/freedom/app/AppCompositionRoot.kt"
RESOLVER = "business/src/main/kotlin/com/freedom/features/conversation/domain/contact/ContactHistoryResolver.kt"
COMPOSITION_PREVIOUS = "357ba685dcd4acde01ac7b41eebac43207a63c2f"
RESOLVER_PREVIOUS = "ebab2e333685c707cf484b8eb911c66877b76712"
COMPOSITION_INVERSE = [
  [
    "            onGreetingDraftConversationObserved = { batch, snapshot, _, visitToken ->\n                // Greeting contacts are new conversations: use the verified current-window OCR\n                // only. Never reuse a private-chat preload or query stored contact history.\n                draftHistoryPreload.clear()\n",
    "            onGreetingDraftConversationObserved = { batch, snapshot, _, visitToken ->\n                // Greeting contacts are new conversations: use the verified current-window OCR\n                // only. Never reuse a private-chat preload or query stored contact history.\n                preparedDraftHistory = null\n"
  ],
  [
    "                val history = draftHistoryPreload.consume(verifiedIdentity, snapshot.chatTitleText)\n",
    "                val history = if (verifiedIdentity != null) {\n                    preparedDraftHistory?.also {\n                        preparedDraftHistory = null\n                        addLog(\"AI 草稿历史读取：复用 OCR 前已完成的历史预加载；不重复查询\")\n                    } ?: runCatching {\n                        com.freedom.features.conversation.domain.contact.ContactHistoryResolver(root.conversationRepository)\n                            .loadDraftHistory(verifiedIdentity, snapshot.chatTitleText)\n                    }.onFailure {\n                        addLog(\n                            \"AI 草稿历史读取失败：CONTACT_HISTORY_READ_FAILED；\" +\n                                \"contactIdVerified=true；exception=${it.javaClass.simpleName}；\" +\n                                \"降级为仅当前窗口上下文\"\n                        )\n                    }.getOrNull()\n                } else {\n                    preparedDraftHistory = null\n                    addLog(\"AI 草稿上下文：身份未达到 PLATFORM_ID_VERIFIED；不加载联系人历史；仅当前窗口上下文\")\n                    null\n                }\n"
  ],
  [
    "                if (verifiedIdentity != null) {\n                    draftHistoryPreload.prepare(verifiedIdentity, snapshot.chatTitleText)\n                }\n",
    "                if (verifiedIdentity != null) {\n                    val history = com.freedom.features.conversation.domain.contact.ContactHistoryResolver(\n                        root.conversationRepository\n                    ).loadDraftHistory(verifiedIdentity, snapshot.chatTitleText)\n                    preparedDraftHistory = history\n                    addLog(\n                        if (history != null) {\n                            \"AI 草稿历史预加载：已验证联系人历史=${history.messages.size}条；OCR 尚未启动\"\n                        } else {\n                            \"AI 草稿历史预加载：未找到可用历史；OCR 尚未启动\"\n                        }\n                    )\n                }\n"
  ],
  [
    "            prepareDraftHistory = { identity, snapshot ->\n                draftHistoryPreload.clear()\n",
    "            prepareDraftHistory = { identity, snapshot ->\n                preparedDraftHistory = null\n"
  ],
  [
    "        val draftHistoryPreload = ContactHistoryResolver.DraftHistoryPreload(\n            loadHistory = { identity, title ->\n                ContactHistoryResolver(root.conversationRepository).loadDraftHistory(identity, title)\n            },\n            addLog = addLog\n        )\n",
    "        // The history preloaded before OCR is consumed by the later draft callback. Keeping this\n        // cache at the single runtime composition boundary avoids reading the same repository\n        // rows again after OCR completes.\n        var preparedDraftHistory: com.freedom.features.aireply.ai.VerifiedDraftHistory? = null\n"
  ],
  [
    "import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence\nimport com.freedom.features.conversation.domain.contact.ContactHistoryResolver\n",
    "import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence\n"
  ]
]
RESOLVER_BLOCK = "\n    /**\n     * One runtime-local, single-use preload slot. The caller retains task-policy admission\n     * and clears before preparing another visit or generating a greeting draft.\n     * No process-wide cache, repository duplication, scheduling, or task authority.\n     */\n    class DraftHistoryPreload(\n        private val loadHistory: (ContactRowIdentity, String?) -> VerifiedDraftHistory?,\n        private val addLog: (String) -> Unit\n    ) {\n        private var preparedDraftHistory: VerifiedDraftHistory? = null\n\n        fun clear() {\n            preparedDraftHistory = null\n        }\n\n        fun prepare(verifiedIdentity: ContactRowIdentity, chatTitle: String?) {\n            val history = loadHistory(verifiedIdentity, chatTitle)\n            preparedDraftHistory = history\n            addLog(\n                if (history != null) {\n                    \"AI 草稿历史预加载：已验证联系人历史=${history.messages.size}条；OCR 尚未启动\"\n                } else {\n                    \"AI 草稿历史预加载：未找到可用历史；OCR 尚未启动\"\n                }\n            )\n        }\n\n        fun consume(verifiedIdentity: ContactRowIdentity?, chatTitle: String?): VerifiedDraftHistory? =\n            if (verifiedIdentity != null) {\n                preparedDraftHistory?.also {\n                    preparedDraftHistory = null\n                    addLog(\"AI 草稿历史读取：复用 OCR 前已完成的历史预加载；不重复查询\")\n                } ?: runCatching {\n                    loadHistory(verifiedIdentity, chatTitle)\n                }.onFailure {\n                    addLog(\n                        \"AI 草稿历史读取失败：CONTACT_HISTORY_READ_FAILED；\" +\n                            \"contactIdVerified=true；exception=${it.javaClass.simpleName}；\" +\n                            \"降级为仅当前窗口上下文\"\n                    )\n                }.getOrNull()\n            } else {\n                preparedDraftHistory = null\n                addLog(\"AI 草稿上下文：身份未达到 PLATFORM_ID_VERIFIED；不加载联系人历史；仅当前窗口上下文\")\n                null\n            }\n    }\n"

# R09-D: preserve the original R08-F inverse after exactly one contact isolation guard.
R09D_CONTACT_GUARD = "                // OCR can be invalidated before consume; never reuse a previous contact's history.\n                if (preparedDraftHistory?.let { it.contactId != verifiedIdentity.contactId } == true) {\n                    clear()\n                    addLog(\"AI 草稿历史读取：预加载联系人已变化；丢弃旧历史并重新查询\")\n                }\n"

def restore_sources(composition, resolver):
    composition = restore_d01d3_source(COMPOSITION, composition)
    if resolver.count(R09D_CONTACT_GUARD) != 1:
        raise AssertionError("R09-D preload contact isolation guard changed")
    resolver = resolver.replace(R09D_CONTACT_GUARD, "", 1)
    if git_blob(resolver.encode("utf-8")) != "235214217df96e84e291d5a58256365551881275":
        raise AssertionError("R09-D pre-isolation resolver not reconstructed")
    for current, previous in COMPOSITION_INVERSE:
        if composition.count(current) != 1:
            raise AssertionError("R08-F composition inverse anchor changed: " + current[:100])
        composition = composition.replace(current, previous, 1)
    if resolver.count(RESOLVER_BLOCK) != 1:
        raise AssertionError("R08-F preload owner migration anchor changed")
    resolver = resolver.replace(RESOLVER_BLOCK, "", 1)
    if git_blob(composition.encode("utf-8")) != COMPOSITION_PREVIOUS:
        raise AssertionError("R08-F composition baseline not reconstructed")
    if git_blob(resolver.encode("utf-8")) != RESOLVER_PREVIOUS:
        raise AssertionError("R08-F resolver baseline not reconstructed")
    return composition, resolver

class PpR08CompositionHistoryMigrationTest(unittest.TestCase):
    def setUp(self):
        self.composition = (ROOT / COMPOSITION).read_text(encoding="utf-8")
        self.resolver = (ROOT / RESOLVER).read_text(encoding="utf-8")

    def test_exact_inverse_and_runtime_local_owner(self):
        restore_sources(self.composition, self.resolver)
        self.assertNotIn("preparedDraftHistory", self.composition)
        self.assertEqual(1, self.resolver.count("private var preparedDraftHistory:"))
        self.assertEqual(1, self.composition.count("ContactHistoryResolver.DraftHistoryPreload("))
        self.assertEqual(2, self.composition.count("draftHistoryPreload.clear()"))
        self.assertIn("draftHistoryPreload.prepare(verifiedIdentity, snapshot.chatTitleText)", self.composition)
        self.assertIn("draftHistoryPreload.consume(verifiedIdentity, snapshot.chatTitleText)", self.composition)
        self.assertIn("history = greetingPolicy.contextPolicy().selectHistory(null)", self.composition)
        self.assertIn("history = privatePolicy.contextPolicy().selectHistory(history)", self.composition)
        self.assertIn("val aiReplyObservationRuntime: AiReplyObservationRuntime by lazy(LazyThreadSafetyMode.NONE)", self.composition)
        for token in ("TaskCoordinator(", "UiOperationOwnershipCoordinator(", "UnifiedRecoveryTransactionManager(",
                      "GestureRuntimeFactory.create(", "MomoSocialAdapter(", "AiReplyObservationRuntime(",
                      "RunSessionCoordinator(", "UnifiedAccessibilityEntry {"):
            self.assertEqual(1, self.composition.count(token), token)
        for token in ("TaskLease", "GestureApi", "ScheduledTask", "companion object", "android."):
            self.assertNotIn(token, RESOLVER_BLOCK)

    def test_mutations_reject_lost_clear_policy_or_instance_boundaries(self):
        for old, new in (
            ("draftHistoryPreload.clear()", "Unit"),
            ("history = greetingPolicy.contextPolicy().selectHistory(null)", "history = history"),
            ("privatePolicy.contextPolicy().verifiedHistoryIdentity(identity)", "identity"),
            ("taskCoordinator = root.taskCoordinator", "taskCoordinator = TaskCoordinator()"),
        ):
            self.assertIn(old, self.composition)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.composition.replace(old, new, 1), self.resolver)
        for old, new in (
            ("preparedDraftHistory?.also {", "null?.also {"),
            ("preparedDraftHistory = null", "Unit"),
            ("loadHistory(verifiedIdentity, chatTitle)", "null"),
            ("}.getOrNull()", "}.getOrThrow()"),
        ):
            self.assertIn(old, self.resolver)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.composition, self.resolver.replace(old, new, 1))
