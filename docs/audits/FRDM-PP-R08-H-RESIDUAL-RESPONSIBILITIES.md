# FRDM PP R08-H — 其他大型文件、重复职责及遗留混杂代码收敛专项

## Baseline and inventory
- Repository ttiaogou/FRDM, PP, ff09d9b1bcc20431610046dffe01a2a5474a3fb4; R08-G CI 46122615576 completed/success.
- Recursive immutable tree inventory: 434 production Kotlin/Java files. Largest: AiReplyObservationRuntime 559807 bytes, MomoSocialAdapter 416470, AndroidAccountBrowseWorkflow 176538, AiReplyDraftCoordinator 135967, MainActivity 112239, PrivateMessagesTraversalTaskExecutor 106357, AppCompositionRoot 61171, RunDiagnosticsManager 59846, AndroidAccessibilityGateway 58603, MomoChatListSessionExtractor 55266, MomoChatMessageReader 55037. This is size-based screening, not an exhaustive semantic scan of every file.
- R04/R05/R06/R07 and R08-A–G owners remain intact. AndroidAccessibilityGateway and AndroidNodeSnapshotReader are S0 frozen; similar primitives there are not changed. InputOriginClassifier and gesture runtime/geometry are outside this convergence. No duplicate entry or task owner added.
- Inspected MomoChatListSessionExtractor, MomoChatMessageReader, MomoChatPageInspector, MomoPageInspector, AndroidNodeSnapshotReader, AndroidAccessibilityGateway, NodeInspectorDialog, NodeInspectorTextFormatter, SessionSampleArchiveManager; selected identical low-level fallback bodies only. Existing raw root acquisition and recycling, read budgets, message content rules, OCR, page recognition, write authority remain unchanged.

## Implemented
- app/src/main/java/com/freedom/adapters/social/momo/MomoNodeReadFallbacks.kt: same-package internal functions momoReadBooleanOrFalse, momoReadIntOrZero, momoReadNodeOrNull, momoReadBounds. Replace three copies each of boolean/int/node fallback and two copies of bounds reader with one each. Existing call sites retain original names via import aliases. No new class, state, scheduler, gateway or Android node traversal entry. Original exception scope and fallback values exact; bounds still returns the resulting Rect even if Android read throws. Different string nullability and recycle semantics deliberately left alone.
- Original three production blobs reconstructed exactly from new sources using inverse edits. R00 exact approved S1 pins (including one new helper file), R01 exact evidence pins. S0, frozen manifests, existing tests, architecture verifier, R02, task/business/gesture algorithms unchanged.
- New direct JVM tests on success/exception/null/no-retry, and two source tests for reconstruction, single implementation, mutation rejection. Android Rect/node behavior requires existing full CI and real-device tests; do not infer runtime coverage from source tests alone.

## Remaining candidates (not authorized for deletion)
- R07-I: regionRefreshTask legacy handle and cancel/clear delegates; verify full references and lifecycle before removal; cross-owner reset and recovery are not duplicate states.
- R08-A–C: existing send/recovery and browse callbacks are coupled; traversal viewport facade and legacy compatibility method remain; review whole-repo callers before removal.
- R08-D/E: MainActivity OCR/permission/export lifecycle; diagnostic report lookup/atomic write and marker construction; compare locking, failure and retention semantics before unification. NodeInspectorDialog local view/render callbacks and archive export verification are mixed but not proven duplicated.
- R08-F: runtime-local preload still relies on callback sequencing rather than independent identity/visit binding; route callback and repeated readiness binding remain candidates. Do not alter behavior within migration.
- R08-G: ChatPagePort still has 42 methods, including greeting traversal/receipt/checkpoint; same-package evidence split did not narrow capability interface. Keep existing defaults until implementer and test double audit. Provider-specific fingerprint literals frozen.
- Large remaining Momo OCR, page inspection, sample archive, overlay and UI files: inventory candidates, not proven redundant. No complete file confirmed safe to delete; no tests or old files removed.
- R09 should verify full dependency/owner graph, legacy/dead code references, unified entry and lease, recovery/checkpoints/async generations, complete tests and real-device regressions; no claim of complete semantic deduplication.
- API-only remote diff cannot establish local worktree status. Full CI and real-device acceptance separately reported.
