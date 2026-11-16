package com.freedom.ocr.plugin.model

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.freedom.ocr.plugin.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Manages model files independently from both APKs and the OCR engine implementation. */
internal class OcrModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val ocrRoot = File(appContext.noBackupFilesDir, "ocr")
    private val modelsRoot = File(ocrRoot, "models")
    private val catalogFile = File(ocrRoot, CATALOG_FILE_NAME)

    @Volatile
    private var catalogCache: Map<String, ModelSpec>? = null
    private var lastCatalogRefreshAttemptMs = 0L
    @Volatile
    private var validatedModelFingerprint: String? = null

    data class ModelFiles(
        val modelId: String,
        val version: String,
        val detectionModel: File,
        val recognitionModel: File,
        val recognitionConfig: File
    )

    data class ModelStatus(
        val modelId: String,
        val version: String?,
        val installed: Boolean,
        val selected: Boolean,
        val totalBytes: Long
    )

    @Synchronized
    fun ensureInstalled(modelId: String): ModelFiles {
        val startedAt = SystemClock.elapsedRealtime()
        logModel("MODEL_PREPARE_START", "modelId=$modelId")
        try {
            val spec = modelCatalog()[modelId]
                ?: throw IllegalArgumentException("当前 OCR 插件不支持模型：$modelId")
            val destination = File(modelsRoot, modelId)
            val fingerprint = spec.validationFingerprint()
            if (validatedModelFingerprint != fingerprint) {
                val validationStartedAt = SystemClock.elapsedRealtime()
                logModel("MODEL_VALIDATE_START", "modelId=$modelId;version=${spec.version}")
                val valid = isInstalledAndValid(destination, spec)
                logModel("MODEL_VALIDATE_END", "modelId=$modelId;valid=$valid;durationMs=${SystemClock.elapsedRealtime() - validationStartedAt}")
                if (!valid) {
                    val downloadStartedAt = SystemClock.elapsedRealtime()
                    logModel("MODEL_INSTALL_START", "modelId=$modelId;version=${spec.version}")
                    downloadAndInstall(spec, destination)
                    logModel("MODEL_INSTALL_SUCCESS", "modelId=$modelId;durationMs=${SystemClock.elapsedRealtime() - downloadStartedAt}")
                }
                validatedModelFingerprint = fingerprint
            } else {
                logModel("MODEL_VALIDATION_CACHE_HIT", "modelId=$modelId;version=${spec.version}")
            }
            return modelFiles(modelId, spec.version, destination).also {
                logModel("MODEL_PREPARE_SUCCESS", "modelId=$modelId;version=${spec.version};durationMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } catch (failure: Throwable) {
            Log.e(LOG_TAG, "OCR_MODEL_STAGE stage=MODEL_PREPARE_FAILED;modelId=$modelId;durationMs=${SystemClock.elapsedRealtime() - startedAt};type=${failure.javaClass.simpleName};message=${failure.message}", failure)
            throw failure
        }
    }

    @Synchronized
    fun status(modelId: String, selectedModelId: String?): ModelStatus {
        val spec = modelCatalog()[modelId]
            ?: return ModelStatus(modelId, null, false, false, 0L)
        val directory = File(modelsRoot, modelId)
        val fingerprint = spec.validationFingerprint()
        val installed = if (validatedModelFingerprint == fingerprint) {
            requiredFiles(directory).all { it.isFile && it.length() > 0L }
        } else {
            isInstalledAndValid(directory, spec).also { valid ->
                if (valid) validatedModelFingerprint = fingerprint
            }
        }
        val files = requiredFiles(directory)
        return ModelStatus(
            modelId = modelId,
            version = if (installed) spec.version else null,
            installed = installed,
            selected = modelId == selectedModelId,
            totalBytes = if (installed) files.sumOf { it.length() } else 0L
        )
    }

    /** Returns the bundled/cached catalog without network I/O; safe during initial AIDL handshake. */
    @Synchronized
    fun cachedSupportedModelIds(): Set<String> {
        val current = catalogCache ?: loadCachedOrBundledCatalog().also { catalogCache = it }
        return current.keys
    }

    /** Refreshes the remote catalog when its six-hour cache has expired. */
    @Synchronized
    fun refreshSupportedModelIds(): Set<String> = modelCatalog(allowRemoteRefresh = true).keys

    /**
     * The model catalog is separate from the plugin binary. A catalog update can add a model or
     * point an existing model ID at a new version without rebuilding/reinstalling this APK.
     * A bundled catalog is used as an offline fallback; the remote catalog is refreshed at most
     * once per six hours and cached in the plugin's private directory.
     */
    @Synchronized
    private fun modelCatalog(allowRemoteRefresh: Boolean = false): Map<String, ModelSpec> {
        var current = catalogCache
        if (current == null) {
            current = loadCachedOrBundledCatalog()
            catalogCache = current
        }

        val catalogUrl = BuildConfig.OCR_MODEL_CATALOG_URL.trim()
        val lastUpdated = if (catalogFile.isFile) catalogFile.lastModified() else 0L
        val refreshReference = maxOf(lastUpdated, lastCatalogRefreshAttemptMs)
        val isStale = refreshReference == 0L || System.currentTimeMillis() - refreshReference >= CATALOG_REFRESH_INTERVAL_MS
        if (allowRemoteRefresh && catalogUrl.isNotBlank() && isStale) {
            lastCatalogRefreshAttemptMs = System.currentTimeMillis()
            val refreshStartedAt = SystemClock.elapsedRealtime()
            logModel("MODEL_CATALOG_REFRESH_START", "urlConfigured=true;stale=true")
            val refreshed = runCatching {
                require(catalogUrl.startsWith("https://")) { "OCR 模型目录必须使用 HTTPS" }
                val content = downloadCatalog(catalogUrl)
                parseCatalog(content).let { parsed -> content to parsed }
            }.onFailure { failure ->
                Log.w(LOG_TAG, "OCR_MODEL_STAGE stage=MODEL_CATALOG_REFRESH_FAILED;durationMs=${SystemClock.elapsedRealtime() - refreshStartedAt};type=${failure.javaClass.simpleName};message=${failure.message}", failure)
            }.getOrNull()
            if (refreshed != null && refreshed.second.isNotEmpty()) {
                runCatching { persistCatalog(refreshed.first) }
                current = refreshed.second
                catalogCache = refreshed.second
                logModel("MODEL_CATALOG_REFRESH_SUCCESS", "models=${refreshed.second.size};durationMs=${SystemClock.elapsedRealtime() - refreshStartedAt}")
            } else if (current.isNullOrEmpty()) {
                throw IllegalStateException("无法读取 OCR 模型目录，且没有可用的内置目录")
            } else {
                logModel("MODEL_CATALOG_FALLBACK", "source=cached_or_bundled;durationMs=${SystemClock.elapsedRealtime() - refreshStartedAt}")
            }
        }
        return current.orEmpty()
    }

    private fun loadCachedOrBundledCatalog(): Map<String, ModelSpec> {
        val cached = if (catalogFile.isFile) {
            runCatching { parseCatalog(catalogFile.readText()) }.getOrNull()
        } else null
        if (!cached.isNullOrEmpty()) return cached
        if (catalogFile.exists()) catalogFile.delete()
        return appContext.assets.open(BUNDLED_CATALOG_ASSET).bufferedReader().use { reader ->
            parseCatalog(reader.readText())
        }
    }

    private fun downloadCatalog(rawUrl: String): String {
        val connection = openHttpsConnection(rawUrl)
        try {
            val length = connection.contentLengthLong
            if (length > 0L) require(length <= MAX_CATALOG_BYTES) { "OCR 模型目录文件大小异常" }
            return connection.inputStream.use { input ->
                val output = StringBuilder()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_CATALOG_BYTES) { "OCR 模型目录超过允许大小" }
                    output.append(String(buffer, 0, count, Charsets.UTF_8))
                }
                require(total > 0) { "OCR 模型目录为空" }
                if (length > 0L) require(total.toLong() == length) { "OCR 模型目录下载不完整" }
                output.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCatalog(content: String): Map<String, ModelSpec> {
        val root = JSONObject(content)
        require(root.optInt("schemaVersion", -1) == CATALOG_SCHEMA_VERSION) {
            "不支持的 OCR 模型目录格式"
        }
        val models = root.optJSONArray("models") ?: JSONArray()
        require(models.length() in 1..MAX_CATALOG_MODELS) { "OCR 模型目录没有有效模型" }
        val result = LinkedHashMap<String, ModelSpec>()
        for (index in 0 until models.length()) {
            val item = models.optJSONObject(index) ?: continue
            // This plugin only contains the Paddle ONNX engine. Entries for other engines or
            // engine API versions are deliberately ignored rather than loaded incorrectly.
            if (item.optString("engineId") != ENGINE_ID || item.optInt("engineApiVersion", -1) != ENGINE_API_VERSION) {
                continue
            }
            val modelId = item.getString("modelId")
            require(modelId.matches(Regex("[a-z0-9_-]{2,64}"))) { "OCR 模型 ID 不合法" }
            val version = item.getString("version")
            require(version.isNotBlank() && version.length <= 80) { "OCR 模型版本号不合法" }
            val detUrl = item.getString("detUrl")
            val recUrl = item.getString("recUrl")
            val recConfigUrl = item.getString("recConfigUrl")
            listOf(detUrl, recUrl, recConfigUrl).forEach { url ->
                require(url.startsWith("https://")) { "OCR 模型文件地址必须使用 HTTPS" }
            }
            val sizes = item.optJSONArray("minimumSizes") ?: throw IllegalArgumentException("模型缺少文件大小要求")
            require(sizes.length() == 3) { "模型文件大小配置必须包含 3 项" }
            val minimumSizes = (0 until sizes.length()).map { sizes.getLong(it) }
            require(minimumSizes.all { it in 1L..MAX_MODEL_FILE_BYTES }) { "OCR 模型文件大小配置不合法" }
            val hashes = item.optJSONObject("sha256") ?: throw IllegalArgumentException("模型缺少 SHA-256 校验值")
            val expectedSha256 = listOf(
                hashes.getString("det"),
                hashes.getString("rec"),
                hashes.getString("recConfig")
            )
            require(expectedSha256.all { it.matches(Regex("[0-9a-fA-F]{64}")) }) {
                "OCR 模型 SHA-256 配置不合法"
            }
            result[modelId] = ModelSpec(
                modelId = modelId,
                version = version,
                detUrl = detUrl,
                recUrl = recUrl,
                recConfigUrl = recConfigUrl,
                minimumSizes = minimumSizes,
                expectedSha256 = expectedSha256
            )
        }
        require(result.isNotEmpty()) { "OCR 模型目录中没有当前插件可运行的模型" }
        return result
    }

    private fun persistCatalog(content: String) {
        ocrRoot.mkdirs()
        val temporary = File(ocrRoot, "$CATALOG_FILE_NAME.part")
        // Keep the exact validated JSON so model additions can be delivered without an APK update.
        parseCatalog(content)
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (catalogFile.exists()) check(catalogFile.delete()) { "无法替换旧的 OCR 模型目录缓存" }
        check(temporary.renameTo(catalogFile)) { "无法保存 OCR 模型目录缓存" }
    }

    private fun isInstalledAndValid(directory: File, spec: ModelSpec): Boolean {
        if (!directory.isDirectory) return false
        val manifestFile = File(directory, MODEL_MANIFEST_FILE)
        if (!manifestFile.isFile) return false
        return runCatching {
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optString("version") != spec.version) return@runCatching false
            if (manifest.optString("engineId") != ENGINE_ID) return@runCatching false
            val files = requiredFiles(directory)
            val hashKeys = listOf("detSha256", "recSha256", "recConfigSha256")
            for (index in files.indices) {
                val file = files[index]
                if (!file.isFile || file.length() < spec.minimumSizes[index]) return@runCatching false
                val recordedHash = manifest.optString(hashKeys[index])
                val pinnedHash = spec.expectedSha256[index]
                if (!recordedHash.equals(pinnedHash, ignoreCase = true)) return@runCatching false
                if (!recordedHash.equals(sha256(file), ignoreCase = true)) return@runCatching false
            }
            true
        }.getOrDefault(false)
    }

    private fun downloadAndInstall(spec: ModelSpec, destination: File) {
        modelsRoot.mkdirs()
        check(modelsRoot.isDirectory) { "无法创建 OCR 模型目录" }
        val staging = File(modelsRoot, "${spec.modelId}.staging")
        val backup = File(modelsRoot, "${spec.modelId}.previous")
        staging.deleteRecursively()

        // Recover the last complete model if the app was stopped between the two directory renames.
        if (!destination.exists() && backup.exists()) backup.renameTo(destination)
        backup.deleteRecursively()

        try {
            val detFile = File(staging, "det/$DET_FILE_NAME")
            val recFile = File(staging, "rec/$REC_FILE_NAME")
            val configFile = File(staging, "rec/$REC_CONFIG_FILE_NAME")
            val bundledAssetPaths = listOf(
                "models/${spec.modelId}/det/$DET_FILE_NAME",
                "models/${spec.modelId}/rec/$REC_FILE_NAME",
                "models/${spec.modelId}/rec/$REC_CONFIG_FILE_NAME"
            )
            val destinations = listOf(detFile, recFile, configFile)
            val hasBundledAssets = bundledAssetPaths.all { assetPath ->
                runCatching { appContext.assets.open(assetPath).use { } }.isSuccess
            }
            if (hasBundledAssets) {
                logModel("MODEL_INSTALL_SOURCE", "source=apk_assets;modelId=${spec.modelId}")
                bundledAssetPaths.forEachIndexed { index, assetPath ->
                    copyBundledAsset(assetPath, destinations[index], spec.minimumSizes[index])
                }
            } else {
                logModel("MODEL_INSTALL_SOURCE", "source=network_fallback;modelId=${spec.modelId}")
                downloadFile(spec.detUrl, detFile, spec.minimumSizes[0])
                downloadFile(spec.recUrl, recFile, spec.minimumSizes[1])
                downloadFile(spec.recConfigUrl, configFile, spec.minimumSizes[2])
            }

            val hashStartedAt = SystemClock.elapsedRealtime()
            logModel("MODEL_HASH_VERIFY_START", "modelId=${spec.modelId}")
            val downloadedHashes = listOf(sha256(detFile), sha256(recFile), sha256(configFile))
            logModel("MODEL_HASH_VERIFY_END", "modelId=${spec.modelId};durationMs=${SystemClock.elapsedRealtime() - hashStartedAt}")
            spec.expectedSha256.forEachIndexed { index, expected ->
                check(downloadedHashes[index].equals(expected, ignoreCase = true)) {
                    "模型文件校验失败：${listOf(DET_FILE_NAME, REC_FILE_NAME, REC_CONFIG_FILE_NAME)[index]}"
                }
            }

            val manifest = JSONObject()
                .put("modelId", spec.modelId)
                .put("version", spec.version)
                .put("engineId", ENGINE_ID)
                .put("detSha256", downloadedHashes[0])
                .put("recSha256", downloadedHashes[1])
                .put("recConfigSha256", downloadedHashes[2])
            File(staging, MODEL_MANIFEST_FILE).writeText(manifest.toString())

            if (destination.exists()) {
                check(destination.renameTo(backup)) { "无法备份当前 OCR 模型" }
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw IllegalStateException("无法启用下载完成的 OCR 模型")
            }
            backup.deleteRecursively()
        } catch (failure: Exception) {
            staging.deleteRecursively()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw IllegalStateException(
                "${spec.modelId} 模型下载或安装失败：${failure.message ?: failure.javaClass.simpleName}",
                failure
            )
        }
    }

    private fun modelFiles(modelId: String, version: String, directory: File) = ModelFiles(
        modelId = modelId,
        version = version,
        detectionModel = File(directory, "det/$DET_FILE_NAME"),
        recognitionModel = File(directory, "rec/$REC_FILE_NAME"),
        recognitionConfig = File(directory, "rec/$REC_CONFIG_FILE_NAME")
    )

    private fun requiredFiles(directory: File): List<File> = listOf(
        File(directory, "det/$DET_FILE_NAME"),
        File(directory, "rec/$REC_FILE_NAME"),
        File(directory, "rec/$REC_CONFIG_FILE_NAME")
    )

    private fun copyBundledAsset(assetPath: String, destination: File, minimumBytes: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        try {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(temporary.length() >= minimumBytes) {
                "内置模型文件不完整：$assetPath (${temporary.length()} bytes)"
            }
            if (destination.exists()) check(destination.delete()) { "无法替换旧内置模型文件" }
            check(temporary.renameTo(destination)) { "无法安装内置模型文件：$assetPath" }
            logModel("MODEL_ASSET_INSTALL_SUCCESS", "asset=$assetPath;bytes=${destination.length()};durationMs=${SystemClock.elapsedRealtime() - startedAt}")
        } catch (failure: Exception) {
            temporary.delete()
            Log.e(LOG_TAG, "OCR_MODEL_STAGE stage=MODEL_ASSET_INSTALL_FAILED;asset=$assetPath;type=${failure.javaClass.simpleName};message=${failure.message}", failure)
            throw failure
        }
    }

    private fun downloadFile(rawUrl: String, destination: File, minimumBytes: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        logModel("MODEL_FILE_DOWNLOAD_START", "file=${destination.name};minimumBytes=$minimumBytes")
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        val connection = openHttpsConnection(rawUrl)
        try {
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0L) {
                check(declaredLength >= minimumBytes && declaredLength <= MAX_MODEL_FILE_BYTES) {
                    "模型文件大小不符合预期"
                }
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_MODEL_FILE_BYTES) { "模型文件超过允许大小" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    check(total >= minimumBytes) { "模型文件下载不完整" }
                    if (declaredLength > 0L) check(total == declaredLength) { "模型文件长度不一致" }
                }
            }
            if (destination.exists()) check(destination.delete()) { "无法替换旧模型文件" }
            check(temporary.renameTo(destination)) { "无法保存模型文件：${destination.name}" }
            logModel("MODEL_FILE_DOWNLOAD_SUCCESS", "file=${destination.name};bytes=${destination.length()};durationMs=${SystemClock.elapsedRealtime() - startedAt}")
        } catch (failure: Exception) {
            temporary.delete()
            Log.e(LOG_TAG, "OCR_MODEL_STAGE stage=MODEL_FILE_DOWNLOAD_FAILED;file=${destination.name};durationMs=${SystemClock.elapsedRealtime() - startedAt};type=${failure.javaClass.simpleName};message=${failure.message}", failure)
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpsConnection(rawUrl: String): HttpURLConnection {
        var target = URL(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            check(target.protocol.equals("https", ignoreCase = true)) {
                "OCR 下载重定向到非 HTTPS 地址，已拒绝继续"
            }
            val connectStartedAt = SystemClock.elapsedRealtime()
            logModel("MODEL_HTTP_CONNECT_START", "host=${target.host};redirect=$redirectCount")
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Freedom-OCR-Model-Manager")
                connect()
            }
            val responseCode = connection.responseCode
            logModel("MODEL_HTTP_RESPONSE", "host=${target.host};status=$responseCode;durationMs=${SystemClock.elapsedRealtime() - connectStartedAt}")
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(redirectCount < MAX_REDIRECTS && !location.isNullOrBlank()) {
                    "OCR 下载重定向次数过多或缺少 Location"
                }
                target = URL(target, location)
            } else {
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("OCR 服务器返回 HTTP $responseCode")
                }
                return connection
            }
        }
        throw IllegalStateException("OCR 下载重定向次数过多")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun logModel(stage: String, details: String = "") {
        Log.i(
            LOG_TAG,
            "OCR_MODEL_STAGE stage=$stage;pid=${android.os.Process.myPid()};" +
                "thread=${Thread.currentThread().name};$details",
        )
    }

    private data class ModelSpec(
        val modelId: String,
        val version: String,
        val detUrl: String,
        val recUrl: String,
        val recConfigUrl: String,
        val minimumSizes: List<Long>,
        val expectedSha256: List<String>
    ) {
        fun validationFingerprint(): String =
            "$modelId|$version|${expectedSha256.joinToString("|")}"
    }

    companion object {
        private const val LOG_TAG = "FreedomOcrModel"
        private const val ENGINE_ID = "paddle-onnx"
        private const val ENGINE_API_VERSION = 1
        private const val CATALOG_SCHEMA_VERSION = 1
        private const val MAX_CATALOG_MODELS = 32
        private const val CATALOG_FILE_NAME = "model-catalog.json"
        private const val BUNDLED_CATALOG_ASSET = "ocr-model-catalog.json"
        private const val MODEL_MANIFEST_FILE = "model-manifest.json"
        private const val DET_FILE_NAME = "inference.onnx"
        private const val REC_FILE_NAME = "inference.onnx"
        private const val REC_CONFIG_FILE_NAME = "inference.yml"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 180_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_MODEL_FILE_BYTES = 100L * 1024L * 1024L
        private const val MAX_CATALOG_BYTES = 512 * 1024
        private const val CATALOG_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
