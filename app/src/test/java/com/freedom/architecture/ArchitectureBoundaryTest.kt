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
        ) + setOf(
            "core/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureCoordinateRegion.kt",
            "core/src/main/kotlin/com/freedom/core/accessibility/events/InputOriginClassifier.kt",
            "core/src/main/kotlin/com/freedom/core/accessibility/api/AccessibilityGateway.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureCommand.kt",
            "gesture/gesture-core/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureCoordinateRegion.kt",
            "gesture/gesture-core/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureCommand.kt",
            "gesture/gesture-core/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureGeometryPolicy.kt",
            "gesture/gesture-core/src/main/kotlin/com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GestureExecutionCoordinator.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GesturePolicyPipeline.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GestureCommandCompiler.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GesturePolicyValidator.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/application/gesture/GestureValidationTypes.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/core/accessibility/api/AccessibilityGateway.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/core/accessibility/api/GestureExecutionPort.kt",
            "gesture/gesture-runtime/src/main/kotlin/com/freedom/core/accessibility/events/InputOriginClassifier.kt",
            "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/android/AndroidGesturePathFactory.kt",
            "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/android/AndroidGestureVisualizer.kt",
            "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt",
            "gesture/gesture-android/src/main/kotlin/com/freedom/adapters/accessibility/android/GestureTrailOverlay.kt"
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
        assertEquals(
            "CI must keep the architecture gate, test, both APK builds and CodeQL as five jobs",
            listOf("arch-guard", "test", "build-freedom-apk", "build-ocr-plugin-apk", "codeql"),
            jobIds
        )

        fun jobBody(jobId: String): String {
            val marker = "\n  $jobId:\n"
            val start = jobsSection.indexOf(marker)
            assertTrue("CI must define job $jobId", start >= 0)
            val remainder = jobsSection.substring(start + marker.length)
            val nextJob = Regex("(?m)^  [A-Za-z0-9_-]+:\\s*$").find(remainder)
            return remainder.substring(0, nextJob?.range?.first ?: remainder.length)
        }

        val architectureJob = jobBody("arch-guard")
        val verifierUnitTests = architectureJob.indexOf("python3 -m unittest discover -s tools/architecture")
        val architectureGate = architectureJob.indexOf("python3 tools/architecture/verify_boundaries.py")
        assertTrue("CI must run verifier unit tests", verifierUnitTests >= 0)
        assertTrue("CI must run the static architecture verifier", architectureGate >= 0)
        assertTrue(
            "Both architecture checks must execute in the gate before any Android build",
            verifierUnitTests < architectureGate && !architectureJob.contains("gradle --no-daemon")
        )
        assertTrue("Architecture gate must validate Android XML resources", architectureJob.contains("ET.parse(path)"))

        for (jobId in listOf("test", "build-freedom-apk", "build-ocr-plugin-apk")) {
            val body = jobBody(jobId)
            assertTrue(
                "$jobId must wait for the full architecture and resource gate",
                Regex("(?m)^    needs: arch-guard\\s*$").containsMatchIn(body)
            )
            assertTrue("$jobId must execute its own Gradle task", body.contains("gradle --no-daemon"))
        }

        val testJob = jobBody("test")
        assertTrue("Android build must execute the core JVM module tests", testJob.contains(":core:test"))
        assertTrue("Android build must execute the business JVM module tests", testJob.contains(":business:test"))
        assertTrue("Android build must execute the gesture-core JVM module tests", testJob.contains(":gesture-core:test"))
        assertTrue("Android build must execute the gesture-runtime JVM module tests", testJob.contains(":gesture-runtime:test"))
        assertTrue("Android build must execute app architecture tests", testJob.contains(":app:testDebugUnitTest"))
        assertTrue("Failed tests must still upload their reports", testJob.contains("if: always()"))

        val freedomJob = jobBody("build-freedom-apk")
        val ocrJob = jobBody("build-ocr-plugin-apk")
        assertTrue("CI must build the Freedom debug APK", freedomJob.contains(":app:assembleDebug"))
        assertTrue("CI must build the OCR plugin debug APK", ocrJob.contains(":ocr-plugin:assembleDebug"))
        assertTrue("Freedom APK must be uploaded", freedomJob.contains("Freedom-debug-apk"))
        assertTrue("OCR APK must be uploaded", ocrJob.contains("Freedom-OCR-plugin-debug-apk"))

        val codeqlJob = jobBody("codeql")
        assertTrue(
            "CodeQL must wait for the Freedom build",
            Regex("(?m)^    needs: build-freedom-apk\\s*$").containsMatchIn(codeqlJob)
        )
        assertTrue("CodeQL must retain manual Java/Kotlin scanning", codeqlJob.contains("languages: java-kotlin") && codeqlJob.contains("build-mode: manual"))
        assertTrue("CodeQL must retain extended security and quality queries", codeqlJob.contains("security-extended,security-and-quality"))
        assertTrue(
            "CodeQL must compile both applications rather than reuse an APK",
            codeqlJob.contains(":app:assembleDebug") && codeqlJob.contains(":ocr-plugin:assembleDebug")
        )
    }

    @Test
    fun removedLegacyDuplicateImplementationsStayAbsent() {
        val removedPaths = listOf(
            "app/src/main/java/com/freedom/accessibility/chat/momo/MomoChatPageInspector.kt",
            "app/src/main/java/com/freedom/accessibility/chat/momo/MomoChatPageModels.kt",
            "app/src/main/java/com/freedom/data/MonitorActions.kt",
            "app/src/main/java/com/freedom/core/accessibility/AccessibilityEventRouter.kt",
            "app/src/main/java/com/freedom/core/runtime/RunCoordinator.kt",
            "business/src/main/kotlin/com/freedom/features/aireply/AiReplyMessageObservationCoordinator.kt",
            "business/src/main/kotlin/com/freedom/features/aireply/AiReplyReceivedGreetingsCoordinator.kt",
            "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsPageArrivalListener.kt",
            "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsRetrySchedule.kt",
            "app/src/test/java/com/freedom/accessibility/chat/momo/MomoChatPageClassifierTest.kt",
            "app/src/test/java/com/freedom/runtime/RunModeTest.kt",
            "app/src/test/java/com/freedom/core/accessibility/AccessibilityEventRouterTest.kt",
            "app/src/test/java/com/freedom/core/runtime/RunCoordinatorTest.kt"
        )
        val present = removedPaths.filter { projectFile(it).exists() }
        assertTrue("Legacy duplicate implementations must be removed: $present", present.isEmpty())
        val greetingsLifecycle = projectFile(
            "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsTaskController.kt"
        ).readText()
        val greetingsFlow = projectFile(
            "business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsTaskFlowController.kt"
        ).readText()
        assertFalse("Legacy greetings retry counter must remain absent", greetingsLifecycle.contains("recoveryRetryCount"))
        assertFalse("Legacy greetings retry plan must remain absent", greetingsFlow.contains("nextRecoveryRetryPlan"))
        assertFalse("Legacy greetings retry schedule must remain absent", greetingsFlow.contains("ReceivedGreetingsRetrySchedule"))
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
            "Page evidence must inspect the active target window with its window identity",
            pageInspection.contains("withActiveTargetRoot")
        )
        assertTrue(
            "Page evidence must reject a stale foreground window",
            pageInspection.contains("accessibilityGateway.isWindowCurrent(window)")
        )
        assertTrue("External foreground packages must be reported as non-Momo evidence", pageInspection.contains("packageName != targetPackage"))
        assertTrue("A missing foreground window must be distinguished from an external foreground window", pageInspection.contains("windowAvailable = false"))
        val externalPopupInspection = momoAdapter.substringAfter("override fun inspectExternalPopup(): PopupEvidence? =")
            .substringBefore("override fun inspectFeed():")
        assertTrue("External popup inspection must be read-only", externalPopupInspection.contains("dismissable = false"))
        assertTrue("External popup inspection must not export dismiss targets", externalPopupInspection.contains("dismissTarget = null"))
        assertTrue("External popup inspection must not export close tap bounds", externalPopupInspection.contains("closeBounds = null"))
        assertTrue("External popup inspection must not export close target bounds", externalPopupInspection.contains("closeTargetBounds = null"))
        assertTrue("AccountBrowse must preserve the external popup recovery branch", workflow.contains("inspectExternalPopup()"))
        assertTrue("Feed snapshots must distinguish whole-window and list-only evidence", momoAdapter.contains("captureVisibleFeedSnapshot(listOnly: Boolean)"))
        assertTrue("Card selection must return the source fingerprint from the same inspected window", momoAdapter.contains("sourceFingerprint = sourceFingerprint"))
        assertTrue("Card selection must return the source list snapshot from the same inspected list", momoAdapter.contains("sourceSnapshot = sourceSnapshot"))
        val gateway = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityGateway.kt").readText()
        val dispatcher = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt").readText()
        assertTrue("AccessibilityGateway must map structured dismiss actions", gateway.contains("NodeAction.DISMISS -> AccessibilityNodeInfo.ACTION_DISMISS"))
        assertTrue("Android Gesture Dispatcher must dispatch additional structured strokes", dispatcher.contains("addAll(spec.additionalStrokes)"))
        assertTrue("The Android root bridge must expose foreground read-only inspection", gateway.contains("override fun <T> withActiveWindowRoot"))
    }

    @Test
    fun presentationUiDependsOnPortsAndCallbacksNotConcreteAdaptersOrData() {
        val forbidden = forbiddenImports(
            kotlinFiles("app/src/main/java/com/freedom/ui"),
            listOf(
                Regex("com\\.freedom\\.(adapters|data|accessibility|stability)\\.")
            )
        )
        assertTrue("UI must use application ports/callbacks rather than concrete platform or data adapters:\n${forbidden.joinToString("\n")}", forbidden.isEmpty())
    }

    @Test
    fun serviceIsOnlyTheAndroidEventCallbackForwarder() {
        val service = projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt")
        val activity = projectFile("app/src/main/java/com/freedom/MainActivity.kt")
        val pipeline = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt")
        val forwarder = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventForwarder.kt")
        val serviceText = service.readText()
        val compositionRootText = projectFile("app/src/main/java/com/freedom/app/AppCompositionRoot.kt").readText()
        val activityText = activity.readText()
        val pipelineText = pipeline.readText()
        assertTrue("Application composition root must own the runtime coordinator", compositionRootText.contains("RunSessionCoordinator("))
        assertTrue("Service must forward raw Android events to the Android entry adapter", serviceText.contains("accessibilityEventForwarder.accept(event)"))
        assertFalse("Service must not call the event pipeline directly", serviceText.contains("accessibilityEventPipeline.process(event, runMode)"))
        assertTrue("The Android event entry must own callback filtering", forwarder.readText().contains("AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent"))
        val pipelinePort = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AccessibilityEventPipelinePort.kt")
        assertTrue("The Android event entry must depend on the named pipeline port", forwarder.readText().contains("AccessibilityEventPipelinePort"))
        assertTrue("The Android pipeline must implement the named pipeline port", pipelineText.contains(": AccessibilityEventPipelinePort"))
        assertTrue("The Android pipeline must signal the shared page observation coordinator", pipelineText.contains("pageObservationCoordinator?.signal(routedTargetEvent.type)"))
        assertTrue("The Android event entry must own pipeline forwarding", forwarder.readText().contains("pipeline.process(event, runMode)"))
        assertTrue("The pipeline port must expose normalized event processing", pipelinePort.readText().contains("fun process(event: AccessibilityEvent, runMode: RunMode)"))
        assertFalse("Service must not normalize Android events directly", serviceText.contains("AndroidAccessibilityEventNormalizer"))
        assertFalse("Service must not apply event routing or log de-duplication directly", serviceText.contains("DefaultAccessibilityEventRoutePolicy") || serviceText.contains("shouldSuppressDuplicate"))
        assertTrue("Android pipeline must normalize before routing", pipelineText.indexOf("AndroidAccessibilityEventNormalizer.normalize(") < pipelineText.indexOf("routePolicy.route("))
        assertTrue("AI Reply routing must stamp the current page generation before source classification", pipelineText.contains("normalizedEvent.copy(pageGeneration = currentWindowGeneration())"))
        assertTrue(
            "Android pipeline must dispatch target events exclusively through the unified entry",
            pipelineText.contains("unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(normalizedEvent))")
        )
        assertTrue(
            "Android pipeline must attach the classified origin to global-touch events before dispatch",
            pipelineText.contains("eventForRouting.copy(inputOriginDecision = originDecision)")
        )
        assertTrue(
            "Android pipeline must own global-touch dispatch of the routed event",
            pipelineText.contains("unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.GlobalTouch(routedTouchEvent))")
        )
        assertTrue(
            "Unified business event entry must be a non-null mandatory constructor dependency",
            pipelineText.contains("private val unifiedAccessibilityEntry: UnifiedAccessibilityEntry,")
        )
        assertFalse(
            "No legacy direct business dispatch callback may coexist with the unified entry",
            listOf("dispatchTargetApplicationEvent", "dispatchGlobalTouchEvent",
                "dispatchActivityTransition").any(pipelineText::contains)
        )
        assertTrue("Android pipeline must own route policy decisions", pipelineText.contains("routePolicy.route("))
        assertTrue("Composition root must construct the application run-command use case", compositionRootText.contains("RunSessionCommandUseCase("))
        assertTrue("Activity must submit commands through its injected application use case", activityText.contains("runSessionCommands.requestStart(") && activityText.contains("runSessionCommands.requestStop()"))
        assertFalse("Activity must not create a runtime coordinator", activityText.contains("RunSessionCoordinator"))
        assertFalse("Service must not call the Android workflow directly", serviceText.contains("accountBrowseWorkflow."))
    }

    @Test
    fun serviceDelegatesConnectionRecoveryAndReceiverRegistrationLifecycle() {
        val service = projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt").readText()
        val recovery = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityServiceRecoveryCoordinator.kt")
        val receiverRegistry = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidServiceReceiverRegistry.kt")
        assertTrue("A dedicated service recovery coordinator is required", recovery.isFile)
        assertTrue("A dedicated Service receiver registry is required", receiverRegistry.isFile)
        assertTrue("Service must invoke recovery from the system connection callback", service.contains("serviceRecoveryCoordinator.onServiceConnected()"))
        assertTrue("Service must register its target receiver through the registry", service.contains("serviceReceiverRegistry.register(\n            targetChangedReceiver"))
        assertTrue("Service must register calibration receiver through the registry", service.contains("serviceReceiverRegistry.register(gestureCalibrationController.receiver"))
        assertTrue("Service must unregister all owned receivers during destruction", service.contains("serviceReceiverRegistry.unregisterAll()"))
        assertFalse("Per-receiver registration booleans must be removed", listOf(
            "targetReceiverRegistered", "refreshReceiverRegistered", "gestureCalibrationReceiverRegistered"
        ).any(service::contains))
        assertFalse("Service must not retain the 500ms recovery callback implementation", service.contains("postDelayed({"))

        val recoverySource = recovery.readText()
        assertTrue("Recovery delay must remain 500ms", recoverySource.contains("KEEP_ALIVE_START_DELAY_MS = 500L"))
        assertTrue("Recovery must cancel pending work on Service destruction", recoverySource.contains("fun onServiceDestroyed()") && recoverySource.contains("cancelPendingKeepAliveStart()"))
        assertTrue("Delayed recovery must recheck the run request", recoverySource.contains("host.isRunRequested() && host.isKeepAliveEnabled()"))
        assertTrue("Overlay permission must guard only the optional overlay display", recoverySource.contains("host.isOverlayEnabled() && host.isFloatingLogEnabled() && host.canDrawOverlays()"))
        assertFalse("Delayed keep-alive must not depend on optional overlay permission", recoverySource.contains("host.isFloatingLogEnabled() &&\n                host.canDrawOverlays()"))

        val registrySource = receiverRegistry.readText()
        assertTrue("Registry must prevent duplicate receiver registration by identity", registrySource.contains("it === receiver"))
        assertTrue("Registry must isolate unregister failures", registrySource.contains("runCatching { unregisterReceiver(receiver) }"))
    }


    @Test
    fun floatingLogIsOptionalAndOverlayPermissionIsOnlyRequestedFromItsManualSwitch() {
        val mainActivity = projectFile("app/src/main/java/com/freedom/MainActivity.kt").readText()
        val configPanel = projectFile("app/src/main/java/com/freedom/ui/config/ConfigPanel.kt").readText()
        val monitorStore = projectFile("app/src/main/java/com/freedom/data/MonitorStore.kt").readText()
        val service = projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt").readText()

        val toggleStart = mainActivity.indexOf("private fun toggleMonitoring()")
        val toggleEnd = mainActivity.indexOf("private fun showTargetEditor()", toggleStart)
        assertTrue("Monitoring start/stop handler must exist", toggleStart >= 0 && toggleEnd > toggleStart)
        val toggleSource = mainActivity.substring(toggleStart, toggleEnd)
        assertTrue("Monitoring must keep the accessibility authorization check", toggleSource.contains("isAccessibilityEnabled()"))
        assertFalse("Monitoring must not require SYSTEM_ALERT_WINDOW", toggleSource.contains("canDrawOverlays"))

        assertTrue("Floating-log preference must default to off", monitorStore.contains("getBoolean(KEY_FLOATING_LOG, false)"))
        assertTrue("Floating-log switch must request overlay access only when manually enabled", configPanel.contains("if (enabled && !Settings.canDrawOverlays(activity))"))
        assertTrue("Floating-log switch must persist its own enabled state", configPanel.contains("repository.setFloatingLogEnabled(enabled)"))
        assertFalse("Permission settings must not expose a separate overlay permission row", mainActivity.contains("悬浮窗权限设置"))
        assertFalse("Accessibility event monitoring must not stop when overlay permission is absent", service.contains("if (!Settings.canDrawOverlays(this))"))
    }

    @Test
    fun androidEventPipelineKeepsRawPlatformEventsOutOfCoreAndFeatureLayers() {
        val pipeline = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt").readText()
        val forbidden = listOf(
            "app/src/main/java/com/freedom/core/accessibility/events/AccessibilityEventEnvelope.kt",
            "app/src/main/java/com/freedom/core/runtime/ModeRuntimePort.kt",
            "app/src/main/java/com/freedom/features/accountbrowse/AccountBrowseRuntime.kt",
            "app/src/main/java/com/freedom/features/aireply/AiReplyObservationRuntime.kt"
        ).map { projectFile(it) }
            .filter { it.readText().contains("android.view.accessibility.AccessibilityEvent") }
        assertTrue("Platform AccessibilityEvent must stop at the Android adapter boundary: ${forbidden.map { it.name }}", forbidden.isEmpty())
        assertTrue("Android pipeline must normalize into the immutable envelope", pipeline.contains("AccessibilityEventEnvelope"))
        assertTrue("Android pipeline must expose a normalized test seam", pipeline.contains("processNormalized("))
    }

    @Test
    fun modeRuntimeContractUsesOnlyNormalizedPlatformNeutralEvents() {
        val port = projectFile("app/src/main/java/com/freedom/core/runtime/ModeRuntimePort.kt").readText()
        assertTrue(port.contains("AccessibilityEventEnvelope"))
        assertFalse(port.contains("android.view.accessibility.AccessibilityEvent"))
        assertFalse(port.contains("android.view.accessibility.AccessibilityNodeInfo"))
    }

    @Test
    fun platformImplementationsAreNotStoredUnderCoreOrFeaturePackages() {
        val oldPaths = listOf(
            "app/src/main/java/com/freedom/core/accessibility/android/AndroidAccessibilityGateway.kt",
            "app/src/main/java/com/freedom/core/accessibility/android/AndroidAccessibilityEventNormalizer.kt",
            "app/src/main/java/com/freedom/core/runtime/android/HandlerTaskScheduler.kt",
            "app/src/main/java/com/freedom/features/accountbrowse/android/AndroidAccountBrowseWorkflow.kt"
        )
        val present = oldPaths.filter { projectFile(it).exists() }
        assertTrue("Android implementations remain inside Core/Feature: $present", present.isEmpty())
    }

    @Test
    fun projectDeclaresBusinessGestureAndCoreGradleModules() {
        val settings = projectFile("settings.gradle.kts").readText()
        val modules = listOf(":app", ":business", ":core", ":gesture-api", ":gesture-core", ":gesture-runtime", ":gesture-android")
        modules.forEach { module ->
            assertTrue("Gradle settings must include $module", settings.contains("\"$module\""))
        }
        modules.drop(1).forEach { module ->
            assertTrue("Gradle module build file is missing for $module", moduleBuildFile(module).isFile)
        }
        val coreBuild = projectFile("core/build.gradle.kts").readText()
        val gestureCoreBuild = projectFile("gesture/gesture-core/build.gradle.kts").readText()
        assertTrue("GestureDynamicsPolicy must not compile in :core", coreBuild.contains("GestureDynamicsPolicy.kt"))
        assertTrue("GestureDynamicsPolicy tests must not compile in :core", coreBuild.contains("GestureDynamicsPolicyTest.kt"))
        assertTrue("GestureDynamicsPolicy and its tests must compile in :gesture-core",
            gestureCoreBuild.contains("GestureDynamicsPolicy.kt") && gestureCoreBuild.contains("GestureDynamicsPolicyTest.kt"))
    }

    @Test
    fun gestureApiIsTheOnlyPublicBusinessFacingGestureContract() {
        val apiSource = File(
            projectRoot,
            "gesture/gesture-api/src/main/kotlin/com/freedom/gesture/api/GestureApi.kt"
        )
        assertTrue("The canonical GestureApi source must exist", apiSource.isFile)
        val apiText = apiSource.readText()
        assertTrue("GestureApi must expose semantic request submission", apiText.contains("fun execute("))
        assertTrue("GestureApi must capture the execution context", apiText.contains("fun captureContext("))
        assertTrue("GestureApi must cancel only a known request ID", Regex("fun cancelActive\\s*\\(\\s*commandId: String").containsMatchIn(apiText))
        listOf(
            "GestureCommand", "GestureSpec", "GesturePathCommand", "GestureGeometryPolicy",
            "GestureExecutionPort", "GestureDescription", "AccessibilityService"
        ).forEach { internalType ->
            assertFalse("Public GestureApi must not expose $internalType", apiText.contains(internalType))
        }

        val apiBuild = moduleBuildFile(":gesture-api").readText()
        assertTrue(":gesture-api must compile the canonical GestureApi", apiBuild.contains("com/freedom/gesture/api/GestureApi.kt"))
        assertFalse(":gesture-api must not compile the retired port declaration", apiBuild.contains("GesturePolicyPort.kt"))

        val legacyPort = projectFile("app/src/main/java/com/freedom/core/accessibility/gesture/GesturePolicyPort.kt")
        val legacySource = legacyPort.readText()
        assertFalse("The legacy file must not redeclare GesturePolicyPort", Regex("interface\\s+GesturePolicyPort\\b").containsMatchIn(legacySource))
        assertFalse("The legacy file must not redeclare an interruptible public port", Regex("interface\\s+InterruptibleGesturePolicyPort\\b").containsMatchIn(legacySource))
    }

    @Test
    fun businessAndGestureConsumersUseOnlyTheCanonicalGestureApi() {
        val businessBuild = moduleBuildFile(":business").readText()
        assertTrue("Business must depend on the Gesture API", businessBuild.contains("api(project(\":gesture-api\"))"))
        listOf(":gesture-core", ":gesture-runtime", ":gesture-android").forEach { module ->
            assertFalse("Business must not depend on $module", businessBuild.contains("project(\"$module\")"))
        }

        val consumers = buildList {
            addAll(kotlinFiles("app/src/main/java/com/freedom/features"))
            addAll(kotlinFiles("app/src/main/java/com/freedom/adapters/social"))
            add(projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt"))
            add(projectFile("app/src/main/java/com/freedom/adapters/android/gestures/AndroidGestureCalibrationHost.kt"))
        }.filter { it.isFile }
        val violations = consumers.flatMap { file ->
            val source = file.readText()
            buildList {
                if (source.contains("GesturePolicyPort") || source.contains("InterruptibleGesturePolicyPort")) {
                    add("${file.relativeTo(projectRoot)}: legacy public port")
                }
                if (Regex("import\\s+com\\.freedom\\.application\\.gesture\\.").containsMatchIn(source)) {
                    add("${file.relativeTo(projectRoot)}: Runtime implementation import")
                }
                if (Regex("import\\s+com\\.freedom\\.gesture\\.api\\.GestureApi").containsMatchIn(source).not() &&
                    Regex("import\\s+com\\.freedom\\.features\\.accountbrowse\\.ports\\.AccountBrowseGesturePort").containsMatchIn(source).not() &&
                    source.contains("GestureIntentRequest")) {
                    add("${file.relativeTo(projectRoot)}: gesture consumer missing GestureApi dependency")
                }
            }
        }
        assertTrue("Business/Adapter gesture consumers must use only GestureApi:\n${violations.joinToString("\n")}", violations.isEmpty())

        val compositionRoot = projectFile("app/src/main/java/com/freedom/app/AppCompositionRoot.kt").readText()
        assertTrue("Application composition root must create the shared Runtime handle", compositionRoot.contains("GestureRuntimeFactory.create("))
        assertTrue("Composition root must expose the shared runtime through the public GestureApi view", compositionRoot.contains("val gesturePolicy: PublicGestureApi get() = gestureRuntime"))
        val aiReplySource = consumers.first { it.name == "AiReplyObservationRuntime.kt" }.readText()
        assertTrue(
            "AI Reply must cancel an active gesture through the canonical GestureApi",
            aiReplySource.contains("gesturePolicy.cancelActive(interruptedGestureRequestId,")
        )
        assertFalse(
            "AI Reply must not cast the public GestureApi to a runtime implementation",
            Regex("gesturePolicy\\s+as\\s+").containsMatchIn(aiReplySource)
        )
    }

    @Test
    fun gestureGradleDependenciesFollowTheApprovedDirection() {
        val business = moduleBuildFile(":business").readText()
        val api = moduleBuildFile(":gesture-api").readText()
        val geometry = moduleBuildFile(":gesture-core").readText()
        val runtime = moduleBuildFile(":gesture-runtime").readText()
        val android = moduleBuildFile(":gesture-android").readText()
        val app = moduleBuildFile(":app").readText()

        assertTrue(":business must own the feature source tree", business.contains("com/freedom/features/**"))
        assertTrue(":business must depend on the gesture API", business.contains("api(project(\":gesture-api\"))"))
        assertTrue(":business may depend on :core", business.contains("api(project(\":core\"))"))
        assertFalse(":business must not depend on gesture implementation modules", listOf(":gesture-core", ":gesture-runtime", ":gesture-android").any { business.contains("project(\"$it\")") })
        assertTrue(":app must assemble the business module", moduleBuildFile(":app").readText().contains("implementation(project(\":business\"))"))
        assertTrue(":gesture-api must depend on :core", api.contains("api(project(\":core\"))"))
        assertTrue(":gesture-core must depend on :core", geometry.contains("api(project(\":core\"))"))
        assertTrue(":gesture-runtime must depend on :gesture-api and :gesture-core", runtime.contains("api(project(\":gesture-api\")") && runtime.contains("implementation(project(\":gesture-core\"))"))
        assertFalse(":gesture-runtime must not re-export geometry algorithms", runtime.contains("api(project(\":gesture-core\"))"))
        assertFalse(":gesture-runtime must not depend on Android Gesture Adapter", runtime.contains("project(\":gesture-android\")"))
        assertTrue(":gesture-android must implement Runtime SPI", android.contains("project(\":gesture-runtime\")"))
        assertFalse(":gesture-android must not depend directly on geometry algorithms", android.contains("project(\":gesture-core\")"))
        assertTrue(":app must compose the modules", app.contains("project(\":gesture-runtime\")") && app.contains("project(\":gesture-android\")"))
        assertFalse(":app must not declare the business Feature source tree", app.contains("src/main/java/com/freedom/features") || app.contains("src/main/kotlin/com/freedom/features"))
        assertFalse(":app must not receive gesture algorithms as a transitive API dependency", app.contains("project(\":gesture-core\")"))
    }

    private fun moduleBuildFile(module: String): File = when (module) {
        ":app" -> projectFile("app/build.gradle.kts")
        ":business" -> projectFile("business/build.gradle.kts")
        ":core" -> projectFile("core/build.gradle.kts")
        ":gesture-api" -> projectFile("gesture/gesture-api/build.gradle.kts")
        ":gesture-core" -> projectFile("gesture/gesture-core/build.gradle.kts")
        ":gesture-runtime" -> projectFile("gesture/gesture-runtime/build.gradle.kts")
        ":gesture-android" -> projectFile("gesture/gesture-android/build.gradle.kts")
        else -> error("Unknown module: $module")
    }

    @Test
    fun serviceDelegatesWindowPreviewAndLogResponsibilitiesToAndroidAdapters() {
        val service = projectFile("app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt")
        val source = service.readText()
        val compositionRootSource = projectFile("app/src/main/java/com/freedom/app/AppCompositionRoot.kt").readText()
        val formatter = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AccessibilityEventFormatter.kt")
        val formatterSource = formatter.readText()
        val workflow = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt")
        val workflowSource = workflow.readText()
        val runtimeAdapter = projectFile("app/src/main/java/com/freedom/adapters/runtime/android/AccountBrowseModeRuntimeAdapter.kt")
        val runtimeAdapterSource = runtimeAdapter.readText()
        val previewScheduler = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidMonitorPreviewScheduler.kt")
        val previewSchedulerSource = previewScheduler.readText()
        val logPresenter = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidMonitorLogPresenter.kt")
        val logPresenterSource = logPresenter.readText()

        assertFalse("Service must not own current target Activity state", source.contains("currentTargetActivityClass"))
        assertFalse("Service must not own the scan Runnable or queue state", source.contains("scanQueued") || source.contains("scanRunnable"))
        assertFalse("Service must not own the scan scheduling implementation", source.contains("private fun scheduleBrowseScan"))
        assertFalse("Service must not duplicate PackageManager app-label resolution", source.contains("packageManager.getApplicationInfo"))
        assertTrue("Accessibility composition root must create the shared Activity context tracker", compositionRootSource.contains("AndroidWindowContextTracker()"))
        assertTrue(
            "Activity context must be cleared at the same three established lifecycle boundaries across service and composition adapters",
            Regex("windowContextTracker\\.clearActivityClass\\(\\)").findAll(source + compositionRootSource).count() == 3
        )
        assertTrue("Composition root must create the monitor preview scheduler", compositionRootSource.contains("AndroidMonitorPreviewScheduler("))
        assertTrue("Composition root must create the log/presentation bridge", compositionRootSource.contains("AndroidMonitorLogPresenter("))
        assertTrue("Event pipeline and tree preview must share one app-label resolver", compositionRootSource.contains("resolveAppLabel = appLabelResolver::resolve") && compositionRootSource.contains("appLabelResolver = appLabelResolver"))

        assertTrue("Preview scheduler must retain the 350ms debounce", previewSchedulerSource.contains("DEFAULT_DEBOUNCE_MS = 350L"))
        assertTrue("Preview scheduler must recheck the browse-run gate at execution time", previewSchedulerSource.contains("if (isBrowseRunActive()) scanActiveWindow()"))
        assertTrue("Log presenter must refresh after persisting a log", logPresenterSource.indexOf("persistLog(") < logPresenterSource.indexOf("refreshPresentation()"))
        assertTrue("Log presenter must preserve the existing millisecond timestamp format", logPresenterSource.contains("HH:mm:ss.SSS"))

        assertTrue("Central formatter must own immutable source details", formatterSource.contains("fun sourceDetails(source: AccessibilitySourceSnapshot?)"))
        assertFalse("Pipeline must not contain a second source-details formatter", projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt").readText().contains("private fun accessibilitySourceDetails"))
        assertFalse("AccountBrowse workflow must not contain a second source-details formatter", workflowSource.contains("accessibilitySourceDetails("))
        assertTrue("Workflow must use the shared source-details formatter", workflowSource.contains("AccessibilityEventFormatter.sourceDetails(event.source)"))
        assertTrue("Runtime adapter must use the shared source-details formatter", runtimeAdapterSource.contains("AccessibilityEventFormatter.sourceDetails(event.source)"))
    }

    @Test
    fun aiReplyMessageReadingRespectsFeatureAdapterBoundaryAndRedactsMessageBodies() {
        val messageFeatureFiles = kotlinFiles("app/src/main/java/com/freedom/features/aireply")
        val violations = messageFeatureFiles.filter { file ->
            val source = file.readText()
            source.contains("import android.") ||
                source.contains("import androidx.") ||
                source.contains("com.freedom.adapters.social.momo") ||
                source.contains("com.immomo.momo") ||
                source.contains("AccessibilityNodeInfo")
        }.map { it.relativeTo(projectRoot).path }
        assertTrue("AI Reply message feature must remain platform/app neutral: ${violations.joinToString()}", violations.isEmpty())

        val messageModel = projectFile("app/src/main/java/com/freedom/features/aireply/domain/message/ChatMessage.kt").readText()
        assertTrue("Message content must be redacted from diagnostic toString", messageModel.contains("content=[REDACTED]"))
        assertTrue("Visible-message batches must be explicitly bounded/in-memory", messageModel.contains("currently visible messages"))

        val messagePort = projectFile("app/src/main/java/com/freedom/features/aireply/ports/ChatMessagePort.kt").readText()
        assertTrue("Feature message reading must use a narrow port", messagePort.contains("interface ChatMessagePort"))
        assertFalse("Message port must not expose Android nodes", messagePort.contains("AccessibilityNodeInfo"))

        val momoAdapter = projectFile("app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt").readText()
        assertTrue("Momo social adapter must implement the message port", momoAdapter.contains("ChatMessagePort"))
        assertTrue("Momo reader must be called through the adapter's extracted message component", momoAdapter.contains("messageComponents.reader.readRecentMessages"))
        assertTrue("Runtime must receive the message port through application composition", projectFile("app/src/main/java/com/freedom/app/AppCompositionRoot.kt").readText().contains("chatMessagePort = momoSocialAdapter"))
    }

    @Test
    fun aiReplyObservationReadsMessagesOnlyForKnownChatDetailsAndNeverLogsBodies() {
        val runtime = projectFile("app/src/main/java/com/freedom/features/aireply/AiReplyObservationRuntime.kt").readText()
        assertTrue("Message reading must be limited to chat detail candidates", runtime.contains("ChatPageKind.CHAT_DETAIL_CANDIDATE ->"))
        assertTrue("Message reading must have a fixed upper bound", runtime.contains("MAX_VISIBLE_MESSAGES = 20"))
        assertTrue("Runtime logs must use redacted batch summaries", runtime.contains("batch.summary()"))
        assertTrue("Runtime must not log message content", !runtime.contains("message.content"))
        assertTrue("Runtime must not persist message content", !runtime.contains("saveMessage") && !runtime.contains("persistMessage"))
    }



    @Test
    fun businessFeedSwipeIntentDoesNotExposeTrajectoryTuning() {
        val intentSource = projectFile("app/src/main/java/com/freedom/core/accessibility/gesture/GestureIntent.kt").readText()
        val feedSwipe = intentSource.substringAfter("data class FeedSwipe(").substringBefore(") : GestureIntent")
        listOf(
            "curveOffsetRatio",
            "microPerturbationFrequencyHz",
            "density",
            "maxCurveOffsetRatio",
            "enableMicroPerturbation",
            "microPerturbationAmplitudePx",
            "gesturePathSteps",
            "microPerturbationPathSteps"
        ).forEach { parameter ->
            assertFalse("Business FeedSwipe must not expose $parameter", feedSwipe.contains(parameter))
        }

        val manager = projectFile("app/src/main/java/com/freedom/application/gesture/GesturePolicyManager.kt").readText()
        val pipeline = projectFile("app/src/main/java/com/freedom/application/gesture/GesturePolicyPipeline.kt").readText()
        assertTrue("Runtime must own per-request algorithm selection", manager.contains("random.nextBoolean()"))
        assertTrue("Pipeline must resolve a strategy before geometry compilation", pipeline.contains("policyManager.select(validatedIntentRequest)"))
        val compiler = projectFile("app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt").readText()
        assertTrue("Feed density must come from the captured coordinate context", compiler.contains("executionContext.coordinateContext.density"))
    }

    @Test
    fun stageTwoGeometryPolicyIsCompiledAndValidatedBeforeAndroidDispatch() {
        val geometry = projectFile("app/src/main/java/com/freedom/core/accessibility/gesture/GestureGeometryPolicy.kt").readText()
        val compiler = projectFile("app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt").readText()
        val validator = projectFile("app/src/main/java/com/freedom/application/gesture/GesturePolicyValidator.kt").readText()
        val dispatcher = projectFile("app/src/main/java/com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt").readText()

        assertTrue("Click and movement geometry must reserve the system-edge safety band", geometry.contains("safeDisplayBounds("))
        assertTrue("Click sampling must use an inward safe region", geometry.contains("DEFAULT_TAP_INSET_RATIO = 0.15f"))
        assertTrue("Slide starts must use bounded normal-distribution sampling", geometry.contains("sampleTruncatedGaussianOffset("))
        assertTrue("Normal sampling must be truncated at a fixed sigma bound", geometry.contains("GAUSSIAN_TRUNCATION_SIGMAS = 3f"))
        assertTrue("Base swipe distance must have bounded +/-7.5% variation", geometry.contains("SWIPE_DISTANCE_VARIATION_RATIO = 0.075f"))
        assertTrue("Movement geometry must have an explicit five-phase time profile", geometry.contains("SPEED_PHASE_DURATION_FRACTIONS = listOf(0.15, 0.20, 0.30, 0.20, 0.15)"))
        assertTrue("The command compiler must apply system-edge bounds before compiling clicks", compiler.contains("GestureGeometryPolicy.safeDisplayBounds("))
        assertTrue("Validator must require a bounded multi-segment motion profile", validator.contains("movement_gesture_requires_three_to_eight_motion_segments"))
        assertTrue("Validator must reject invalid or excessive segment speeds", validator.contains("movement_gesture_speed_profile_invalid") && validator.contains("movement_gesture_speed_exceeds_limit"))
        val dynamics = projectFile("app/src/main/java/com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt").readText()
        assertTrue("Dynamics policy must support explicit kinematic acceleration/deceleration", dynamics.contains("GestureMotionMode.KINEMATIC_LIMITED") && dynamics.contains("accelerationPxPerSecondSquared") && dynamics.contains("decelerationPxPerSecondSquared"))
        assertTrue("Validator must constrain every control point to the approved region", validator.contains("coordinate_outside_validated_safe_region"))
        assertTrue("Android dispatcher must link the same pointer with continueStroke", dispatcher.contains("previous.continueStroke(paths[index], 0L, stroke.durationMs, stroke.willContinue)"))
        assertTrue("All continuation segments must be owned by the centralized Android dispatcher", dispatcher.contains("dispatchSegment(index + 1, androidStroke)"))
        assertTrue("One logical continued gesture must settle the registered input action once", dispatcher.contains("if (terminalDelivered) return"))
    }

}
