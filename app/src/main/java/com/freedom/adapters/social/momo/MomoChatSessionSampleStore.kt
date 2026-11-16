package com.freedom.adapters.social.momo


import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageObservationIndex
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.logging.RunLogBudgetPolicy
import com.freedom.core.logging.NodeTreeCapturePriority
import com.freedom.application.ports.RunDiagnosticsPort
import com.freedom.application.ports.SessionSampleArchivePort
import com.freedom.core.accessibility.diagnostics.NodeSelectorDiagnosticsBuffer
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Explicit AI-mode sample store. Contact names, previews and OCR text are persisted only in this
 * local sample package; ordinary Freedom event logs remain redacted. Files stay in the app-specific
 * Documents directory and require no broad storage permission.
 */
internal class MomoChatSessionSampleStore(
    context: Context,
    private val diagnostics: RunDiagnosticsPort,
    private val archivePort: SessionSampleArchivePort
) {
    private val appContext = context.applicationContext
    private companion object {
        const val MAX_RUNTIME_LOG_ENTRIES = RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_ENTRIES
        const val MAX_RUNTIME_LOG_CHARS = 5_000 // Legacy guard; UTF-8 byte budget is authoritative.
        const val MAX_EVENT_TIMELINE_ENTRIES = 4_000
    }
    private val rootDirectory: File = File(
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir,
        "FreedomSamples"
    )
    private var runDirectory: File? = null
    private var diagnosticRunId: String? = null
    private var nextChatBatchIndex = 1
    private var runtimeLogCount = 0
    private var runtimeLogSequence = 0L
    private var runtimeLogBytes = 0L
    private var runtimeLogDroppedCount = 0L
    private var runtimeLogDroppedBytes = 0L
    private var runtimeLogTruncatedCount = 0L
    private var runtimeLogTruncatedBytes = 0L
    private var runtimeLogsTruncated = false
    private var eventTimelineCount = 0
    private var eventTimelineSequence = 0L
    private var eventTimelineBytes = 0L
    private var eventTimelineDroppedCount = 0L
    private var eventTimelineDroppedBytes = 0L
    private var eventTimelineDeduplicatedCount = 0L
    private var lastEventSignature = ""
    private var lastEventAtMillis = 0L
    private var pendingEventDuplicateCount = 0L
    private var pendingDuplicateType = ""
    private var eventTimelineTruncated = false
    private var archiveWriteFailureCount = 0
    private var screenshotWriteFailureCount = 0
    private var runFinishedAtMillis: Long? = null
    private var runAcceptingRecords = false
    private val conversationMessageIndex = ChatMessageObservationIndex()
    private val imageStore by lazy(LazyThreadSafetyMode.NONE) {
        MomoChatSampleImageStore(
            isRunAcceptingRecords = { runAcceptingRecords },
            runDirectory = { runDirectory },
            appendJsonLine = ::appendJsonLine,
            relativeSamplePath = ::relativeSamplePath,
            blockSessionIdentityKey = ::blockSessionIdentityKey,
            onWriteFailure = { screenshotWriteFailureCount++ }
        )
    }
    private val nodeTreeStore by lazy(LazyThreadSafetyMode.NONE) {
        MomoChatSampleNodeTreeStore(
            isRunAcceptingRecords = { runAcceptingRecords },
            runDirectory = { runDirectory }
        )
    }
    private val metadataStore by lazy(LazyThreadSafetyMode.NONE) {
        MomoChatSampleMetadataStore(
            isRunAcceptingRecords = { runAcceptingRecords },
            runDirectory = { runDirectory },
            appendJsonLine = ::appendJsonLine,
            onArchiveWriteFailure = { archiveWriteFailureCount++ }
        )
    }
    private val sessionListStore by lazy(LazyThreadSafetyMode.NONE) {
        MomoChatSampleSessionListStore(
            isRunAcceptingRecords = { runAcceptingRecords },
            runDirectory = { runDirectory },
            blockedIdentityKeys = { blockedSessionIdentityKeys },
            appendJsonLine = ::appendJsonLine
        )
    }
    /** Run-local collision keys remain blocked even when duplicate rows leave the viewport. */
    private val blockedSessionIdentityKeys = LinkedHashSet<String>()

    @Synchronized
    fun beginRun(): String {
        if (runDirectory != null) {
            check(!finishRun().isNullOrBlank()) { "上一次采集任务尚未成功封存，已保留现场；请先处理封存失败" }
        }
        rootDirectory.mkdirs()
        val runsDirectory = File(rootDirectory, "runs")
        // Recover only abandoned directories. The recovery layer recognizes this process' owner
        // token and will never touch another run that is still active in the current process.
        archivePort.recoverPendingRuns(runsDirectory)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val unique = "${stamp}-${android.os.SystemClock.elapsedRealtime()}"
        val directory = File(runsDirectory, unique)
        // Publish ownership before creating the run directory so a concurrent startup scan
        // cannot mistake the few-millisecond creation window for an abandoned legacy run.
        check(archivePort.writeOwnerMarker(runsDirectory, unique, System.currentTimeMillis())) {
            "无法创建采集任务活动标记"
        }
        check(directory.mkdirs() || directory.isDirectory) {
            archivePort.removeOwnerMarker(runsDirectory, unique)
            "无法创建样本目录"
        }
        runDirectory = directory
        diagnosticRunId = diagnostics.activeRunId()
        imageStore.resetForNewRun()
        nodeTreeStore.resetForNewRun()
        nextChatBatchIndex = 1
        metadataStore.resetForNewRun()
        sessionListStore.resetForNewRun()
        runtimeLogCount = 0
        runtimeLogSequence = 0L
        runtimeLogBytes = 0L
        runtimeLogDroppedCount = 0L
        runtimeLogDroppedBytes = 0L
        runtimeLogTruncatedCount = 0L
        runtimeLogTruncatedBytes = 0L
        runtimeLogsTruncated = false
        eventTimelineCount = 0
        eventTimelineSequence = 0L
        eventTimelineBytes = 0L
        eventTimelineDroppedCount = 0L
        eventTimelineDroppedBytes = 0L
        eventTimelineDeduplicatedCount = 0L
        lastEventSignature = ""
        lastEventAtMillis = 0L
        pendingEventDuplicateCount = 0L
        pendingDuplicateType = ""
        eventTimelineTruncated = false
        archiveWriteFailureCount = 0
        screenshotWriteFailureCount = 0
        runFinishedAtMillis = null
        runAcceptingRecords = true
        NodeSelectorDiagnosticsBuffer.clear()
        conversationMessageIndex.clear()
        blockedSessionIdentityKeys.clear()
        appendJsonLine("run.jsonl", JSONObject()
            .put("event", "run_started")
            .put("runId", unique)
            .put("diagnosticRunId", diagnosticRunId ?: JSONObject.NULL)
            .put("startedAt", System.currentTimeMillis())
            .put("package", MomoAppDescriptor.PACKAGE_NAME))
        return directory.absolutePath
    }

    /** Archives the same monitor log message in the active AI collection run. */
    @Synchronized
    fun recordRuntimeLog(message: String) {
        if (!runAcceptingRecords) return
        val directory = runDirectory ?: return
        val originalBytes = RunLogBudgetPolicy.utf8Size(message).toLong()
        runtimeLogSequence++
        if (runtimeLogCount >= MAX_RUNTIME_LOG_ENTRIES) {
            runtimeLogsTruncated = true
            runtimeLogDroppedCount++
            runtimeLogDroppedBytes += originalBytes
            return
        }
        val normalized = message.replace('\r', ' ').replace('\n', ' ')
        val charBounded = normalized.take(MAX_RUNTIME_LOG_CHARS)
        val redacted = RunLogBudgetPolicy.redactSensitivePayload(charBounded)
        val capturedAt = System.currentTimeMillis()
        val sequence = runtimeLogSequence
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(capturedAt))
        var messageBudget = minOf(
            RunLogBudgetPolicy.utf8Size(redacted),
            RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_RECORD_BYTES / 2
        )
        var safeMessage = RunLogBudgetPolicy.truncateUtf8(redacted, messageBudget)
        var messageWasTruncated = normalized.length > charBounded.length ||
            RunLogBudgetPolicy.utf8Size(redacted) > messageBudget
        var jsonLine = ""
        var textLine = ""
        var recordBytes = 0L
        while (true) {
            val record = JSONObject()
                .put("sequence", sequence)
                .put("capturedAtEpochMs", capturedAt)
                .put("message", safeMessage)
                .put("messageTruncated", messageWasTruncated)
                .put("originalMessageBytes", originalBytes)
                .put("storedMessageBytes", RunLogBudgetPolicy.utf8Size(safeMessage))
            jsonLine = record.toString() + "\n"
            textLine = "$timestamp  $safeMessage\n"
            recordBytes = RunLogBudgetPolicy.utf8Size(jsonLine).toLong() +
                RunLogBudgetPolicy.utf8Size(textLine).toLong()
            if (recordBytes <= RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_RECORD_BYTES || messageBudget <= 0) break
            messageBudget = (messageBudget * 3 / 4).coerceAtLeast(0)
            safeMessage = RunLogBudgetPolicy.truncateUtf8(redacted, messageBudget)
            messageWasTruncated = true
        }
        if (messageWasTruncated) {
            runtimeLogsTruncated = true
            runtimeLogTruncatedCount++
            val removedByCharacterGuard = (RunLogBudgetPolicy.utf8Size(normalized) -
                RunLogBudgetPolicy.utf8Size(charBounded)).coerceAtLeast(0)
            val removedByByteGuard = (RunLogBudgetPolicy.utf8Size(redacted) -
                RunLogBudgetPolicy.utf8Size(safeMessage)).coerceAtLeast(0)
            runtimeLogTruncatedBytes += (removedByCharacterGuard + removedByByteGuard).toLong()
        }
        if (recordBytes > RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_RECORD_BYTES ||
            runtimeLogBytes + recordBytes > RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_BYTES
        ) {
            runtimeLogsTruncated = true
            runtimeLogDroppedCount++
            runtimeLogDroppedBytes += recordBytes
            return
        }
        val jsonFile = File(directory, "runtime_logs.jsonl")
        val textFile = File(directory, "runtime_logs.txt")
        val jsonBefore = jsonFile.length()
        val textBefore = textFile.length()
        try {
            jsonFile.appendText(jsonLine, Charsets.UTF_8)
            textFile.appendText(textLine, Charsets.UTF_8)
            runtimeLogCount++
            runtimeLogBytes += recordBytes
        } catch (error: Exception) {
            val jsonWritten = (jsonFile.length() - jsonBefore).coerceAtLeast(0L)
            val textWritten = (textFile.length() - textBefore).coerceAtLeast(0L)
            val actualWrittenBytes = jsonWritten + textWritten
            runtimeLogBytes += actualWrittenBytes
            runtimeLogsTruncated = true
            runtimeLogDroppedCount++
            runtimeLogDroppedBytes += (recordBytes - actualWrittenBytes).coerceAtLeast(0L)
            archiveWriteFailureCount++
            diagnostics.recordException(
                component = "MomoChatSessionSampleStore",
                eventType = "runtime_log_write_failed",
                message = "AI采集运行日志部分写入或写入失败；sequence=$sequence；jsonWrittenBytes=$jsonWritten；textWrittenBytes=$textWritten",
                error = error
            )
        }
    }

    /** Archives target-app event metadata without event text, descriptions or contact identifiers. */
    @Synchronized
    fun recordAccessibilityEvent(event: AccessibilityEventEnvelope) {
        if (!runAcceptingRecords) return
        val directory = runDirectory ?: return
        val source = event.source
        val signature = listOf(
            event.type.name,
            event.packageName,
            event.className,
            source?.viewId.orEmpty(),
            event.windowId.toString(),
            event.inputOriginDecision?.origin?.name.orEmpty()
        ).joinToString("|")
        if (signature == lastEventSignature &&
            event.eventTimeMillis - lastEventAtMillis in 0..RunLogBudgetPolicy.DEDUPLICATION_WINDOW_MS
        ) {
            eventTimelineDeduplicatedCount++
            pendingEventDuplicateCount++
            pendingDuplicateType = event.type.name
            lastEventAtMillis = event.eventTimeMillis
            return
        }
        flushPendingEventDuplicateSummary(directory)
        lastEventSignature = signature
        lastEventAtMillis = event.eventTimeMillis
        if (eventTimelineCount >= MAX_EVENT_TIMELINE_ENTRIES) {
            eventTimelineTruncated = true
            eventTimelineDroppedCount++
            eventTimelineDroppedBytes += 256L
            return
        }
        val decision = event.inputOriginDecision
        val record = JSONObject()
            .put("sequence", eventTimelineSequence + 1)
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("eventTimeMillis", event.eventTimeMillis)
            .put("type", event.type.name)
            .put("packageName", event.packageName)
            .put("className", event.className)
            .put("windowId", event.windowId)
            .put("inputOrigin", decision?.origin?.name ?: JSONObject.NULL)
            .put("inputOriginEvidence", decision?.evidence?.name ?: JSONObject.NULL)
            .put("actionId", decision?.actionId ?: JSONObject.NULL)
            .put("inputOriginReason", decision?.reason?.take(256) ?: JSONObject.NULL)
            .put("contentChanges", JSONArray().apply { event.contentChanges.forEach { put(it.name) } })
            .put("signals", JSONArray().apply { event.signals.forEach { put(it.name) } })
            .put("source", source?.let {
                JSONObject()
                    .put("className", it.className)
                    .put("viewId", it.viewId)
                    .put("bounds", it.bounds?.let(MomoChatSampleDataMapper::rectJson))
                    .put("clickable", it.clickable)
                    .put("longClickable", it.longClickable)
                    .put("scrollable", it.scrollable)
                    .put("editable", it.editable)
            } ?: JSONObject.NULL)
        appendEventTimelineRecord(directory, record)
    }

    @Synchronized
    fun persistSessionList(snapshot: ChatPageSnapshot): Int = sessionListStore.persist(snapshot)

    @Synchronized
    fun persistChatHeaderValidationDiagnostic(
        snapshot: ChatPageSnapshot,
        expectedSessionName: String?,
        matched: Boolean,
        expectedCandidate: ChatListCandidateSnapshot? = null
    ) = metadataStore.persistChatHeaderValidationDiagnostic(
        snapshot, expectedSessionName, matched, expectedCandidate
    )

    @Synchronized
    fun persistChatScreenshot(sampleIndex: Int, screenshotPath: String?, source: String) =
        imageStore.persistChatScreenshot(sampleIndex, screenshotPath, source)

    @Synchronized
    fun persistSessionListScreenshot(
        sampleIndex: Int,
        screenshotPath: String?,
        snapshot: ChatPageSnapshot
    ) = imageStore.persistSessionListScreenshot(sampleIndex, screenshotPath, snapshot)

    @Synchronized
    fun saveBitmap(prefix: String, bitmap: Bitmap): String? = imageStore.saveBitmap(prefix, bitmap)

    @Synchronized
    fun saveCropBitmap(rowIndex: Int, bitmap: Bitmap): String? = imageStore.saveCropBitmap(rowIndex, bitmap)

    @Synchronized
    fun persistChatBatch(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        sessionName: String?,
        contactIdentity: ContactRowIdentity? = null,
        greetingRowKeyHash: String? = null,
        greetingIdentityStatus: String? = null
    ): Int {
        if (!runAcceptingRecords) return 0
        val directory = runDirectory ?: return 0
        val messages = JSONArray()
        batch.messages.forEach { message ->
            messages.put(JSONObject()
                .put("id", message.id)
                .put("direction", message.direction.name)
                .put("type", message.type.name)
                .put("text", message.content.orEmpty())
                .put("quality", message.contentQuality.name)
                .put("directionEvidence", message.directionEvidence.name)
                .put("bounds", MomoChatSampleDataMapper.rectJson(message.bounds)))
        }
        val evidence = JSONArray()
        batch.messageStructureEvidence.forEach { item ->
            evidence.put(JSONObject()
                .put("rowIndex", item.rowIndex)
                .put("rowBounds", MomoChatSampleDataMapper.rectJson(item.rowBounds))
                .put("bodyViewId", item.bodyViewId)
                .put("bodyViewClass", item.bodyViewClass)
                .put("bubbleBounds", item.bubbleBounds?.let(MomoChatSampleDataMapper::rectJson))
                .put("avatarBounds", item.avatarBounds?.let(MomoChatSampleDataMapper::rectJson)))
        }
        val directionDiagnostics = JSONArray()
        batch.directionDiagnostics.forEach { item ->
            directionDiagnostics.put(JSONObject()
                .put("rowIndex", item.rowIndex)
                .put("rowBounds", MomoChatSampleDataMapper.rectJson(item.rowBounds))
                .put("bubbleBounds", item.bubbleBounds?.let(MomoChatSampleDataMapper::rectJson))
                .put("avatarBounds", item.avatarBounds?.let(MomoChatSampleDataMapper::rectJson))
                .put("bubbleCenterRatio", item.bubbleCenterRatio?.toDouble())
                .put("avatarCenterRatio", item.avatarCenterRatio?.toDouble())
                .put("reason", item.reason.name))
        }
        val pageEvidence = JSONArray()
        snapshot.structureEvidence.forEach { item ->
            pageEvidence.put(JSONObject()
                .put("path", item.path)
                .put("className", item.className)
                .put("viewId", item.viewId)
                .put("left", item.left)
                .put("top", item.top)
                .put("right", item.right)
                .put("bottom", item.bottom)
                .put("childCount", item.childCount)
                .put("editable", item.isEditable)
                .put("scrollable", item.isScrollable)
                .put("clickable", item.isClickable)
                .put("hasText", item.hasText)
                .put("insideListCandidate", item.insideListCandidate)
                .put("depth", item.depth)
                .put("parentPath", item.parentPath)
                .put("visibleToUser", item.isVisibleToUser)
                .put("messageRowCandidate", item.isMessageRowCandidate)
                .put("textLength", item.textLength)
                .put("contentDescriptionLength", item.contentDescriptionLength)
                .put("boundsValid", item.boundsValid))
        }
        val ocr = JSONObject()
            .put("attempted", batch.ocrAttempted)
            .put("recognizedTargetCount", batch.ocrRecognizedTargetCount)
            .put("failedTargetCount", batch.ocrFailedTargetCount)
            .put("captureDescription", batch.ocrCaptureDescription.orEmpty())
            .put("failureReason", batch.ocrFailureReason.orEmpty())
            .put("screenshotPath", batch.sampleScreenshotPath?.let(::relativeSamplePath).orEmpty())
            .put("cropPathsByRowIndex", MomoChatSampleDataMapper.stringMapJson(batch.sampleCropPathsByRowIndex.mapValues { relativeSamplePath(it.value) }))
            .put("finalTextByRowIndex", MomoChatSampleDataMapper.stringMapJson(batch.ocrDebugTextByRowIndex))
            .put("chineseTextByRowIndex", MomoChatSampleDataMapper.stringMapJson(batch.ocrDebugChineseTextByRowIndex))
            .put("latinTextByRowIndex", MomoChatSampleDataMapper.stringMapJson(batch.ocrDebugLatinTextByRowIndex))
            .put("sampleTextByRowIndex", MomoChatSampleDataMapper.stringMapJson(batch.ocrSampleTextByRowIndex))
            .put("confidenceByRowIndex", MomoChatSampleDataMapper.floatMapJson(batch.ocrConfidenceByRowIndex))
            .put("lineCountByRowIndex", MomoChatSampleDataMapper.intMapJson(batch.ocrLineCountByRowIndex))
            .put("groundTruthStatus", "PENDING_MANUAL_LABEL")
            .put("accuracyAvailable", false)
            .put("accuracy", JSONObject.NULL)
        val ocrMetrics = MomoOcrSampleMetrics.calculate(
            attemptedTargetCount = if (batch.ocrAttempted) batch.ocrAttemptedTargetCount else 0,
            textByRowIndex = batch.ocrSampleTextByRowIndex,
            failedTargetCount = batch.ocrFailedTargetCount,
            confidenceByRowIndex = batch.ocrConfidenceByRowIndex
        )
        ocr.put("attemptedTargetCount", ocrMetrics.attemptedTargetCount)
            .put("recognizedTargetCount", ocrMetrics.recognizedTargetCount)
            .put("failedTargetCount", ocrMetrics.failedTargetCount)
            .put("recognitionYieldPercent", ocrMetrics.recognitionYieldPercent ?: JSONObject.NULL)
            .put("confidenceSampleCount", ocrMetrics.confidenceSampleCount)
            .put("meanConfidence", ocrMetrics.meanConfidence ?: JSONObject.NULL)

        val headerMatch = ChatSessionManagementPolicy.matchChatHeader(
            sessionName, snapshot.chatTitleText
        )
        val index = nextChatBatchIndex++
        val record = JSONObject()
            .put("sampleType", "chat_message_batch")
            .put("sampleIndex", index)
            .put("capturedAt", batch.capturedAtMillis)
            .put("sessionName", sessionName.orEmpty())
            .put("sessionHeaderName", snapshot.chatTitleText.orEmpty())
            .put("greetingRowKeyHash", greetingRowKeyHash ?: JSONObject.NULL)
            .put("greetingIdentityStatus", greetingIdentityStatus ?: JSONObject.NULL)
            .put("contactId", contactIdentity?.contactId ?: JSONObject.NULL)
            .put("contactIdentityConfidence", contactIdentity?.confidence?.name ?: JSONObject.NULL)
            .put("sessionHeaderMatchesExpected", headerMatch != null)
            .put("chatHeaderNodeDiagnostic", snapshot.chatHeaderDiagnostic?.let { diagnostic ->
                JSONObject()
                    .put("found", diagnostic.found)
                    .put("textLength", diagnostic.textLength)
                    .put("contentDescriptionLength", diagnostic.contentDescriptionLength)
                    .put("isVisibleToUser", diagnostic.isVisibleToUser)
                    .put("bounds", JSONObject()
                        .put("left", diagnostic.left).put("top", diagnostic.top)
                        .put("right", diagnostic.right).put("bottom", diagnostic.bottom))
                    .put("boundsValid", diagnostic.boundsValid)
                    .put("parentFound", diagnostic.parentFound)
                    .put("parentClassName", diagnostic.parentClassName)
                    .put("parentViewId", diagnostic.parentViewId)
                    .put("parentIsVisibleToUser", diagnostic.parentIsVisibleToUser)
                    .put("parentBoundsValid", diagnostic.parentBoundsValid)
                    .put("readOutcome", diagnostic.readOutcome)
            })
            .put("activity", snapshot.activityClass)
            .put("windowSessionKey", batch.windowSessionKey.orEmpty())
            .put("readStatus", batch.status.name)
            .put("scannedRowCount", batch.scannedRowCount)
            .put("candidateRowCount", batch.candidateRowCount)
            .put("unrecognizedRowCount", batch.unrecognizedRowCount)
            .put("truncated", batch.truncated)
            .put("coordinateSpaceReliable", batch.coordinateSpaceReliable)
            .put("rootBounds", batch.coordinateRootBounds?.let(MomoChatSampleDataMapper::rectJson))
            .put("listBounds", batch.coordinateListBounds?.let(MomoChatSampleDataMapper::rectJson))
            .put("messages", messages)
            .put("pageStructureEvidence", pageEvidence)
            .put("messageStructureEvidence", evidence)
            .put("directionDiagnostics", directionDiagnostics)
            .put("ocr", ocr)
        appendJsonLine("chat_samples.jsonl", record)
        // Message observations must not be merged by nickname. The ordinary inbox currently
        // exposes no platform ID, so only a verified profile-derived contact may receive a
        // cross-snapshot conversation index. Name-only observations stay in this run archive.
        val verifiedContactKey = contactIdentity
            ?.takeIf { it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED }
            ?.contactId
            ?.let(ChatSessionManagementPolicy::stableSessionKeyForVerifiedContact)
        if (headerMatch != null && !batch.windowSessionKey.isNullOrBlank() &&
            !verifiedContactKey.isNullOrBlank()
        ) {
            conversationMessageIndex.observe(verifiedContactKey, batch)
        }
        return if (directory.isDirectory) index else 0
    }

    /**
     * Persists the identity result obtained after a verified row-avatar navigation. The raw
     * platform ID is deliberately not accepted here: ContactIdentityManager stores only its
     * one-way identity-key hash, and this archive stores only the resolved local contact handle.
     */
    @Synchronized
    fun persistSayHiIdentityResult(
        rowKeyHash: String,
        displayName: String?,
        status: MomoSayHiPlatformIdentityStatus,
        candidateCount: Int,
        source: String,
        identity: ContactRowIdentity?
    ): Boolean = metadataStore.persistSayHiIdentityResult(
        rowKeyHash, displayName, status, candidateCount, source, identity
    )

    @Synchronized
    fun persistSayHiInspection(inspection: MomoSayHiPageInspection): Boolean =
        metadataStore.persistSayHiInspection(inspection)

    /**
     * Writes a bounded, structural-only AutoJs6-style node dump into the current run archive.
     * Duplicate snapshots are skipped by a text-free structural signature; no message or contact
     * strings are stored by this path.
     */
    @Synchronized
    fun recordNodeTree(
        snapshot: AccessibilityTreeSnapshot,
        priority: NodeTreeCapturePriority = NodeTreeCapturePriority.STANDARD
    ): Boolean = nodeTreeStore.record(snapshot, priority)

    @Synchronized
    fun blockSessionIdentityKey(sessionKey: String) {
        if (!runAcceptingRecords) return
        val key = sessionKey.trim()
        if (key.isBlank()) return
        blockedSessionIdentityKeys += key
        conversationMessageIndex.blockSessionIdentity(key)
    }

    @Synchronized
    fun isSessionIdentityBlocked(sessionKey: String): Boolean {
        val key = sessionKey.trim()
        return key.isNotBlank() && key in blockedSessionIdentityKeys
    }

    @Synchronized
    fun finishRun(): String? {
        val directory = runDirectory ?: return null
        runAcceptingRecords = false
        flushPendingEventDuplicateSummary(directory)
        val existingZip = File(directory.parentFile, "${directory.name}.zip")
        if (existingZip.isFile && archivePort.isVerifiedArchive(existingZip)) {
            return try {
                check(directory.deleteRecursively() || !directory.exists()) { "完整 ZIP 已存在但原始目录仍无法清理" }
                archivePort.removeOwnerMarker(File(rootDirectory, "runs"), directory.name)
                runDirectory = null
                diagnosticRunId = null
                conversationMessageIndex.clear()
                blockedSessionIdentityKeys.clear()
                imageStore.clearHistory()
                existingZip.absolutePath
            } catch (_: Exception) {
                null
            }
        }
        return try {
            archivePort.persistConversationMessageIndex(directory, conversationMessageIndex)
            val historySummary = conversationMessageIndex.summary()
            val selectorDiagnostics = NodeSelectorDiagnosticsBuffer.toJsonLines()
            val selectorDiagnosticsDroppedCount = NodeSelectorDiagnosticsBuffer.droppedCount()
            if (selectorDiagnostics.isNotBlank()) {
                File(directory, "node_search.jsonl").writeText(selectorDiagnostics, Charsets.UTF_8)
            } else {
                runCatching { File(directory, "node_search.jsonl").delete() }
            }

            val finishedAt = runFinishedAtMillis ?: System.currentTimeMillis()
            val captureWarnings = buildList {
                if (runtimeLogsTruncated) add("RUNTIME_LOG_LIMIT_REACHED")
                if (runtimeLogDroppedCount > 0) add("RUNTIME_LOG_BYTE_BUDGET_OR_ENTRY_LIMIT_REACHED")
                if (runtimeLogTruncatedCount > 0) add("RUNTIME_LOG_RECORD_TRUNCATED")
                if (eventTimelineTruncated) add("EVENT_TIMELINE_LIMIT_REACHED")
                if (eventTimelineDroppedCount > 0) add("EVENT_TIMELINE_BYTE_BUDGET_OR_ENTRY_LIMIT_REACHED")
                if (nodeTreeStore.captureLimitReached) add("NODE_TREE_SAMPLE_LIMIT_REACHED")
                if (nodeTreeStore.writeFailureCount > 0) add("NODE_TREE_WRITE_FAILURE")
                if (archiveWriteFailureCount > 0) add("ARCHIVE_ENTRY_WRITE_FAILURE")
                if (screenshotWriteFailureCount > 0) add("SCREENSHOT_WRITE_FAILURE")
                if (selectorDiagnosticsDroppedCount > 0L) add("NODE_SEARCH_DIAGNOSTICS_EVICTED")
            }
            File(directory, "run_manifest.json").writeText(
                JSONObject()
                    .put("schemaVersion", 3)
                    .put("archivePurpose", "complete_ai_session_observation")
                    .put("runId", directory.name)
                    .put("diagnosticRunId", diagnosticRunId ?: JSONObject.NULL)
                    .put("finishedAtEpochMs", finishedAt)
                    .put("containsRuntimeLogs", runtimeLogCount > 0)
                    .put("runtimeLogCount", runtimeLogCount)
                    .put("runtimeLogSequenceCount", runtimeLogSequence)
                    .put("runtimeLogsTruncated", runtimeLogsTruncated)
                    .put("runtimeLogBytes", runtimeLogBytes)
                    .put("runtimeLogDroppedCount", runtimeLogDroppedCount)
                    .put("runtimeLogDroppedBytes", runtimeLogDroppedBytes)
                    .put("runtimeLogTruncatedCount", runtimeLogTruncatedCount)
                    .put("runtimeLogTruncatedBytes", runtimeLogTruncatedBytes)
                    .put("eventTimelineCount", eventTimelineCount)
                    .put("eventTimelineSequenceCount", eventTimelineSequence)
                    .put("eventTimelineTruncated", eventTimelineTruncated)
                    .put("eventTimelineBytes", eventTimelineBytes)
                    .put("eventTimelineDeduplicatedCount", eventTimelineDeduplicatedCount)
                    .put("eventTimelineDroppedCount", eventTimelineDroppedCount)
                    .put("eventTimelineDroppedBytes", eventTimelineDroppedBytes)
                    .put("logByteBudgets", JSONObject()
                        .put("runtimeLogsMaxBytes", RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_BYTES)
                        .put("eventTimelineMaxBytes", RunLogBudgetPolicy.MAX_SAMPLE_EVENT_TIMELINE_BYTES)
                        .put("runtimeLogRecordMaxBytes", RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_RECORD_BYTES)
                        .put("eventTimelineRecordMaxBytes", RunLogBudgetPolicy.MAX_SAMPLE_RECORD_BYTES))
                    .put("nodeTreeCaptureCount", nodeTreeStore.captureCount)
                    .put("nodeTreeCaptureMaxSamples", nodeTreeStore.maxSamples)
                    .put("nodeTreeCaptureKeyPageCount", nodeTreeStore.keyPageCount)
                    .put("nodeTreeCaptureStandardCount", nodeTreeStore.standardCount)
                    .put("nodeTreeCaptureStandardMaxSamples", RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES)
                    .put("nodeTreeCaptureStandardQuotaSkippedCount", nodeTreeStore.standardQuotaSkippedCount)
                    .put("sayHiPageSampleCount", metadataStore.sayHiSampleCount)
                    .put("nodeTreeCaptureLimitReached", nodeTreeStore.captureLimitReached)
                    .put("nodeTreeWriteFailureCount", nodeTreeStore.writeFailureCount)
                    .put("archiveWriteFailureCount", archiveWriteFailureCount)
                    .put("screenshotWriteFailureCount", screenshotWriteFailureCount)
                    .put("selectorDiagnosticCount", selectorDiagnostics.lineSequence().count { it.isNotBlank() })
                    .put("selectorDiagnosticsDroppedCount", selectorDiagnosticsDroppedCount)
                    .put("captureWarnings", JSONArray().apply { captureWarnings.forEach { warning -> put(warning) } })
                    .put("privacy", JSONObject()
                        .put("eventTextIncluded", false)
                        .put("eventContentDescriptionIncluded", false)
                        .put("nodeTreeTextIncluded", false)
                        .put("sayHiTextFieldsIncluded", metadataStore.sayHiSampleCount > 0)
                        .put("sayHiTextStoredLocalOnly", true)
                        .put("sayHiTextFile", if (metadataStore.sayHiSampleCount > 0) "sayhi_pages.jsonl" else JSONObject.NULL)
                        .put("runtimeLogsUseExistingMonitorLogContent", true))
                    .toString(2),
                Charsets.UTF_8
            )

            if (runFinishedAtMillis == null) {
                File(directory, "run.jsonl").appendText(
                    JSONObject()
                        .put("event", "run_finished")
                        .put("finishedAt", finishedAt)
                        .put("sessionLists", sessionListStore.sampleCount)
                        .put("chatBatches", nextChatBatchIndex - 1)
                        .put("uniqueMessageFingerprints", historySummary.uniqueMessageFingerprintCount)
                        .put("messageObservations", historySummary.observationCount)
                        .put("repeatMessageObservations", historySummary.repeatedObservationCount)
                        .put("aiInputEligibleMessages", historySummary.aiInputEligibleCount)
                        .put("runBlockedDisplayNameIdentityKeyCount", blockedSessionIdentityKeys.size)
                        .put("runtimeLogCount", runtimeLogCount)
                        .put("runtimeLogSequenceCount", runtimeLogSequence)
                        .put("runtimeLogsTruncated", runtimeLogsTruncated)
                        .put("runtimeLogBytes", runtimeLogBytes)
                        .put("runtimeLogDroppedCount", runtimeLogDroppedCount)
                        .put("runtimeLogDroppedBytes", runtimeLogDroppedBytes)
                        .put("runtimeLogTruncatedCount", runtimeLogTruncatedCount)
                        .put("runtimeLogTruncatedBytes", runtimeLogTruncatedBytes)
                        .put("eventTimelineCount", eventTimelineCount)
                        .put("eventTimelineSequenceCount", eventTimelineSequence)
                        .put("eventTimelineTruncated", eventTimelineTruncated)
                        .put("eventTimelineBytes", eventTimelineBytes)
                        .put("eventTimelineDeduplicatedCount", eventTimelineDeduplicatedCount)
                        .put("eventTimelineDroppedCount", eventTimelineDroppedCount)
                        .put("eventTimelineDroppedBytes", eventTimelineDroppedBytes)
                        .put("nodeTreeCaptureCount", nodeTreeStore.captureCount)
                        .put("nodeTreeCaptureKeyPageCount", nodeTreeStore.keyPageCount)
                        .put("nodeTreeCaptureStandardCount", nodeTreeStore.standardCount)
                        .put("nodeTreeCaptureStandardQuotaSkippedCount", nodeTreeStore.standardQuotaSkippedCount)
                        .put("sayHiPageSampleCount", metadataStore.sayHiSampleCount)
                        .put("nodeTreeCaptureLimitReached", nodeTreeStore.captureLimitReached)
                        .put("nodeTreeWriteFailureCount", nodeTreeStore.writeFailureCount)
                        .put("archiveWriteFailureCount", archiveWriteFailureCount)
                        .put("screenshotWriteFailureCount", screenshotWriteFailureCount)
                        .put("selectorDiagnosticsDroppedCount", selectorDiagnosticsDroppedCount)
                        .toString() + "\n",
                    Charsets.UTF_8
                )
                runFinishedAtMillis = finishedAt
            }

            val zip = archivePort.packageVerifiedArchive(
                runDirectory = directory,
                finishedAtEpochMs = finishedAt,
                warnings = captureWarnings
            )

            // Keep the owner marker until the verified ZIP is published and the source directory
            // is removed. A process death at any earlier point remains discoverable on next start.
            check(directory.deleteRecursively() || !directory.exists()) { "采集 ZIP 已验证但原始目录清理失败" }
            archivePort.removeOwnerMarker(File(rootDirectory, "runs"), directory.name)
            runDirectory = null
            diagnosticRunId = null
            runAcceptingRecords = false
            conversationMessageIndex.clear()
            blockedSessionIdentityKeys.clear()
            imageStore.clearHistory()
            val hasCaptureWarnings = captureWarnings.isNotEmpty()
            diagnostics.recordStructured(
                eventType = "sample_archive_summary",
                category = if (hasCaptureWarnings) com.freedom.core.logging.RunLogCategory.DATA_QUALITY
                    else com.freedom.core.logging.RunLogCategory.COLLECTION,
                level = when {
                    archiveWriteFailureCount > 0 || nodeTreeStore.writeFailureCount > 0 || screenshotWriteFailureCount > 0 ->
                        com.freedom.core.logging.RunLogLevel.ERROR
                    hasCaptureWarnings -> com.freedom.core.logging.RunLogLevel.WARN
                    else -> com.freedom.core.logging.RunLogLevel.INFO
                },
                component = "MomoChatSessionSampleStore",
                message = "AI采集归档统计；runtimeLogs=$runtimeLogCount；runtimeLogBytes=$runtimeLogBytes；" +
                    "runtimeLogDropped=$runtimeLogDroppedCount/$runtimeLogDroppedBytes bytes；" +
                    "runtimeLogTruncated=$runtimeLogTruncatedCount/$runtimeLogTruncatedBytes bytes；" +
                    "eventTimeline=$eventTimelineCount/$eventTimelineBytes bytes；" +
                    "eventTimelineDeduplicated=$eventTimelineDeduplicatedCount；" +
                    "eventTimelineDropped=$eventTimelineDroppedCount/$eventTimelineDroppedBytes bytes；" +
                    "nodeTrees=${nodeTreeStore.captureCount}；keyPageTrees=${nodeTreeStore.keyPageCount}；" +
                    "standardTrees=${nodeTreeStore.standardCount}；standardQuotaSkipped=${nodeTreeStore.standardQuotaSkippedCount}；" +
                    "sayHiSamples=${metadataStore.sayHiSampleCount}；" +
                    "writeFailures=${archiveWriteFailureCount + nodeTreeStore.writeFailureCount + screenshotWriteFailureCount}；" +
                    "captureWarnings=${captureWarnings.joinToString(",")}",
                result = if (hasCaptureWarnings) "SEALED_WITH_WARNINGS" else "SEALED"
            )
            zip.absolutePath
        } catch (error: Exception) {
            // Do not discard the source run on a failed seal. The next finish/begin attempt can retry.
            archiveWriteFailureCount++
            diagnostics.recordException(
                component = "MomoChatSessionSampleStore",
                eventType = "sample_archive_finish_failed",
                message = "AI 会话样本归档封存失败；原始目录保留供重试；runId=${directory.name}",
                error = error
            )
            runCatching { File(directory.parentFile, "${directory.name}.zip").delete() }
            null
        }
    }

    private fun appendEventTimelineRecord(directory: File, record: JSONObject) {
        eventTimelineSequence++
        if (eventTimelineCount >= MAX_EVENT_TIMELINE_ENTRIES) {
            eventTimelineTruncated = true
            eventTimelineDroppedCount++
            eventTimelineDroppedBytes += RunLogBudgetPolicy.utf8Size(record.toString()).toLong() + 1L
            return
        }
        val line = record.toString() + "\n"
        val bytes = RunLogBudgetPolicy.utf8Size(line).toLong()
        if (bytes > RunLogBudgetPolicy.MAX_SAMPLE_RECORD_BYTES ||
            eventTimelineBytes + bytes > RunLogBudgetPolicy.MAX_SAMPLE_EVENT_TIMELINE_BYTES
        ) {
            eventTimelineTruncated = true
            eventTimelineDroppedCount++
            eventTimelineDroppedBytes += bytes
            return
        }
        val timelineFile = File(directory, "event_timeline.jsonl")
        val bytesBefore = timelineFile.length()
        try {
            timelineFile.appendText(line, Charsets.UTF_8)
            eventTimelineCount++
            eventTimelineBytes += bytes
        } catch (error: Exception) {
            val actualWrittenBytes = (timelineFile.length() - bytesBefore).coerceAtLeast(0L)
            eventTimelineBytes += actualWrittenBytes
            eventTimelineTruncated = true
            eventTimelineDroppedCount++
            eventTimelineDroppedBytes += (bytes - actualWrittenBytes).coerceAtLeast(0L)
            archiveWriteFailureCount++
            diagnostics.recordException(
                component = "MomoChatSessionSampleStore",
                eventType = "event_timeline_write_failed",
                message = "AI采集事件时间线写入失败；sequence=$eventTimelineSequence；bytes=$bytes；actualWrittenBytes=$actualWrittenBytes",
                error = error
            )
        }
    }

    private fun flushPendingEventDuplicateSummary(directory: File) {
        val repeatCount = pendingEventDuplicateCount
        if (repeatCount <= 0L) return
        pendingEventDuplicateCount = 0L
        val summary = JSONObject()
            .put("sequence", eventTimelineSequence + 1)
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("eventTimeMillis", lastEventAtMillis)
            .put("type", "EVENT_DEDUP_SUMMARY")
            .put("repeatedEventType", pendingDuplicateType)
            .put("repeatCount", repeatCount)
            .put("deduplicationWindowMs", RunLogBudgetPolicy.DEDUPLICATION_WINDOW_MS)
        pendingDuplicateType = ""
        appendEventTimelineRecord(directory, summary)
    }

    private fun appendJsonLine(name: String, value: JSONObject) {
        val directory = runDirectory ?: return
        archivePort.appendJsonLine(directory, name, value).onFailure { error ->
            archiveWriteFailureCount++
            diagnostics.recordException(
                component = "MomoChatSessionSampleStore",
                eventType = "sample_archive_entry_write_failed",
                message = "AI采集归档条目写入失败；file=$name",
                error = error
            )
        }
    }

    private fun relativeSamplePath(path: String): String = runDirectory?.let { archivePort.relativePath(it, path) } ?: path

}
