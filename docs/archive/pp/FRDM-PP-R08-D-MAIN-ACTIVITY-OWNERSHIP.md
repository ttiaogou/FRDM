# FRDM PP R08-D — MainActivity 职责、状态所有权及模块化迁移专项

## Baseline and ownership
- Repository ttiaogou/FRDM, PP, 245a8cfeacdacf3da5fc1a250dff5ce9958410a1; R08-C CI 46119375433 completed/success.
- MainActivity baseline 7afbe0c4eb9f8f67f96f9833665093424948d9c2, 2383 lines -> 2302 lines. No new production file or architecture layer.
- AppCompositionRoot.createActivityDependencies supplies repository and command ports; existing ConfigurationHome, ConfigPanel, TargetAppEditor, PersonalizationScreen, AiModelConfigurationScreen and GestureCalibrationUiController retain presentation ownership.
- MonitorControlRepository and RunSessionCommands retain run authorization/configuration authority; Activity requests commands and refreshes UI, not a new runtime coordinator. Service entry and page lease not changed.
- Activity-local receiver registration, screen navigation, pending permission/SAF results, overlay visibility, OCR install/preflight lifecycle and image import remain tied to existing Activity lifecycle; no global or duplicate owner.
- PendingCollectionExport, sessionSampleExportRunning, pendingNodeDumpExportFile and archive seal handler/runnable remain Activity-owned. SessionSampleArchiveManager owns archive verification/cleanup implementation; sampleArchivePort owns recovery access; diagnostics owns finalized history; repository owns display log snapshot/clear.

## Implemented
- Extract archive-confirmation presentation into existing CollectionExportDialog (LogRecordsDialog.kt). Activity retains empty-selection guard and confirmation callback. Preserve content order, original labels, count limit, checked disabled content rows, privacy warning, dialog style and positive/negative actions.
- Extract exported log text construction into existing LogTextFormatter, preserving format, timestamp locale, detail flag, trailing newline, and mandatory RunLogBudgetPolicy.redactSensitivePayload before formatting. Activity retains thin forwarding method for existing export call and source boundary tests.
- SAF picker, pending receipt, thread boundaries, write/flush/readback/verified cleanup/log clearance order unchanged. No permission, task, OCR, archive data or UI behavior change.
- Exact inverse reconstructs all three prior production files. R00 S1 pins and R01 new evidence only; S0, manifests, historical tests and R02 unchanged. Negative mutation checks cover confirmation, privacy warning, disabled rows, redaction and readback/cleanup.
- Remaining: Activity still has OCR manager/review/install UI, permission return and export orchestration. OCR preflight uses process-shared OcrManager but Activity-local cooldown/listener lifetime; moving it requires separate lifetime audit, not a new global latch. Similar target package save and session archive directory construction remain candidates; their existing different guards/side effects must not be silently merged. No complete file proven safe to delete.
- API-only remote diff cannot establish local worktree status. Static source acceptance is not real-device UI or lifecycle verification; full CI separate.
