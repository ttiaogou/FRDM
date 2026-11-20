package com.freedom.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for Freedom's modular Clean/Hexagonal dependency boundaries. */
class ArchitectureBoundaryTest {
    private val projectRoot: File
        get() = File(System.getProperty("user.dir")).let { start ->
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: start
        }

    private fun projectFile(path: String): File = TestSourceResolver.resolve(projectRoot, path)

    private fun kotlinFiles(relativeDirectory: String): List<File> =
        TestSourceResolver.resolveAll(projectRoot, relativeDirectory)
            .flatMap { directory ->
                if (!directory.exists()) emptyList()
                else if (directory.isFile) listOf(directory)
                else directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            .distinctBy { it.absolutePath }

    // Match verify_boundaries.py: comments and literals are not executable Kotlin code.
    // Keep every newline (and its position) so reported source locations remain stable.
    private val kotlinNonCode = Regex(
        "\"\"\"[\\s\\S]*?\"\"\"|/\\*[\\s\\S]*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
    )

    private fun maskKotlinNonCode(source: String): String = kotlinNonCode.replace(source) { match ->
        buildString(match.value.length) {
            match.value.forEach { character ->
                append(if (character == '\n' || character == '\r') character else ' ')
            }
        }
    }

    private fun imports(file: File): List<String> = file.readLines().mapNotNull { line ->
        line.trim().takeIf { it.startsWith("import ") }
    }

    private fun forbiddenImports(files: List<File>, patterns: List<Regex>): List<String> =
        files.flatMap { file ->
            imports(file).filter { import -> patterns.any { pattern -> pattern.containsMatchIn(import) } }
                .map { "${file.relativeTo(projectRoot)}: $it" }
        }

    @Test
    fun coreHasNoPlatformOrOuterLayerDependencies() {
        val forbidden = forbiddenImports(
            kotlinFiles("app/src/main/java/com/freedom/core"),
            listOf(
                Regex("android\\."), Regex("androidx\\."),
                Regex("com\\.freedom\\.(features|adapters|data|application|accessibility|overlay|ui|stability)\\.")
            )
        )
        assertTrue("Core dependency violations:\n${forbidden.joinToString("\n")}", forbidden.isEmpty())
    }

    @Test
    fun featureCodeIsPlatformFreeAndDependsOnlyOnCoreOrItsOwnDomain() {
        val forbidden = forbiddenImports(
            kotlinFiles("app/src/main/java/com/freedom/features"),
            listOf(
                Regex("android\\."), Regex("androidx\\."),
                Regex("com\\.freedom\\.(adapters|data|application|accessibility|overlay|ui|stability)\\.")
            )
        )
        assertTrue("Feature dependency violations:\n${forbidden.joinToString("\n")}", forbidden.isEmpty())
    }

    @Test
    fun featureCodeDoesNotEmbedMomoPackageOrActivityIdentifiers() {
        val violations = kotlinFiles("app/src/main/java/com/freedom/features")
            .filter { file ->
                val source = file.readText()
                source.contains("com.immomo.momo") ||
                    source.contains("MaintabActivity") ||
                    source.contains("LuaViewActivity") ||
                    source.contains("androidx.recyclerview.widget.RecyclerView") ||
                    Regex("\\bMOMO_[A-Z0-9_]+\\b").containsMatchIn(source)
            }
            .map { it.relativeTo(projectRoot).path }
        assertTrue("Feature code must consume adapter-derived semantic evidence, not Momo identifiers:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun productionMomoIdentityLiteralsAreCentralizedInTheSocialAdapterDescriptor() {
        val descriptor = projectFile("app/src/main/java/com/freedom/adapters/social/momo/MomoAppDescriptor.kt")
        assertTrue("Momo descriptor is required", descriptor.isFile)
        val boundaryFiles = kotlinFiles("app/src/main/java/com/freedom/features") + listOf(
            projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt"),
            projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        )
        val violations = boundaryFiles.filter { it.isFile }
            .filter { Regex("com\\.immomo\\.momo").containsMatchIn(it.readText()) ||
                it.readText().contains("MaintabActivity") || it.readText().contains("LuaViewActivity") }
            .map { it.relativeTo(projectRoot).path }
            .toList()
        assertTrue("Application and feature boundaries must obtain Momo identifiers from the social adapter descriptor: ${violations.joinToString()}", violations.isEmpty())
    }

    @Test
    fun momoPageConfigurationIsOwnedByTheMomoSocialAdapter() {
        val service = projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt")
        val serviceSource = service.readText()
        val forbiddenServiceSymbols = listOf(
            "MomoSocialAdapter.Config",
            "MomoPageInspector.Config",
            "MomoNavigationController.Config",
            "MomoFeedGestureController.Config",
            "MOMO_HOME_NAV_LABELS",
            "MOMO_DYNAMIC_TAB_LABEL",
            "PAGE_FINGERPRINT_MAX_NODES",
            "MIN_DETAIL_NODE_COUNT"
        )
        val leakedSymbols = forbiddenServiceSymbols.filter(serviceSource::contains)
        assertTrue(
            "Momo page configuration must not be assembled by FreedomAccessibilityService: ${leakedSymbols.joinToString()}",
            leakedSymbols.isEmpty()
        )

        val adapter = projectFile("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt")
        val adapterSource = adapter.readText()
        assertTrue("Momo adapter must own its default page configuration", adapterSource.contains("createDefaultConfig()"))
        assertTrue(
            "Momo adapter must retain an instance config initialized from its default factory",
            adapterSource.contains("private val config: Config = createDefaultConfig()")
        )
        assertTrue("Momo adapter must own the dynamic-tab labels", adapterSource.contains("MOMO_DYNAMIC_TAB_LABEL = \"动态\""))
    }

    @Test
    fun gestureCalibrationControllerDependsOnHostContractNotConcreteAccessibilityService() {
        val controller = projectFile("app/src/main/java/com/freedom/accessibility/gesture/GestureCalibrationController.kt")
        val controllerSource = controller.readText()
        assertFalse(
            "GestureCalibrationController must not depend on FreedomAccessibilityService",
            controllerSource.contains("FreedomAccessibilityService") ||
                controllerSource.contains("import com.freedom.accessibility.FreedomAccessibilityService")
        )
        assertTrue("Controller must consume the host contract", controllerSource.contains("host: GestureCalibrationHost"))

        val hostContract = projectFile("app/src/main/java/com/freedom/accessibility/gesture/GestureCalibrationHost.kt")
        assertTrue("Narrow gesture calibration host contract is required", hostContract.isFile)

        val hostAdapter = projectFile("app/src/main/java/com/freedom/adapters/android/gestures/AndroidGestureCalibrationHost.kt")
        val hostAdapterSource = hostAdapter.readText()
        assertTrue("Android adapter must implement the host contract", hostAdapterSource.contains(": GestureCalibrationHost"))
        assertTrue("Android adapter must own active-window access", hostAdapterSource.contains("service.rootInActiveWindow"))
        assertTrue("Android adapter must delegate to the shared Gesture Policy Pipeline", hostAdapterSource.contains("gesturePolicy.execute(request, listener)"))
        assertFalse("Calibration adapter must not bypass the shared Gesture Policy Pipeline", hostAdapterSource.contains("service.dispatchGesture"))
        assertTrue("Android adapter must own broadcast dispatch", hostAdapterSource.contains("service.sendBroadcast"))

        val service = projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt")
        val serviceSource = service.readText()
        val compositionRoot = projectFile("app/src/main/java/com/freedom/app/AppCompositionRoot.kt").readText()
        assertTrue("Accessibility composition root must create the Android calibration host", compositionRoot.contains("AndroidGestureCalibrationHost("))
        assertTrue("Composition root must inject the unified Gesture Policy Pipeline", compositionRoot.contains("gesturePolicy = gesturePolicy"))
        assertTrue("Service must register the calibration receiver through its registry", serviceSource.contains("serviceReceiverRegistry.register(gestureCalibrationController.receiver"))
        assertTrue("Service must unregister owned receivers through the registry", serviceSource.contains("serviceReceiverRegistry.unregisterAll()"))
        assertTrue("Service must retain controller destruction lifecycle", serviceSource.contains("gestureCalibrationController.onServiceDestroyed()"))
    }

    @Test
    fun gestureExecutorAndVisualizerShareOneValidatedCommandBoundary() {
        val coordinator = projectFile("app/src/main/java/com/freedom/application/gesture/GestureExecutionCoordinator.kt")
        val coordinatorSource = coordinator.readText()
        assertTrue("Gesture coordinator must show the command before dispatch", coordinatorSource.indexOf("visualizer.show(command)") < coordinatorSource.indexOf("dispatcher.dispatchGesture(command.gesture"))
        assertTrue("Gesture coordinator must reject when visualization is unavailable", coordinatorSource.contains("gesture_visualization_unavailable"))
        val validatorSource = projectFile("app/src/main/java/com/freedom/application/gesture/GesturePolicyValidator.kt").readText()
        assertTrue("Shared gesture validator must validate display coordinates", validatorSource.contains("coordinate_outside_display"))

        val pathFactory = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGesturePathFactory.kt")
        assertTrue("Android path factory is required", pathFactory.isFile)
        val dispatcher = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt").readText()
        val overlay = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/GestureTrailOverlay.kt").readText()
        assertTrue("Dispatch must use the shared Android path factory", dispatcher.contains("AndroidGesturePathFactory.create(stroke.commands)"))
        assertTrue("Visualization must use the shared Android path factory", overlay.contains("AndroidGesturePathFactory.create(commands)"))
        assertTrue("Gesture visualization must use accessibility overlay windows", overlay.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse("Gesture visualization must not require SYSTEM_ALERT_WINDOW directly", overlay.contains("canDrawOverlays"))

        val workflow = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt").readText()
        assertTrue("AccountBrowse coordinate gestures must use the shared Gesture Policy Pipeline", workflow.contains("gesturePolicy.execute(gestureRequest)"))
        assertFalse("AccountBrowse workflow must not dispatch raw Android gestures", workflow.contains("accessibilityGateway.dispatchGesture("))
        assertFalse("Calibration must not dispatch raw Android gestures", projectFile("app/src/main/java/com/freedom/accessibility/gesture/GestureCalibrationController.kt").readText().contains("GestureDescription.Builder"))
    }


    @Test
    fun momoSocialAdapterRoutesEveryScreenGestureThroughTheUnifiedExecutor() {
        val adapter = projectFile("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt")
        val source = adapter.readText()
        assertTrue("Momo adapter must receive the shared Gesture Policy Pipeline", source.contains("private val gesturePolicy: GestureApi"))
        assertTrue("Received-greetings entry tap must use the unified executor", source.contains("gesturePolicy.execute(tapRequest"))
        assertTrue("Received-greetings list swipe must use the unified executor", source.contains("gesturePolicy.execute(request"))
        assertFalse("Momo adapter must not bypass serialization and visualization", source.contains("accessibilityGateway.dispatchGesture("))
        assertFalse("Momo adapter must not retain a raw platform gesture callback", source.contains("GestureResultListener"))

        val compositionRoot = projectFile("app/src/main/java/com/freedom/app/AppCompositionRoot.kt").readText()
        assertTrue("Composition root must create Momo adapter and inject the single shared Gesture API", compositionRoot.contains("MomoSocialAdapter(") && compositionRoot.contains("gesturePolicy = gesturePolicy"))
    }

    @Test
    fun momoProfileBackRebuildsRowTargetAndRebindsCurrentWindowGenerationOnlyAfterRevalidation() {
        val adapter = projectFile("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt")
        val source = adapter.readText()
        val profileBackBranch = source.substringAfter(
            "if (sayHiReadOnlyStage == SayHiReadOnlyStage.PROFILE_BACK_PENDING) {"
        ).substringBefore("if (sayHiReadOnlyStage != SayHiReadOnlyStage.IDLE) return true")

        val pageProof = profileBackBranch.indexOf("inspection.kind != MomoSayHiPageKind.RECEIVED_GREETINGS_LIST")
        val rowIdentityProof = profileBackBranch.indexOf("val matches = inspection.listRows.filter { MomoSayHiIdentityFingerprintPolicy.rowKey(it) == selected.rowKey }")
        val platformIdentityProof = profileBackBranch.indexOf("val resolvedContactId = sayHiSelectedContactIdentity")
        val targetRebuild = profileBackBranch.indexOf("MomoSayHiAvatarTargetPolicy.resolveRowTarget(")
        val generationRebind = profileBackBranch.indexOf("sourceWindow = tree.window")
        val rowDispatch = profileBackBranch.indexOf("dispatchSayHiReadOnlyRowTap(tree, row, target)")

        assertTrue("Profile return must reconfirm the received-greetings page", pageProof >= 0)
        assertTrue("Original row identity must be uniquely reacquired", rowIdentityProof > pageProof)
        assertTrue("Platform identity must be confirmed before rebinding", platformIdentityProof > rowIdentityProof)
        assertTrue("The row click target must be rebuilt from the current snapshot", targetRebuild > platformIdentityProof)
        assertTrue("Only the current snapshot may supply the rebound window generation", generationRebind > targetRebuild)
        assertTrue("Dispatch must follow all revalidation and rebinding", rowDispatch > generationRebind)
        assertFalse("Profile return must not reuse the row's retained tap target", profileBackBranch.contains("row.rowTapTarget"))

        val validation = source.substringAfter("private fun revalidateSayHiRowTarget(")
            .substringBefore("private fun containsBounds(")
        assertTrue(
            "The final safety validation must bind the structure fingerprint to the same snapshot",
            validation.contains("selected.sourceStructureFingerprint != MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(tree)")
        )
    }

    @Test
    fun allAutomatedScreenTapsAndScrollsUseCoordinateGesturesOnly() {
        val productionFiles = kotlinFiles("app/src/main/java/com/freedom")
        val forbiddenTouchAction = Regex(
            "(?:AccessibilityNodeInfo\\.)?ACTION_(?:CLICK|LONG_CLICK|SCROLL_FORWARD|SCROLL_BACKWARD)"
        )
        val forbiddenNodeAction = Regex(
            "NodeAction\\.(?:CLICK|LONG_CLICK|SCROLL_FORWARD|SCROLL_BACKWARD)"
        )
        val forbiddenCalls = Regex(
            "performAction\\s*\\(\\s*(?:AccessibilityNodeInfo\\.)?ACTION_(?:CLICK|LONG_CLICK|SCROLL_FORWARD|SCROLL_BACKWARD)"
        )
        val violations = productionFiles.flatMap { file ->
            val source = file.readText()
            buildList {
                if (forbiddenTouchAction.containsMatchIn(source)) add("${file.relativeTo(projectRoot)}: Android touch Action")
                if (forbiddenNodeAction.containsMatchIn(source)) add("${file.relativeTo(projectRoot)}: NodeAction touch enum")
                if (forbiddenCalls.containsMatchIn(source)) add("${file.relativeTo(projectRoot)}: performAction touch call")
                if (source.contains("performClickAndRecycle")) add("${file.relativeTo(projectRoot)}: legacy node click helper")
                if (source.contains(".performAction(") && file.name != "AndroidAccessibilityGateway.kt") {
                    add("${file.relativeTo(projectRoot)}: raw AccessibilityNodeInfo.performAction bypass")
                }
            }
        }
        assertTrue("Screen taps and scrolls must not use node actions:\n${violations.joinToString("\n")}", violations.isEmpty())

        val actionContract = projectFile("app/src/main/java/com/freedom/core/accessibility/api/AccessibilityGateway.kt").readText()
        val nodeActionBody = actionContract.substringAfter("enum class NodeAction {").substringBefore("}")
        assertTrue("Only non-touch semantic node actions may remain", nodeActionBody.contains("FOCUS") &&
            nodeActionBody.contains("SET_TEXT") && nodeActionBody.contains("DISMISS"))
        assertFalse("NodeAction must not expose tap or scroll actions", forbiddenNodeAction.containsMatchIn(nodeActionBody))

        val workflow = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt").readText()
        assertTrue("AccountBrowse coordinate operations must use Gesture Policy Pipeline", workflow.contains("gesturePolicy.execute("))
        assertFalse("AccountBrowse must not call node click or scroll actions", Regex("NodeAction\\.(?:CLICK|LONG_CLICK|SCROLL_FORWARD|SCROLL_BACKWARD)").containsMatchIn(workflow))
        assertTrue("The only remaining node action in AccountBrowse is semantic popup dismiss", workflow.contains("NodeAction.DISMISS"))
        val navigationExecutor = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AccountBrowseNavigationExecutor.kt").readText()
        assertTrue(
            "AccountBrowse taps must intersect preferred, clickable and current display bounds before submission",
            workflow.contains("navigationExecutor.createCoordinateTap(") &&
                navigationExecutor.contains("preferredBounds: ScreenRect?") &&
                navigationExecutor.contains("clickableBounds: ScreenRect?") &&
                navigationExecutor.contains("intersectScreenRects(preferred, clickable, displayBounds) == null") &&
                navigationExecutor.contains("preferredBounds = preferred") &&
                navigationExecutor.contains("targetBounds = clickable")
        )
        assertTrue("Tap continuation must build its stroke only inside the verified safe intersection", projectFile("app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt").readText().contains("GestureGeometryPolicy.createTapCommands(point, targetRegion, random)"))
        assertTrue("Popup coordinate tap must use the close icon/label bounds", workflow.contains("preferredBounds = closeBounds"))
        assertTrue("Popup coordinate tap must validate against its clickable ancestor bounds", workflow.contains("clickableBounds = closeTargetBounds"))

        val gateway = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt").readText()
        assertTrue("Accessibility Gateway must delegate gesture dispatch to the Android Gesture Adapter", gateway.contains("gestureDispatcher.dispatchGesture(spec, listener)"))
        assertFalse("Accessibility Gateway must not call dispatchGesture directly", gateway.contains("service.dispatchGesture("))
        assertTrue("Node performAction must be centralized in one gateway adapter", Regex("\\.performAction\\(").findAll(gateway).count() == 1)
        val rawAndroidDispatchFiles = productionFiles.filter { it.readText().contains("service.dispatchGesture(") }
        assertTrue("Only AndroidGestureDispatcher may call AccessibilityService.dispatchGesture", rawAndroidDispatchFiles.size == 1 && rawAndroidDispatchFiles.single().name == "AndroidGestureDispatcher.kt")
        assertFalse("Workflow must not bypass Gesture Policy Pipeline through gateway dispatch", workflow.contains("accessibilityGateway.dispatchGesture("))

        val overlay = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/GestureTrailOverlay.kt").readText()
        assertTrue("Gesture visualizer must render the submitted command", overlay.contains("showCommand(command, renderedStrokes)"))
        assertFalse("Legacy estimated node-bound click visualization must be removed", overlay.contains("showClick(") || overlay.contains("showLegacyNodeBounds"))
        assertFalse("Legacy standalone swipe visualization must be removed", overlay.contains("fun showSwipe(") || overlay.contains("fun finishSwipe("))
    }

    @Test
    fun kotlinNonCodeMaskingPreservesLinesAndStillFindsExecutableForbiddenSymbols() {
        val tripleQuote = "\"\"\""
        val source = listOf(
            "// GesturePoint() is documentation, not executable code",
            "/* GestureSpec() is also documentation */",
            "val ordinary = \"GestureExecutor()\"",
            "val raw = $tripleQuote GestureGeometryPolicy $tripleQuote",
            "GestureCommand()"
        ).joinToString("\n")

        val masked = maskKotlinNonCode(source)

        assertEquals("Masking must preserve the number of source lines", source.count { it == '\n' }, masked.count { it == '\n' })
        assertFalse(
            "Comments and string literals must not trigger boundary violations",
            Regex("Gesture(Point|Spec|Executor|GeometryPolicy)\\b").containsMatchIn(masked)
        )
        assertTrue(
            "Executable forbidden symbols must remain visible to the boundary scan",
            Regex("\\bGestureCommand\\b").containsMatchIn(masked)
        )
    }

    @Test
    fun businessModulesCanOnlySubmitGestureIntentThroughThePolicyPipeline() {
        val businessFiles = buildList {
            addAll(kotlinFiles("business/src/main/kotlin/com/freedom/features"))
            add(projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"))
            add(projectFile("app/src/main/java/com/freedom/accessibility/gesture/GestureCalibrationController.kt"))
            add(projectFile("app/src/main/java/com/freedom/adapters/android/gestures/AndroidGestureCalibrationHost.kt"))
        }.filter { it.isFile }
        val forbidden = listOf(
            Regex("\\bGestureCommand\\b"),
            Regex("\\bGesturePoint\\b"),
            Regex("\\bGesturePathCommand\\b"),
            Regex("\\bGestureStrokeSpec\\b"),
            Regex("\\bGestureSpec\\b"),
            Regex("\\bGestureDispatchPurpose\\b"),
            Regex("\\bGestureExecutor\\b"),
            Regex("\\bInterruptibleGestureExecutor\\b"),
            Regex("\\bGestureGeometryPolicy\\b"),
            Regex("\\.dispatchGesture\\s*\\(")
        )
        val violations = businessFiles.flatMap { file ->
            val code = maskKotlinNonCode(file.readText())
            forbidden.filter { it.containsMatchIn(code) }
                .map { "${file.relativeTo(projectRoot)}: ${it.pattern}" }
        }
        assertTrue(
            "Business modules must submit GestureIntentRequest only; raw gesture construction or dispatch is forbidden:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )

        val pipelineFile = projectFile("app/src/main/java/com/freedom/application/gesture/GesturePolicyPipeline.kt")
        val pipeline = pipelineFile.readText()
        val compilerFile = projectFile("app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt")
        val compiler = compilerFile.readText()
        assertTrue("Pipeline must validate raw requests before policy resolution", pipeline.contains("validator.validateRequest(request)"))
        assertTrue("Pipeline must select policy before command compilation", pipeline.contains("commandCompiler.compile(validatedRequest, policyDecision)"))
        assertTrue("Compiler must accept only a validated request token", compiler.contains("validatedRequest: ValidatedGestureRequest"))
        assertTrue("Compiler must own GestureCommand construction", compiler.contains("GestureCommand("))
        assertTrue("Pipeline must issue approval after geometry validation", pipeline.contains("validator.validateAndApprove(command)"))
        assertTrue("Pipeline must pass only an approved command to the executor", pipeline.contains("executor.execute(approvedCommand, listener)"))
        assertFalse("Pipeline must not directly construct platform gesture specs", pipeline.contains("GestureSpec("))
        val requestValidationIndex = pipeline.indexOf("validator.validateRequest(request)")
        val contextValidationIndex = pipeline.indexOf("validateExecutionContext(validatedIntentRequest.executionContext)")
        val policySelectionIndex = pipeline.indexOf("policyManager.select(validatedIntentRequest)")
        val compilationIndex = pipeline.indexOf("commandCompiler.compile(validatedRequest, policyDecision)")
        val approvalIndex = pipeline.indexOf("validator.validateAndApprove(command)")
        val executionIndex = pipeline.indexOf("executor.execute(approvedCommand, listener)")
        assertTrue("Runtime stages must preserve validate -> context -> policy -> compile -> approve -> execute order",
            requestValidationIndex >= 0 && requestValidationIndex < contextValidationIndex &&
                contextValidationIndex < policySelectionIndex && policySelectionIndex < compilationIndex &&
                compilationIndex < approvalIndex && approvalIndex < executionIndex)
        val runtimeBuild = projectFile("gesture/gesture-runtime/build.gradle.kts").readText()
        assertTrue(":gesture-runtime must compile the dedicated GestureCommandCompiler", runtimeBuild.contains("GestureCommandCompiler.kt"))
        assertFalse(":app must not retain a duplicate GestureCommandCompiler source", projectFile("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { it.name == "GestureCommandCompiler.kt" })

        val sourceFiles = kotlinFiles("app/src/main/java/com/freedom")
        val trustedGestureInfrastructure = setOf(
            "app/src/main/java/com/freedom/application/gesture/GestureExecutionCoordinator.kt",
            "app/src/main/java/com/freedom/application/gesture/GesturePolicyPipeline.kt",
            "app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt",
            "app/src/main/java/com/freedom/application/gesture/GesturePolicyValidator.kt",
            "app/src/main/java/com/freedom/application/gesture/GestureValidationTypes.kt",
            "app/src/main/java/com/freedom/core/accessibility/api/AccessibilityGateway.kt",
            "app/src/main/java/com/freedom/core/accessibility/api/GestureExecutionPort.kt",
            "app/src/main/java/com/freedom/core/accessibility/events/InputOriginClassifier.kt",
            "app/src/main/java/com/freedom/core/accessibility/gesture/GestureCoordinateRegion.kt",
            "app/src/main/java/com/freedom/core/accessibility/gesture/GestureCommand.kt",
            "app/src/main/java/com/freedom/core/accessibility/gesture/GestureGeometryPolicy.kt",
            "app/src/main/java/com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt",
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt",
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGesturePathFactory.kt",
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGestureVisualizer.kt",
            "app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt",
            "app/src/main/java/com/freedom/adapters/accessibility/android/GestureTrailOverlay.kt"
        )
        val rawGestureApi = listOf(
            Regex("\\bGestureCommand\\b"),
            Regex("\\bGesturePoint\\b"),
            Regex("\\bGesturePathCommand\\b"),
            Regex("\\bGestureStrokeSpec\\b"),
            Regex("\\bGestureSpec\\b"),
            Regex("\\bGestureDispatchPurpose\\b"),
            Regex("\\bGestureExecutor\\b"),
            Regex("\\bInterruptibleGestureExecutor\\b"),
            Regex("\\bGestureGeometryPolicy\\b"),
            Regex("\\.dispatchGesture\\s*\\(")
        )
        val unauthorizedGestureApi = sourceFiles.filter { file ->
            file.relativeTo(projectRoot).invariantSeparatorsPath !in trustedGestureInfrastructure
        }.flatMap { file ->
            val code = maskKotlinNonCode(file.readText())
            rawGestureApi.filter { it.containsMatchIn(code) }
                .map { "${file.relativeTo(projectRoot)}: ${it.pattern}" }
        }
        assertTrue(
            "Raw gesture construction and dispatch APIs are restricted to the trusted gesture infrastructure:\n${unauthorizedGestureApi.joinToString("\n")}",
            unauthorizedGestureApi.isEmpty()
        )

        val rawCommandConstruction = sourceFiles.filter { file ->
            file != compilerFile && file.name != "GestureCommand.kt" &&
                Regex("\\bGestureCommand\\s*\\(").containsMatchIn(maskKotlinNonCode(file.readText()))
        }
        assertTrue("Only GestureCommandCompiler may construct production GestureCommand values: ${rawCommandConstruction.map { it.relativeTo(projectRoot) }}", rawCommandConstruction.isEmpty())
        val rawSpecConstruction = sourceFiles.filter { file ->
            file != compilerFile && file.name != "AccessibilityGateway.kt" &&
                Regex("\\bGestureSpec\\s*\\(").containsMatchIn(maskKotlinNonCode(file.readText()))
        }
        assertTrue("Only GestureCommandCompiler may construct production GestureSpec values: ${rawSpecConstruction.map { it.relativeTo(projectRoot) }}", rawSpecConstruction.isEmpty())
        val rawDispatch = sourceFiles.filter { file ->
            file.name != "AndroidGestureDispatcher.kt" &&
                Regex("service\\.dispatchGesture\\s*\\(").containsMatchIn(maskKotlinNonCode(file.readText()))
        }
        assertTrue("Only AndroidGestureDispatcher may call AccessibilityService.dispatchGesture: ${rawDispatch.map { it.relativeTo(projectRoot) }}", rawDispatch.isEmpty())

        val contract = projectFile("app/src/main/java/com/freedom/core/accessibility/api/AccessibilityGateway.kt").readText()
        val accessibilityGateway = contract.substringAfter("interface AccessibilityGateway :")
            .substringBefore("\n}")
        assertFalse("Feature-facing AccessibilityGateway must not expose GestureExecutionPort", accessibilityGateway.contains("GestureExecutionPort"))
    }


    @Test
    fun gestureCoordinatorOwnsOneBoundedFifoExecutionQueue() {
        val coordinator = projectFile("app/src/main/java/com/freedom/application/gesture/GestureExecutionCoordinator.kt").readText()
        assertTrue("Gesture coordinator must own a FIFO pending queue", coordinator.contains("ArrayDeque<ActiveExecution>"))
        assertTrue("Gesture coordinator must bound pending queue admission", coordinator.contains("pendingExecutions.size >= maxPendingCommands"))
        assertTrue("Gesture coordinator must promote only the queue head", coordinator.contains("pendingExecutions.removeFirst()"))
        assertTrue("Queued commands must be revalidated before execution", coordinator.contains("queued_or_delayed_execution_context_invalid"))
        assertTrue("Queued commands must be cancellable by command ID", coordinator.contains("cancelled_while_queued"))
        assertTrue("Old-page queued commands must be invalidated", coordinator.contains("queued_command_invalidated"))
        assertTrue("A timeout must not release the Android platform slot", coordinator.contains("等待迟到的平台终态回调；禁止提交新手势"))
        assertTrue("Cancellation during visualization must wait for show() to return", coordinator.contains("DEFER_CANCEL_UNTIL_VISUALIZATION_RETURNS"))
        assertTrue("Visualization lifecycle must be tracked while owning the active slot", coordinator.contains("visualizationInProgress"))
        assertTrue("Dispatcher deferral must settle a racing cancellation explicitly", coordinator.contains("DeferredSettlement"))
    }

    @Test
    fun gestureExecutionRequiresRuntimeOnlyValidatedAndApprovedTypes() {
        val validationTypes = projectFile("app/src/main/java/com/freedom/application/gesture/GestureValidationTypes.kt").readText()
        val commandSource = projectFile("app/src/main/java/com/freedom/core/accessibility/gesture/GestureCommand.kt").readText()
        val validatorSource = projectFile("app/src/main/java/com/freedom/application/gesture/GesturePolicyValidator.kt").readText()
        val coordinatorSource = projectFile("app/src/main/java/com/freedom/application/gesture/GestureExecutionCoordinator.kt").readText()
        val runtimeBuild = projectFile("gesture/gesture-runtime/build.gradle.kts").readText()

        assertTrue(validationTypes.contains("internal class ValidatedGestureRequest private constructor"))
        assertTrue(validationTypes.contains("internal class ApprovedGestureCommand private constructor"))
        assertTrue(commandSource.contains("class GestureCommand internal constructor"))
        assertFalse("GestureCommand must not expose data-class copy()", commandSource.contains("data class GestureCommand"))
        assertTrue(validatorSource.contains("ValidatedGestureRequest.issueAfterValidation(request)"))
        assertTrue(validatorSource.contains("ApprovedGestureCommand.issueAfterValidation(command)"))
        assertTrue(coordinatorSource.contains("approvedCommand: ApprovedGestureCommand"))
        assertTrue(runtimeBuild.contains("GestureValidationTypes.kt"))

        val productionFiles = kotlinFiles("app/src/main/java/com/freedom")
        val nonValidatorIssuers = productionFiles.filter { file ->
            val code = maskKotlinNonCode(file.readText())
            file.name != "GesturePolicyValidator.kt" &&
                (code.contains("ValidatedGestureRequest.issueAfterValidation(") ||
                    code.contains("ApprovedGestureCommand.issueAfterValidation("))
        }
        assertTrue("Only the validator may issue trusted gesture tokens: ${nonValidatorIssuers.map { it.relativeTo(projectRoot) }}", nonValidatorIssuers.isEmpty())

        val untrustedFiles = (kotlinFiles("app/src/main/java/com/freedom/features") +
            kotlinFiles("app/src/main/java/com/freedom/adapters"))
        val trustedTypeReferences = untrustedFiles.filter { file ->
            Regex("\\b(ValidatedGestureRequest|ApprovedGestureCommand)\\b")
                .containsMatchIn(maskKotlinNonCode(file.readText()))
        }
        assertTrue("Business and adapter code must not reference Runtime validation capabilities: ${trustedTypeReferences.map { it.relativeTo(projectRoot) }}", trustedTypeReferences.isEmpty())
    }

    @Test
    fun gestureGradleModulesRespectTheDeclaredDependencyDirection() {
        val expectedDependencies = mapOf(
            "business/build.gradle.kts" to setOf(":core", ":gesture-api"),
            "core/build.gradle.kts" to emptySet(),
            "gesture/gesture-api/build.gradle.kts" to setOf(":core"),
            "gesture/gesture-core/build.gradle.kts" to setOf(":core"),
            "gesture/gesture-runtime/build.gradle.kts" to setOf(":core", ":gesture-api", ":gesture-core"),
            "gesture/gesture-android/build.gradle.kts" to setOf(":core", ":gesture-runtime")
        )
        expectedDependencies.forEach { (path, expected) ->
            val source = projectFile(path).readText()
            val actual = Regex("project\\(\\s*\":([^\"]+)\"\\s*\\)")
                .findAll(source)
                .map { ":${it.groupValues[1]}" }
                .toSet()
            assertTrue("$path dependencies must be $expected, found $actual", actual == expected)
        }

        val runtimeBuild = projectFile("gesture/gesture-runtime/build.gradle.kts").readText()
        assertTrue("Runtime must keep gesture-core as implementation", runtimeBuild.contains("implementation(project(\":gesture-core\"))"))
        assertFalse("Runtime must not export gesture-core algorithms", runtimeBuild.contains("api(project(\":gesture-core\"))"))
        val settings = projectFile("settings.gradle.kts").readText()
        listOf(":app", ":business", ":core", ":gesture-api", ":gesture-core", ":gesture-runtime", ":gesture-android")
            .forEach { module -> assertTrue("Gradle module must be included: $module", settings.contains("\"$module\"")) }
    }

    @Test
    fun gestureApiIsTheOnlyPublicGestureEntryPoint() {
        val sourceRoot = projectFile("app/src/main/java/com/freedom")
        val productionFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList() + projectFile("gesture/gesture-api/src/main/kotlin/com/freedom/gesture/api/GestureApi.kt")
        val declarations = productionFiles.filter { Regex("\\binterface\\s+GestureApi\\b").containsMatchIn(it.readText()) }
        assertTrue("GestureApi must have one declaration in :gesture-api", declarations.size == 1 &&
            declarations.single().relativeTo(projectRoot).invariantSeparatorsPath ==
            "gesture/gesture-api/src/main/kotlin/com/freedom/gesture/api/GestureApi.kt")
        val legacyDeclarations = productionFiles.filter {
            Regex("\\binterface\\s+(?:GesturePolicyPort|InterruptibleGesturePolicyPort)\\b").containsMatchIn(it.readText())
        }
        assertTrue("Legacy public gesture ports must not be redeclared", legacyDeclarations.isEmpty())

        val featureFiles = kotlinFiles("app/src/main/java/com/freedom/features")
        val privateRuntimeTypes = Regex("\\b(?:GestureCommand|GestureSpec|GestureExecutor|ValidatedGestureRequest|ApprovedGestureCommand|GestureGeometryPolicy)\\b")
        val leaks = featureFiles.filter { privateRuntimeTypes.containsMatchIn(it.readText()) }
        assertTrue("Feature code must not reference Runtime gesture internals: ${leaks.map { it.relativeTo(projectRoot) }}", leaks.isEmpty())
    }

    @Test
    fun androidPlatformGestureDispatchIsConfinedToOneAdapter() {
        val productionFiles = listOf(
            projectFile("app/src/main/java/com/freedom"),
            projectFile("gesture/gesture-android/src/main/kotlin/com/freedom")
        ).flatMap { sourceRoot ->
            sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val platformCalls = productionFiles.filter {
            Regex("\\bservice\\s*\\.\\s*dispatchGesture\\s*\\(").containsMatchIn(it.readText())
        }
        assertTrue(
            "AccessibilityService.dispatchGesture must only be called in AndroidGestureDispatcher: ${platformCalls.map { it.relativeTo(projectRoot) }}",
            platformCalls.size == 1 && platformCalls.single().name == "AndroidGestureDispatcher.kt"
        )
        val compiler = projectFile("app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt")
        val unauthorizedCommands = productionFiles.filter {
            it != compiler && it.name != "GestureCommand.kt" && Regex("\\bGestureCommand\\s*\\(").containsMatchIn(it.readText())
        }
        assertTrue("Only GestureCommandCompiler may construct GestureCommand", unauthorizedCommands.isEmpty())
    }

    @Test
    fun ciRunsTheArchitectureGateBeforeAndroidBuild() {
        val workflow = projectFile(".github/workflows/android-build.yml").readText()
        val jobsSection = workflow.substringAfter("jobs:", missingDelimiterValue = "")
        assertTrue("CI workflow must declare jobs", jobsSection.isNotBlank())

        val jobIds = Regex("(?m)^  ([A-Za-z0-9_-]+):\\s*$")
            .findAll(jobsSection)
            .map { it.groupValues[1] }
            .toList()
        assertEquals("CI must use one job for architecture checks and the Android build", listOf("ci"), jobIds)

        val verifierUnitTests = workflow.indexOf("python3 -m unittest discover -s tools/architecture")
        val architectureGate = workflow.indexOf("python3 tools/architecture/verify_boundaries.py")
        // The cache strategy is an implementation detail. The ordering contract is that the
        // Gradle build invocation itself remains after both architecture checks.
        val androidBuild = workflow.indexOf("gradle --no-daemon")
        assertTrue("CI must run verifier unit tests", verifierUnitTests >= 0)
        assertTrue("CI must run the static architecture verifier", architectureGate >= 0)
        assertTrue("Architecture verification must finish before the Android Gradle build", verifierUnitTests < architectureGate && architectureGate < androidBuild)

        assertTrue("Android build must execute the core JVM module tests", workflow.contains(":core:test"))
        assertTrue("Android build must execute the business JVM module tests", workflow.contains(":business:test"))
        assertTrue("Android build must execute the gesture-core JVM module tests", workflow.contains(":gesture-core:test"))
        assertTrue("Android build must execute the gesture-runtime JVM module tests", workflow.contains(":gesture-runtime:test"))
        assertTrue("Android build must execute app architecture tests", workflow.contains(":app:testDebugUnitTest"))
        assertTrue("CI must build the Freedom debug APK", workflow.contains(":app:assembleDebug"))
        assertTrue("CI must build the OCR plugin debug APK", workflow.contains(":ocr-plugin:assembleDebug"))
    }

    @Test
    fun removedLegacyDuplicateImplementationsStayAbsent() {
        val removedPaths = listOf(
            "app/src/main/java/com/freedom/accessibility/chat/momo/MomoChatPageInspector.kt",
            "app/src/main/java/com/freedom/accessibility/chat/momo/MomoChatPageModels.kt",
            "app/src/main/java/com/freedom/data/MonitorActions.kt",
            "app/src/main/java/com/freedom/core/accessibility/AccessibilityEventRouter.kt",
            "app/src/main/java/com/freedom/core/runtime/RunCoordinator.kt",
            "app/src/test/java/com/freedom/accessibility/chat/momo/MomoChatPageClassifierTest.kt",
            "app/src/test/java/com/freedom/runtime/RunModeTest.kt",
            "app/src/test/java/com/freedom/core/accessibility/AccessibilityEventRouterTest.kt",
            "app/src/test/java/com/freedom/core/runtime/RunCoordinatorTest.kt"
        )
        val present = removedPaths.filter { projectFile(it).exists() }
        assertTrue("Legacy duplicate implementations must be removed: $present", present.isEmpty())
    }

    @Test
    fun applicationLayerHasNoAndroidOrConcreteAdapterDataDependencies() {
        val forbidden = forbiddenImports(
            kotlinFiles("app/src/main/java/com/freedom/application"),
            listOf(
                Regex("android\\."), Regex("androidx\\."),
                Regex("com\\.freedom\\.(adapters|data|accessibility|overlay|ui|stability)\\.")
            )
        )
        assertTrue("Application dependency violations:\n${forbidden.joinToString("\n")}", forbidden.isEmpty())
    }

    @Test
    fun monitorStoreImplementationIsConfinedToDataAdapters() {
        val sourceRoot = projectFile("app/src/main/java/com/freedom")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.toPath().startsWith(projectFile("app/src/main/java/com/freedom/data").toPath()) }
            .filter { Regex("\\bMonitorStore\\s*\\.\\s*[A-Za-z_]").containsMatchIn(it.readText()) }
            .map { it.relativeTo(projectRoot).path }
            .toList()
        assertTrue("MonitorStore must be accessed only inside Data:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun sharedPreferencesAccessIsConfinedToDataAdapters() {
        val sourceRoot = projectFile("app/src/main/java/com/freedom")
        val dataRoot = projectFile("app/src/main/java/com/freedom/data")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.toPath().startsWith(dataRoot.toPath()) }
            .filter {
                val source = it.readText()
                source.contains("getSharedPreferences(") ||
                    source.contains("SharedPreferences.Editor") ||
                    source.contains("android.content.SharedPreferences")
            }
            .map { it.relativeTo(projectRoot).path }
            .toList()
        assertTrue("SharedPreferences access must be confined to Data:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun presentationAndBusinessFeaturesDependOnRepositoryPortsRatherThanDataImplementation() {
        val forbidden = forbiddenImports(
            kotlinFiles("app/src/main/java/com/freedom/ui") +
                kotlinFiles("app/src/main/java/com/freedom/features"),
            listOf(Regex("com\\.freedom\\.data\\."))
        )
        assertTrue("UI/Feature must not depend on concrete Data classes:\n${forbidden.joinToString("\n")}", forbidden.isEmpty())
    }

    @Test
    fun accountBrowseFeatureNeverExposesAndroidNodeHandlesOrServiceWorkflow() {
        val featureFiles = kotlinFiles("app/src/main/java/com/freedom/features/accountbrowse")
        val leaks = featureFiles.filter { file ->
            val source = file.readText()
            source.contains("AccessibilityNodeInfo") ||
                source.contains("android.graphics.Rect") ||
                source.contains("android.os.Handler") ||
                source.contains("import android.") ||
                source.contains("import androidx.")
        }.map { it.relativeTo(projectRoot).path }
        assertTrue("AccountBrowse Feature must not expose Android handles/services: ${leaks.joinToString()}", leaks.isEmpty())
        assertFalse(
            "Android AccountBrowse workflow must stay outside Feature",
            projectFile("app/src/main/java/com/freedom/features/accountbrowse/android/AndroidAccountBrowseWorkflow.kt").exists()
        )
        val legacyBridge = projectFile("app/src/main/java/com/freedom/adapters/social/momo/AccountBrowseAndroidPort.kt")
        assertFalse("Legacy AccountBrowseAndroidPort compatibility bridge must be removed", legacyBridge.exists())

        val workflow = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt").readText()
        assertTrue("AccountBrowse workflow must consume SocialBrowsePort", workflow.contains("private val socialBrowsePort: SocialBrowsePort"))
        assertTrue("AccountBrowse workflow must consume AccessibilityGateway", workflow.contains("private val accessibilityGateway: AccessibilityGateway"))
        assertFalse("AccountBrowse workflow must not access Android AccessibilityNodeInfo", workflow.contains("AccessibilityNodeInfo"))
        assertFalse("AccountBrowse workflow must not depend on Android AccessibilityService", workflow.contains("AccessibilityService"))
        assertFalse("AccountBrowse workflow must not acquire the active root directly", workflow.contains("rootInActiveWindow"))
        val momoAdapter = projectFile("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt").readText()
        assertFalse("Momo adapter must not implement the removed legacy AccountBrowse port", momoAdapter.contains("AccountBrowseAndroidPort"))
        val pageInspection = momoAdapter.substringAfter("override fun inspectPage(): BrowsePageEvidence =")
            .substringBefore("override fun inspectDynamicTab():")
        assertTrue(
