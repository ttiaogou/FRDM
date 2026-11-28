# FRDM PP R08-E — RunDiagnosticsManager 职责、状态所有权及模块化迁移专项

## Baseline and actual ownership
- Repository ttiaogou/FRDM, PP, 8f10deba51362e8acf34d94f85fde42f21934266; R08-D CI 46120292462 completed/success.
- Manager baseline d44a787d7f8b9195a8e226086167573b31129b3b, 1243 lines -> 1178 lines. Existing Data owners reused; no production file added.
- AppCompositionRoot supplies the process-shared manager through RunDiagnosticsPort. Diagnostic input and analysis remain separate from task execution/lease and display log authority.
- Manager owns activeRun, finalizingRun, finalizationPending, ActiveRun sequence/counters/dedup/recent events, writer queue, finish scheduler and latch, marker and historyLock. Keep their lifetime and synchronization unchanged.
- RunEventRollingStore owns writer-thread event spool and lane budgets; RunIncidentEvidenceStore owns incident capture/transaction evidence; RunIncidentHistoryStore owns protected incident retention; Core policy/analyzers own budget constants and deterministic analysis. Do not introduce another runtime/recovery owner.
- Manager retains error-detail redaction, reservation and truncation accounting, admission, queue submission, file write order, manifest/hash, previous-process recovery and retention/export lock.
 
## Implemented
- Move boundEventFields and errorDetailLine to existing RunDiagnosticEventJsonCodec. Keep exact UTF-8 limits, field lengths, iterative JSON-size checks, fallback and error schema; original call positions retained via private delegates.
- Move bounded report JSON generation and summary text to existing RunDiagnosticReportFormatter. Keep JSON schema and byte-limit fallback, exact text and summary fields; manager continues writing JSON then bounded TXT then bounded summary.
- Exact inverse reconstructs all three previous source blobs; R00 reviewed S1 pins and R01 evidence only. S0, historical tests, R02 trace evidence, rolling store, incident capture and history retention unchanged. Negative source mutations check caps, schema, redaction, counters and sealing. No Android JVM JSON dependency added.
- Remaining: latestReportText/latestReportFile duplicate lookup, repeated atomic temporary-file writes, repeated marker fields and historical report reading are candidates; their failure, locking and legacy-data semantics require separate verification. No file proven safe to delete. ActiveRun and counters remain cohesive with the existing writer/finalizer and should not be split into parallel owners.
- API-only remote diff cannot establish local worktree state; source preflight is not real-device or complete CI acceptance.
