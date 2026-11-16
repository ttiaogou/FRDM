import java.io.File
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }
val modelCatalogUrl = providers.gradleProperty("ocrModelCatalogUrl").orNull.orEmpty()
val escapedModelCatalogUrl = modelCatalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    signingConfigs {
        if (releaseSigningConfigured) {
            create("freedomRelease") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    namespace = "com.freedom.ocr.plugin"
    compileSdk = 35

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("freedomRelease")
            }
        }
    }

    defaultConfig {
        applicationId = "com.freedom.ocr.plugin"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("ocrPluginVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("ocrPluginVersionName").orNull ?: "1.0.0"
        buildConfigField("String", "OCR_MODEL_CATALOG_URL", "\"$escapedModelCatalogUrl\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// SDK source is pinned to the same immutable PaddleOCR revision documented for Freedom.
// The two source files changed locally are excluded from this list; local copies add support
// for loading model files from the plugin's private files directory rather than APK assets.
val paddleOcrRevision = "dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf"
val paddleOcrSdkFiles = listOf(
    "PaddleOCR.kt",
    "PaddleOCRConfig.kt",
    "EngineConfig.kt",
    "engine/OCREngine.kt",
    "engine/OCREngineResult.kt",
    "engine/DetectionEngine.kt",
    "engine/RecognitionEngine.kt",
    "model/OCRBox.kt",
    "model/OCRError.kt",
    "model/OCRResult.kt",
    "model/OCRRunResult.kt",
    "postprocess/BoxSorter.kt",
    "postprocess/CTCDecoder.kt",
    "postprocess/QuadTextCrop.kt",
    "postprocess/QuadGeometry.kt",
    "postprocess/DBPostProcessor.kt",
    "postprocess/PolygonUnclip.kt",
    "preprocess/RecPreprocessor.kt",
    "preprocess/DetPreprocessor.kt",
    "util/YamlUtils.kt",
    "util/MathUtils.kt",
    "util/ImageUtils.kt",
    "util/BitmapUtils.kt",
)
val paddleOcrGeneratedSources = layout.buildDirectory.dir("generated/sources/paddleocr/main")
val paddleOcrGeneratedAssets = layout.buildDirectory.dir("generated/assets/paddleocr/main")
val freedomOcrCacheDir = providers.gradleProperty("freedomOcrCacheDir")
    .orNull
    ?.let(::File)
    ?: File(gradle.gradleUserHomeDir, "caches/freedom-ocr")
val paddleOcrCacheRoot = File(freedomOcrCacheDir, "paddleocr/$paddleOcrRevision")
val preparePaddleOcrSdk = tasks.register("preparePaddleOcrSdk") {
    outputs.dir(paddleOcrGeneratedSources)
    outputs.dir(paddleOcrGeneratedAssets)
    outputs.upToDateWhen {
        paddleOcrSdkFiles.all { relative ->
            val source = paddleOcrGeneratedSources.get().file("com/paddle/ocr/$relative").asFile
            source.isFile && source.length() > 0L
        } && File(paddleOcrGeneratedAssets.get().asFile, "THIRD_PARTY_NOTICES/PaddleOCR-LICENSE.txt").isFile
    }
    doLast {
        val sourceRoot = paddleOcrGeneratedSources.get().asFile
        val assetsRoot = paddleOcrGeneratedAssets.get().asFile
        sourceRoot.deleteRecursively()
        assetsRoot.deleteRecursively()
        val cachedSourcesRoot = File(paddleOcrCacheRoot, "sources")
        val cachedAssetsRoot = File(paddleOcrCacheRoot, "assets")
        val cacheIsComplete = paddleOcrSdkFiles.all { relative ->
            val cached = File(cachedSourcesRoot, "com/paddle/ocr/$relative")
            cached.isFile && cached.length() > 0L
        } && File(cachedAssetsRoot, "THIRD_PARTY_NOTICES/PaddleOCR-LICENSE.txt").let {
            it.isFile && it.length() > 0L
        }

        if (!cacheIsComplete) {
            val stagingRoot = File(paddleOcrCacheRoot.parentFile, "${paddleOcrCacheRoot.name}.staging")
            stagingRoot.deleteRecursively()
            val stagingSourcesRoot = File(stagingRoot, "sources")
            val stagingAssetsRoot = File(stagingRoot, "assets")
            val sourceBase = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/$paddleOcrRevision/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/"

            fun download(url: String, destination: File) {
                destination.parentFile?.mkdirs()
                val temporary = File(destination.parentFile, destination.name + ".part")
                URL(url).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "Freedom-OCR-Plugin-Build")
                }.getInputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                check(temporary.isFile && temporary.length() > 0L) { "PaddleOCR SDK 下载文件为空：$url" }
                check(destination.delete() || !destination.exists()) { "无法替换 SDK 源文件：$destination" }
                check(temporary.renameTo(destination)) { "无法保存 SDK 源文件：$destination" }
            }

            paddleOcrSdkFiles.forEach { relative ->
                download(sourceBase + relative, File(stagingSourcesRoot, "com/paddle/ocr/$relative"))
            }
            download(
                "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/$paddleOcrRevision/LICENSE",
                File(stagingAssetsRoot, "THIRD_PARTY_NOTICES/PaddleOCR-LICENSE.txt")
            )
            check(paddleOcrSdkFiles.all { relative ->
                File(stagingSourcesRoot, "com/paddle/ocr/$relative").let { it.isFile && it.length() > 0L }
            }) { "PaddleOCR SDK cache staging is incomplete" }
            check(File(stagingAssetsRoot, "THIRD_PARTY_NOTICES/PaddleOCR-LICENSE.txt").isFile) {
                "PaddleOCR SDK license cache staging is incomplete"
            }
            paddleOcrCacheRoot.deleteRecursively()
            paddleOcrCacheRoot.parentFile.mkdirs()
            check(stagingRoot.renameTo(paddleOcrCacheRoot)) { "Unable to promote PaddleOCR SDK cache" }
        }

        cachedSourcesRoot.copyRecursively(sourceRoot, overwrite = true)
        cachedAssetsRoot.copyRecursively(assetsRoot, overwrite = true)
        logger.lifecycle("PaddleOCR SDK cache: ${if (cacheIsComplete) "hit" else "refreshed"} ($paddleOcrRevision)")
    }
}


// Bundle the pinned OCR model into the plugin APK at build time. The phone must never need
// Hugging Face (or any other external model host) for its first recognition request.
val bundledModelAssets = layout.buildDirectory.dir("generated/assets/bundled-ocr-models/main")
val prepareBundledOcrModels = tasks.register("prepareBundledOcrModels") {
    val catalogFile = layout.projectDirectory.file("src/main/assets/ocr-model-catalog.json")
    inputs.file(catalogFile)
    outputs.dir(bundledModelAssets)
    // Always verify pinned hashes; existence alone is not proof that generated assets are intact.
    outputs.upToDateWhen { false }
    doLast {
        val catalog = groovy.json.JsonSlurper().parse(catalogFile.asFile) as Map<*, *>
        val models = catalog["models"] as? List<*> ?: error("OCR model catalog has no models")
        val outputRoot = bundledModelAssets.get().asFile
        outputRoot.mkdirs()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun downloadAndVerify(
            url: String,
            destination: File,
            cacheFile: File,
            minimumBytes: Long,
            expectedHash: String
        ) {
            fun isValid(file: File): Boolean = file.isFile && file.length() >= minimumBytes &&
                sha256(file).equals(expectedHash, ignoreCase = true)

            if (!isValid(cacheFile)) {
                cacheFile.parentFile.mkdirs()
                val temporary = File(cacheFile.parentFile, cacheFile.name + ".part")
                temporary.delete()
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 180_000
                    setRequestProperty("User-Agent", "Freedom-OCR-Model-Build")
                }
                try {
                    connection.getInputStream().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 100L * 1024L * 1024L) { "OCR model file exceeds 100 MiB: $url" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(temporary.length() >= minimumBytes) {
                        "OCR model file is smaller than catalog minimum: $url (${temporary.length()} bytes)"
                    }
                    val actualHash = sha256(temporary)
                    check(actualHash.equals(expectedHash, ignoreCase = true)) {
                        "OCR model SHA-256 mismatch for $url: expected=$expectedHash actual=$actualHash"
                    }
                    if (cacheFile.exists()) check(cacheFile.delete())
                    check(temporary.renameTo(cacheFile)) { "Unable to save OCR model cache file: $cacheFile" }
                } catch (failure: Throwable) {
                    temporary.delete()
                    throw failure
                } finally {
                    (connection as? java.net.HttpURLConnection)?.disconnect()
                }
            }

            check(isValid(cacheFile)) {
                "Cached OCR model failed validation: $cacheFile (expected SHA-256=$expectedHash)"
            }
            destination.parentFile.mkdirs()
            if (destination.exists()) check(destination.delete())
            cacheFile.copyTo(destination, overwrite = true)
            logger.lifecycle("Bundled OCR model: ${destination.relativeTo(outputRoot)} (${destination.length()} bytes; cache verified)")
        }

        models.forEach { raw ->
            val model = raw as? Map<*, *> ?: error("Invalid OCR model catalog entry")
            val modelId = model["modelId"] as? String ?: error("OCR modelId missing")
            require(modelId.matches(Regex("[a-z0-9_-]{2,64}"))) { "Invalid OCR modelId: $modelId" }
            require(model["engineId"] == "paddle-onnx") { "Unsupported bundled OCR engine: ${model["engineId"]}" }
            val urls = listOf(
                model["detUrl"] as? String ?: error("detUrl missing"),
                model["recUrl"] as? String ?: error("recUrl missing"),
                model["recConfigUrl"] as? String ?: error("recConfigUrl missing")
            )
            val sizes = model["minimumSizes"] as? List<*> ?: error("minimumSizes missing")
            val hashes = model["sha256"] as? Map<*, *> ?: error("sha256 missing")
            val expectedHashes = listOf("det", "rec", "recConfig").map {
                hashes[it] as? String ?: error("sha256.$it missing")
            }
            val relativePaths = listOf("det/inference.onnx", "rec/inference.onnx", "rec/inference.yml")
            urls.forEachIndexed { index, url ->
                require(url.startsWith("https://")) { "OCR model URL must use HTTPS" }
                val minimum = (sizes[index] as? Number)?.toLong() ?: error("Invalid minimum size")
                val hash = expectedHashes[index]
                require(hash.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256 for $modelId" }
                val relativePath = relativePaths[index]
                val destination = File(outputRoot, "models/$modelId/$relativePath")
                val cacheFile = File(freedomOcrCacheDir, "models/$modelId/$relativePath")
                downloadAndVerify(url, destination, cacheFile, minimum, hash)
            }
        }
    }
}

android.sourceSets.getByName("main").java.srcDir(paddleOcrGeneratedSources)
android.sourceSets.getByName("main").assets.srcDir(bundledModelAssets)
tasks.named("preBuild").configure { dependsOn(preparePaddleOcrSdk, prepareBundledOcrModels) }
tasks.configureEach {
    if ((name.startsWith("compile") && name.endsWith("Kotlin")) ||
        (name.startsWith("merge") && name.endsWith("Assets"))
    ) {
        dependsOn(preparePaddleOcrSdk, prepareBundledOcrModels)
    }
}


dependencies {
    implementation(project(":ocr-plugin-api"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("org.opencv:opencv:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
