"""R08-B: move session evidence decisions into the existing Feature owner, preserving platform gates."""
import re
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"
RUNTIME = "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt"
BOUNDARY_TEST = "app/src/test/java/com/freedom/architecture/AccountBrowseManualHandoffBoundaryTest.kt"
PREVIOUS = {
  "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt": "2c35e3d96bbb595e0bf08d3c1c1257f9ef7b0da6",
  "business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt": "d0fa5ac24dcd1d06981853c058b3b7bd3500d8de",
  "app/src/test/java/com/freedom/architecture/AccountBrowseManualHandoffBoundaryTest.kt": "3ae02f95b291a0bf127794d4ffdb1f9fd9d57f84"
}
WORKFLOW_INVERSE = [
  [
    "    private fun interactionContext(): AccountBrowseInteractionContext = accountBrowseRuntime.interactionContext(\n        isMainActivity = socialBrowsePort::isMainActivity,\n        isDetailActivity = socialBrowsePort::isDetailActivity\n    )\n",
    "    private fun interactionContext(): AccountBrowseInteractionContext = AccountBrowseInteractionContext(\n        phase = accountBrowsePhase,\n        isMainActivity = socialBrowsePort.isMainActivity(accountBrowseSession.currentActivityClass),\n        isDetailActivity = socialBrowsePort.isDetailActivity(accountBrowseSession.currentActivityClass),\n        touchActive = accountBrowseSession.touchActive,\n        lastGlobalTouchEventAt = accountBrowseSession.lastGlobalTouchEventAt,\n        lastGlobalTouchEventName = accountBrowseSession.lastGlobalTouchEventName,\n        lastGlobalTouchEventOrigin = accountBrowseSession.lastGlobalTouchEventOrigin,\n        gestureInFlight = accountBrowseSession.gestureInFlight,\n        ignoreManualEventsUntil = accountBrowseSession.ignoreManualEventsUntil,\n        programmaticActionLabel = accountBrowseSession.programmaticActionLabel,\n        dynamicTabWaitStartedAt = accountBrowseSession.dynamicTabWaitStartedAt,\n        feedRefreshRecoveryActive = accountBrowseSession.feedRefreshRecoveryActive,\n        detailDwellStartedAt = accountBrowseSession.detailDwellStartedAt,\n        feedRefreshActionAt = accountBrowseSession.feedRefreshActionAt\n    )\n",
    1
  ],
  [
    "        val decision = accountBrowseRuntime.observeFeedReturn(\n            currentSnapshot, isFeedList, positionMatches, REQUIRED_STABLE_PAGE_SAMPLES\n        )\n",
    "        val stability = FeedReturnStability.update(\n            previousSnapshot = accountBrowseSession.lastReturnedSnapshot,\n            previousSamples = accountBrowseSession.returnedStableSamples,\n            currentSnapshot = currentSnapshot,\n            isFeedPage = isFeedList,\n            positionMatches = positionMatches,\n            requiredSamples = REQUIRED_STABLE_PAGE_SAMPLES\n        )\n        accountBrowseSession.lastReturnedSnapshot = stability.lastSnapshot\n        accountBrowseSession.returnedStableSamples = stability.stableSamples\n        val decision = FeedReturnVerificationPolicy.decide(\n            rootAvailable = true,\n            isFeedPage = isFeedList,\n            positionMatches = positionMatches,\n            positionStable = stability.isStable,\n            deadlineReached = System.currentTimeMillis() >= accountBrowseSession.deadlineAt\n        )\n",
    1
  ],
  [
    "    private fun initializeImagePaging(page: BrowsePageEvidence) {\n        accountBrowseRuntime.initializeImagePaging(page, ::readImagePosition, NAVIGATION_ACTION_VERIFY_TIMEOUT_MS, ::addLog)\n    }\n",
    "    private fun initializeImagePaging(page: BrowsePageEvidence) {\n        accountBrowseSession.imagePagingProgress = null\n        if (page.pageKind != BrowsePageKind.IMAGE_VIEWER) return\n        val evidence = readImagePosition(page) as? ImageIndexReadResult.Confirmed\n        val plan = evidence?.let { ImageBrowsePlanningPolicy.plan(it.position) }\n        accountBrowseSession.imagePagingProgress = ImagePagingProgress(\n            fingerprint = page.fingerprint, plan = plan,\n            deadlineAt = accountBrowseSession.detailDwellDueAt + NAVIGATION_ACTION_VERIFY_TIMEOUT_MS,\n            finished = evidence?.position?.total == 1\n        )\n        addLog(\"图片翻阅计划[INIT]：direction=${plan?.direction}；count=${plan?.swipeCount}；保留原详情停留\")\n    }\n",
    1
  ],
  [
    "import com.freedom.features.accountbrowse.FeedReturnVerificationPolicy\n",
    "import com.freedom.features.accountbrowse.FeedReturnStability\nimport com.freedom.features.accountbrowse.FeedReturnVerificationPolicy\n",
    1
  ]
]
TEST_INVERSE = [
  [
    "        assertTrue(verify.contains(\"accountBrowseRuntime.observeFeedReturn(\"))\n        val runtime = source(\"business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt\")\n        val returnOwner = runtime.substringAfter(\"fun observeFeedReturn(\").substringBefore(\"fun interactionContext(\")\n        assertTrue(returnOwner.contains(\"FeedReturnStability.update(\"))\n        assertTrue(returnOwner.contains(\"session.lastReturnedSnapshot = stability.lastSnapshot\"))\n        assertTrue(returnOwner.contains(\"session.returnedStableSamples = stability.stableSamples\"))\n        assertTrue(returnOwner.contains(\"return FeedReturnVerificationPolicy.decide(\"))\n",
    "        assertTrue(verify.contains(\"FeedReturnStability.update(\"))\n",
    1
  ],
  [
    "        val initializeDelegate = workflow.substringAfter(initStart).substringBefore(advanceStart)\n        val runtime = source(\"business/src/main/kotlin/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt\")\n        val initialize = runtime.substringAfter(\"fun initializeImagePaging(\").substringBefore(\"fun observeFeedReturn(\")\n        assertTrue(initializeDelegate.contains(\"accountBrowseRuntime.initializeImagePaging(page, ::readImagePosition, NAVIGATION_ACTION_VERIFY_TIMEOUT_MS, ::addLog)\"))\n        assertFalse(initializeDelegate.contains(\"gesturePolicy.execute(\"))\n        assertFalse(initializeDelegate.contains(\"performGlobalAction(\"))\n        assertFalse(initializeDelegate.contains(\"scheduleAccountBrowseStep(\"))\n        assertFalse(initializeDelegate.contains(\"pauseAccountBrowse(\"))\n        assertFalse(initializeDelegate.contains(\"accountBrowsePhase =\"))\n",
    "        val initialize = workflow.substringAfter(initStart).substringBefore(advanceStart)\n",
    2
  ]
]
RUNTIME_BLOCK = "    /** Read-only image evidence initializes the Feature-owned visit cursor; no gesture admission. */\n    fun initializeImagePaging(\n        page: BrowsePageEvidence,\n        readImagePosition: (BrowsePageEvidence) -> ImageIndexReadResult,\n        navigationActionVerifyTimeoutMs: Long,\n        addLog: (String) -> Unit\n    ) {\n        session.imagePagingProgress = null\n        if (page.pageKind != BrowsePageKind.IMAGE_VIEWER) return\n        val evidence = readImagePosition(page) as? ImageIndexReadResult.Confirmed\n        val plan = evidence?.let { ImageBrowsePlanningPolicy.plan(it.position) }\n        session.imagePagingProgress = ImagePagingProgress(\n            fingerprint = page.fingerprint, plan = plan,\n            deadlineAt = session.detailDwellDueAt + navigationActionVerifyTimeoutMs,\n            finished = evidence?.position?.total == 1\n        )\n        addLog(\"图片翻阅计划[INIT]：direction=${plan?.direction}；count=${plan?.swipeCount}；保留原详情停留\")\n    }\n\n    /** The Feature owner updates return samples before applying the unchanged verification policy. */\n    fun observeFeedReturn(\n        currentSnapshot: String,\n        isFeedList: Boolean,\n        positionMatches: Boolean,\n        requiredSamples: Int\n    ): FeedReturnVerificationPolicy.Decision {\n        val stability = FeedReturnStability.update(\n            previousSnapshot = session.lastReturnedSnapshot,\n            previousSamples = session.returnedStableSamples,\n            currentSnapshot = currentSnapshot,\n            isFeedPage = isFeedList,\n            positionMatches = positionMatches,\n            requiredSamples = requiredSamples\n        )\n        session.lastReturnedSnapshot = stability.lastSnapshot\n        session.returnedStableSamples = stability.stableSamples\n        return FeedReturnVerificationPolicy.decide(\n            rootAvailable = true,\n            isFeedPage = isFeedList,\n            positionMatches = positionMatches,\n            positionStable = stability.isStable,\n            deadlineReached = System.currentTimeMillis() >= session.deadlineAt\n        )\n    }\n\n    /** Project the existing session facts for the interaction classifiers. */\n    fun interactionContext(\n        isMainActivity: (String) -> Boolean,\n        isDetailActivity: (String) -> Boolean\n    ): AccountBrowseInteractionContext = AccountBrowseInteractionContext(\n        phase = phase,\n        isMainActivity = isMainActivity(session.currentActivityClass),\n        isDetailActivity = isDetailActivity(session.currentActivityClass),\n        touchActive = session.touchActive,\n        lastGlobalTouchEventAt = session.lastGlobalTouchEventAt,\n        lastGlobalTouchEventName = session.lastGlobalTouchEventName,\n        lastGlobalTouchEventOrigin = session.lastGlobalTouchEventOrigin,\n        gestureInFlight = session.gestureInFlight,\n        ignoreManualEventsUntil = session.ignoreManualEventsUntil,\n        programmaticActionLabel = session.programmaticActionLabel,\n        dynamicTabWaitStartedAt = session.dynamicTabWaitStartedAt,\n        feedRefreshRecoveryActive = session.feedRefreshRecoveryActive,\n        detailDwellStartedAt = session.detailDwellStartedAt,\n        feedRefreshActionAt = session.feedRefreshActionAt\n    )\n\n"
RUNTIME_IMPORT = "import com.freedom.features.accountbrowse.ports.ImageIndexReadResult\n"

