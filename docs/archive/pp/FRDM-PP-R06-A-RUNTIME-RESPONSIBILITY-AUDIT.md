# FRDM PP R06-A — AiReplyObservationRuntime 职责、依赖及状态归属审计

## 1. 安全基线与范围

- Repository: ttiaogou/FRDM; branch: PP; reviewed commit: `560025b8cb856baa957987f3f759265158210c73`.
- R05-I CI [46069844942](https://github.com/ttiaogou/FRDM/actions/runs/46069844942) completed/success: architecture/resource gate, CodeQL, unit tests, Freedom debug APK, OCR plugin debug APK.
- Runtime: business/src/main/kotlin/com/freedom/features/aireply/AiReplyObservationRuntime.kt; 9,849 lines; Git blob `1c0c3bc1669f0cdc56e3abbb36c2f8c0be064f8a`, equal to the R00 pre_migration_s1 freeze entry. No change to the R00/R01 manifests.
- R06-A is audit-only. R06-B..G remain separate implementation/acceptance steps. R07 stateful migration is out of scope.

## 2. Existing entry and dependency boundaries

| Boundary | Verified implementation / baseline location | Rule |
| --- | --- | --- |
| Android event entry | FreedomAccessibilityService.onAccessibilityEvent; AndroidAccessibilityEventPipeline; UnifiedAccessibilityEntry; AppCompositionRoot unifiedAccessibilityEntry (942+); RunSessionCoordinator.dispatchTargetApplicationEvent (508+) | Do not add alternate event entries, direct service calls or raw nodes to business code. |
| Business observation | Runtime.schedule (4913+), MomoPageObservationCoordinator, shared PageObservationCoordinator; runFullInspection (1327+), runRegionRefresh (3533+) | Preserve dispatch/debounce ordering and read purpose. Event occurrence is not permission to write. |
| Raw page read | AiReplyPageReadCoordinator (runtime 689–728), ChatPagePort.inspectChatPage | The existing coordinator alone advances raw observationGeneration; classify only after read. No extra page reads from extracted helpers. |
| Page semantics | AiReplyPageObservationSemantics; AiReplyPageStability; MomoInboxPageRecognizer, MomoReceivedGreetingsPageRecognizer | Keep UNKNOWN semantics and same-window comparison-only proof; do not alter page fingerprints or reuse old locators. |
| Read/OCR | ChatMessageReadCoordinator (runtime 408+), ChatMessagePort; readVisibleMessages (5036+), isMessageReadResultCurrent (5102+), handleMessageReadBatch (5719+) | Keep request generation, callback arbitration, timeout and active-window validation in the existing owner. |
| Task ownership | AiReplyTaskOwnership / TaskCoordinator; AppCompositionRoot UiWriteAuthorization (478+) | Read-only classification grants neither a lease nor final platform write permission. |
| Recovery | RecoveryEventDispatcher, RecoveryContext, shared manager, message-list/greeting checkpoint verifiers and registered routes (runtime 494–560, 693–726) | Preserve unique registered route and durable checkpoint before resume; no second recovery loop. |

Runtime receives platform-neutral ports, snapshot models, scheduler, GestureApi and injected business callbacks. It imports no Android framework classes. Production adapter, gesture pipeline and R05 controllers are not migration targets.

## 3. Responsibility inventory and approved R06 extraction candidates

Line numbers refer to the frozen Runtime baseline, not future revisions.

| Step | Candidates and current dependencies | Migration boundary |
| --- | --- | --- |
| B Page observation/evidence | recoveryStructuralKey (1674); isMessageViewportPositionedAgainstTab (3096) and hasRelevantMessageStructureDelta (3122); pageIdentityChanged (4528); hasSameWindowSession (9663), windowSessionKey (9682), selfNavigationMatchesInspection (9695), chatSurfaceKey (9710). Inputs are snapshots, transitions and rectangles only. | Reuse AiReplyPageObservationSemantics/AiReplyPageStability or a single stateless evidence unit. Preserve exact checks, ordering, equality and output keys; keep scheduling, lastPageInspection/lastConfirmedChatListInspection writes and readiness callbacks in Runtime. |
| C Private candidate extraction/judgment | handleSessionListPage (6523+) already delegates evaluateListSnapshot, recordViewportUnreadEvidence and reconcileIdentityReturn to PrivateMessagesTraversalTaskExecutor; formatUnreadAudit (7926) mixes formatting with ambiguous/attempted-key reads. ChatSessionManagementPolicy provides stableSessionKey, isEligible, boundary evidence. | Extract only pure candidate filtering/evidence aggregation with explicit input sets; reuse policy. Do not move evaluateListSnapshot state changes, identity blocking, cursor updates, checkpoint persistence, selection/admission or clicks. Do not change traversal algorithm. |
| D Greeting read-only judgments | hasOffscreenGreetingChatLocators (1160) is snapshot-only; inspectGreetingChatAdmission (1063) and greetingBindingRejectReason (1119) mix live task/lease/recovery/identity reads; existing ReceivedGreetingsTaskFlowController and recognizer. | Move pure geometry and evidence comparison only; keep stateful binding creation, identity resolution, current lease and callback validation at their original boundary. No second recognizer. |
| E Message/OCR evidence | createOcrDebugFingerprint (6480), escapeOcrDebugText (6504); isMessageReadResultCurrent (5102), sendConfirmationMessageBatchRejectionReason (5189) include live binding checks. | Extract exact stateless hashing/escaping and pure evidence comparison; keep the existing read coordinator, current-session/visit checks, side effects, retry, OCR execution, journal and generation gates. Never widen debug logging or expose message text in normal logs. |
| F Diagnostics/helpers | summarize (9734), formatChatListSessionSummary (9747), formatNodeTreeProbeSummary (9777), formatNodeEvidence (9823); logStructureEvidence (9791) emits bounded redacted records; recordPrivateUnreadDiagnostic (190) invokes injected logger. | Pure formatting only; preserve 256-entry and 3500-char limits, redaction, output strings, callback order and exception behavior. Keep loggedOcrDebugFingerprints, ocrDebugBatchLimitLogged and diagnostic deduplication state. No new logger/storage path. |
| G Integration | Existing exact R00/R01/R02 gates, source-equivalence regression, unit tests, full CI | Verify all extracted methods reconstruct frozen implementation; update exact S1 pins only after actual relocation. Preserve all prior tests and independent safety checks. |

Candidate list is a review boundary, not authorization to change algorithms. Any mixed method that cannot be proven pure stays in Runtime until R07. In particular, stabilizeIncomingPageSnapshot (9259) mutates two snapshot caches and logs; do not migrate the whole function as stateless. isSemanticallyUnconfirmed (9246) already delegates to the classifier and has a default argument tied to lastPageInspection.

## 4. Stateful responsibilities explicitly reserved for R07

- PrivateMessagesTraversalTaskExecutor remains the owner of traversal executionState, attempted/ambiguous keys, checkpoints and viewport continuity; Runtime owns current-session binding/return and its task orchestration.
- ReceivedGreetingsTaskFlowController owns existing task lifecycle; Runtime holds greetingChatBinding, confirmedGreetingSend, model failure recovery and visit/receipt orchestration.
- ChatMessageReadCoordinator owns asynchronous request generation, in-flight flags, timeout and coalescing; Runtime owns current session/visit, read retry and validated result/business callbacks.
- activePrivateFollowUp and contactMessageLedger: Runtime owns business admission and durable checkpoint progress; existing MomoPrivateFollowUpController owns platform child lifecycle, not a lease.
- pageInspectionTask/fullInspectionCallbackToken, pendingPageReadPurpose, regionRefreshTask, readiness retries, lastPageInspection/lastConfirmedChatListInspection, lastRegionFingerprint/lastMessageFingerprint remain in existing lifecycle owners.
- RecoveryContext, dispatcher, route registration, suspendedPrivateRecovery, checkpoint verifiers, task claims and paired quota/wake remain unchanged.

## 5. Tests, risks and sequence

- Preserve R00 S0/S1 inventory, R01 protected evidence, R02 trace index, R05-I 41 integration predicates, M04 frozen click implementation and architecture hard checks; no tests deleted or assertions relaxed.
- Existing regression evidence: AiReplyObservationRuntimeTest.kt, AiReplyObservationRuntimeSnapshotRecoveryPolicyTest.kt, AiReplyPageObservationSemanticsTest.kt, AiReplyPageStabilityTest.kt, ChatMessageReadCoordinatorTest.kt; architecture AccessibilityInspectorPipelineBoundaryTest.kt and UnifiedAccessibilityEntryContractTest.kt; verify_boundaries.py includes unique owner construction and ordered L07 gates.
- Major risk: extracting a method which reads implicit mutable Runtime state, changes when callbacks evaluate, or alters snapshot equality / fresh-read cadence. Require explicit inputs and exact source reconstruction, plus mutation tests for stale window/lease/identity evidence; do not create replacement execution authority.
- Preserve original package, Kotlin visibility and constructor/API contracts. Keep Runtime as orchestrator; use existing helper classes when they have the correct responsibility.
- Sequence from the approved construction plan: R06-B page evidence → C private candidate judgments → D greeting read-only judgments → E message/OCR evidence → F diagnostics → G integration. One reviewed change-set at a time; each must pass existing full CI before the next stage.
- Audit limitation: static source and green automated baseline, not fresh device proof. No local checkout/working-tree check via GitHub API. R05-I baseline did not eliminate all Adapter state; no R05 cleanup in R06-A.
