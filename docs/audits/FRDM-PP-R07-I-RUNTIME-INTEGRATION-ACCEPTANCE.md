# FRDM PP R07-I — AiReplyObservationRuntime 集成验收及冗余门面专项审计

## 基线和范围

- Repository ttiaogou/FRDM; branch PP; baseline 0306479b9a6215fdd913a5424acac461598c5b15; R07-H CI [46115661630](https://github.com/ttiaogou/FRDM/actions/runs/46115661630) completed/success (architecture, both APK builds, unit tests, CodeQL).
- R07-A baseline Runtime blob ee67d98af55bdcba4270128132db9b5317f6c942; R07-H Runtime blob 7f5d99bcfccf80e2404880fcdfd5341c73a19014.
- R07-I production changes: zero. New integrated architecture test and evidence pin; preserve historical manifests, inverse chain, R02 behavior trace, existing gates and regressions.
- Current Runtime: 9,153 lines vs 9,474 at R07-A; 37 class-level private var declarations vs 97, including delegated accessors. Class-level fun declarations 170 vs 174. Counts are textual inventory, not independent state counts or proof of behavior.

## B-H integrated ownership acceptance

| Stage | Scoped declarations | Owner and invariant |
| --- | ---: | --- |
| B | 7 | MomoPageObservationCoordinator: explicit observation handle/token/purpose, cache and legacy region handle; actual shared region channel retained. |
| C | 15 | PrivateMessagesTraversalTaskExecutor: private visit/identity/return and viewport retry; cursor and durable checkpoint unchanged. |
| D | 2 | ReceivedGreetingsTaskFlowController: greeting visit and receipt; controller/executor retain lifecycle/platform responsibilities. |
| E | 19 | ChatMessageReadCoordinator: shared message/OCR lifetime, visit token, model and read retry; no stale window/visit/lease bypass. |
| F | 4 | PrivateMessagesTraversalTaskExecutor (3) + ChatMessageReadCoordinator (1): confirmed private receipt, child binding, deferred send-proof retry; journals/ledger unchanged. |
| G | 9 | AiReplyTaskOwnership: two independent quotas, receipt readiness, pair wake, handoff and retry debt; TaskCoordinator remains lease authority. |
| H | 7 | AiReplySessionTraversalCoordinator, PageRecoveryVerificationGate, MessageListCheckpointVerification, ReceivedGreetingsCheckpointVerification: route-specific observation flags, empty episode and retry debt; common reducer/scheduler remain S0. |

63 named migrated declarations checked across Runtime and nine destination source files, each appears exactly once in its intended file. Scoped lexical inventory, not a whole-repository semantic alias analysis. The existing seven-stage exact inverse reconstructs the R07-A Runtime; R06-G acceptance separately reconstructs its earlier baseline. New integrated tests verify cross-owner reset/visit retirement, reconnect ordering, checkpoint persistence before reset, explicit stop schedule clearing, mode adapter and recovery route wiring; mutations reject duplicate or missing ownership and lost boundary tokens. Existing behavior tests remain mandatory.

## Actual entry and authority

FreedomAccessibilityService -> AndroidAccessibilityEventPipeline -> UnifiedAccessibilityEntry -> AppCompositionRoot -> RunSessionCoordinator -> AiReplyModeRuntimeAdapter -> AiReplyObservationRuntime.schedule. TaskCoordinator/TaskExecutionController retain lease authority; RecoveryContext, RecoveryEventDispatcher, UnifiedRecoveryTransactionManager and registered route adapters remain complementary. No new scheduler, page recognizer, gesture path or recovery authority. Archive lifecycle, run authorization, observation generation, lease revision, sessionActionGeneration, visit token and OCR request generation remain distinct.

## Facade and redundancy inventory (no removal authorized)

| Item | Classification | Evidence / disposition |
| --- | --- | --- |
| MomoPageObservationCoordinator.regionRefreshTask + cancelLegacyRegionRefreshHandle/clearLegacyRegionRefreshHandle | Confirmed local legacy-handle candidate | Only null initialization, cancel and null write in current owner (lines 34, 71, 73); live regionRefreshChannel registered at 47 and requested/cancelled at 136/140/153. Runtime reset calls both wrappers. No non-null write found in inspected owner. Keep now; whole-repo reflection/reference check and targeted reset test needed before authorized deletion. |
| Runtime currentSessionKey/Name/Candidate/ReadCaptured/WindowKey, headerMismatchGuard/headerMismatchRecordedForCurrentAttempt | Pure forwarding candidates (7) | Delegated getters/setters to PrivateMessagesTraversalTaskExecutor; no duplicate backing fields. Keep until separately authorized mechanical cleanup with exact inverse and caller audit. |
| Runtime receivedGreetingsTaskActive/awaitingNormalInboxAfterGreetingsReturn | Pure forwarding candidates (2) | Getters to ReceivedGreetingsTaskFlowController. Readability and call-site usage; not new state. |
| Runtime invalidateMessageReadTransaction/cancelMessageReadRetry | Thin method forwarding candidates (2) | Explicit Unit return types from R07-E compile repair; live 6/11 textual references including declaration/delegation. Retain until type-inference and callback order regression checked. |
| Runtime sessionManagerState | Required cross-owner facade | Setter updates executor.executionState AND recoveryContext.recordPauseState; removing or bypassing it loses pause-domain evidence. |
| Runtime invalidatePendingMessageRead/reset | Required orchestration | Cross-owner visit, greeting binding, retry, callback and conditional recovery retirement; no wholesale relocation. |
| Runtime sessionTransitionTask/sessionActionGeneration/activeSessionGestureRequestId | Shared coordination, not established duplicate | 71/40 textual references for handle/generation; multiple navigation, return, wake and retry flows. Need dedicated callback/lifetime audit before splitting. |
| Runtime readinessDeferredRetry* and startup coordinator | Distinct current lifetimes; possible future audit | Deferred observation gate is separate from startup probe and Android readiness; preserve cancellation and generation semantics. |
| Runtime lastIdentityReturnNotConsumedReason, loggedOcrDebugFingerprints, ocrDebugBatchLimitLogged | Remaining diagnostic state | Visit/window reset coupling; not demonstrated duplicates. Candidate for targeted ownership audit, not automatic deletion. |
| Runtime suspendedPrivateRecovery, checkpoint references/restore sources and privateListRecoveryReasonAfterObservedDetail | Required recovery coordination for current architecture | Cross-task handoff and constructor load ordering; no duplicate durable store proven. |
| logStructureEvidence comment vs MAX_STRUCTURE_LOG_LINE_CHARS | Documentation discrepancy | Comment says 4500, actual constant 3500; report only, no parameter change. |

No file confirmed safe to delete. Legacy-handle candidate is local code evidence, not authorization for removal. R07-I intentionally changes no Kotlin production or test files; no existing assertion, file or test is removed.

## Validation boundary

Pre-commit: pinned source identity, scoped 63-field ownership scan and order/wiring anchor check. Full inverse, all existing architecture checks, unit tests, CodeQL and APK builds must run on R07-I commit; R07-H green CI is only baseline evidence. API-only remote comparison cannot prove local git worktree status. Real-device end-to-end behavior is not tested here. Next R08 may proceed after full CI acceptance; reported candidates remain for explicitly authorized cleanup.