def restore_sources(workflow, runtime, boundary_test):
    def inverse(source, edits):
        for current, previous, count in edits:
            if source.count(current) != count:
                raise AssertionError("R08-B inverse anchor changed: " + current[:100])
            source = source.replace(current, previous)
        return source
    restored = {
        WORKFLOW: inverse(workflow, WORKFLOW_INVERSE),
        RUNTIME: inverse(runtime, [(RUNTIME_BLOCK, "", 1), (RUNTIME_IMPORT, "", 1)]),
        BOUNDARY_TEST: inverse(boundary_test, TEST_INVERSE),
    }
    for path, source in restored.items():
        if git_blob(source.encode("utf-8")) != PREVIOUS[path]:
            raise AssertionError("R08-B prior source not reconstructed: " + path)
    return restored

class PpR08BrowseRuntimeMigrationTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / WORKFLOW).read_text(encoding="utf-8")
        self.runtime = (ROOT / RUNTIME).read_text(encoding="utf-8")
        self.boundary = (ROOT / BOUNDARY_TEST).read_text(encoding="utf-8")

    def test_exact_inverse_and_real_state_owner(self):
        restore_sources(self.workflow, self.runtime, self.boundary)
        self.assertIn("private val sessionStateOwner = AccountBrowseSessionStateOwner()", self.runtime)
        self.assertIn("val session: AccountBrowseSessionStatePort = sessionStateOwner.port", self.runtime)
        for token in ("AccountBrowseSessionStateOwner(", "AccountBrowseSessionSnapshot("):
            self.assertNotIn(token, self.workflow)
        for field in ("gestureDispatchSyncTask", "overlayPassthroughWatchdogTask",
                      "browseCallbackEpoch", "activeBrowseGestureRequestId"):
            declaration = r"\bvar\s+" + field + r"\b"
            self.assertEqual(1, len(re.findall(declaration, self.workflow)))
            self.assertNotRegex(self.runtime, declaration)
        for method in ("initializeImagePaging", "observeFeedReturn", "interactionContext"):
            self.assertEqual(1, self.runtime.count("fun " + method + "("))
            self.assertIn("accountBrowseRuntime." + method + "(", self.workflow)
        for forbidden in ("gesturePolicy", "performGlobalAction", "scheduleAccountBrowseStep", "import android."):
            self.assertNotIn(forbidden, RUNTIME_BLOCK)
        self.assertNotIn("FeedReturnStability.update(", self.workflow)

    def test_mutations_reject_lost_return_proof_and_stale_platform_callback(self):
        for old, new in (
            ("session.lastReturnedSnapshot = stability.lastSnapshot", "Unit"),
            ("session.returnedStableSamples = stability.stableSamples", "Unit"),
            ("positionStable = stability.isStable", "positionStable = true"),
            ("session.imagePagingProgress = null", "Unit"),
            ("page.pageKind != BrowsePageKind.IMAGE_VIEWER", "false"),
        ):
            self.assertIn(old, self.runtime)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.workflow, self.runtime.replace(old, new, 1), self.boundary)
        for old, new in (
            ("accountBrowseRuntime.observeFeedReturn(", "unverifiedReturn("),
            ("generation == currentRunGeneration() && epoch == browseCallbackEpoch", "true"),
            ("check(restoreMonitorOverlayTouchPassthrough())", "restoreMonitorOverlayTouchPassthrough()"),
        ):
            self.assertIn(old, self.workflow)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.workflow.replace(old, new, 1), self.runtime, self.boundary)
