package com.freedom.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.freedom.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface OcrPluginInstallAction {
    data class InstallerOpened(val packageName: String, val versionName: String) : OcrPluginInstallAction
    data object UnknownSourcesPermissionRequired : OcrPluginInstallAction
}

data class DownloadedOcrPluginApk(
    val file: File,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

data class OcrPluginRelease(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String?,
    val sha256: String?,
    val apkVariants: Map<String, Pair<String, String>>,
) {
    fun assetForDevice(): Pair<String, String> {
        for (abi in Build.SUPPORTED_ABIS) apkVariants[abi]?.let { return it }
        apkVariants["universal"]?.let { return it }
        val url = apkUrl ?: throw OcrPluginException("插件发布清单没有提供当前设备可用的 APK")
        val hash = sha256 ?: throw OcrPluginException("插件发布清单缺少 APK SHA-256")
        return url to hash
    }
}

data class OcrPluginCatalogEntry(
    val packageName: String,
    val pluginId: String,
    val title: String,
    val description: String,
    val engine: String?,
    val variant: String?,
    val releases: List<OcrPluginRelease>,
) {
    val latestRelease: OcrPluginRelease? get() = releases.maxByOrNull { it.versionCode }
}

/**
 * AutoJs6-inspired OCR plugin catalog/installer adapter. Catalog entries are package-oriented,
 * allowing Freedom to discover and install more than one OCR engine/profile without baking the
 * plugin list into the host APK. APK bytes are cached under the host app's private files directory
 * and passed to Android's normal package installer (install/uninstall are never silent).
 */
class OcrPluginInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val catalogCache = File(appContext.filesDir, CATALOG_CACHE_NAME)

    fun loadCatalog(): List<OcrPluginCatalogEntry> {
        val url = BuildConfig.OCR_PLUGIN_CATALOG_URL.trim()
        if (url.isBlank()) {
            return cachedCatalogOrEmpty()
        }
        require(url.startsWith("https://")) { "OCR 插件目录必须使用 HTTPS" }
        val refreshed = runCatching { downloadText(url, MAX_CATALOG_BYTES) }
        val content = refreshed.getOrElse { failure ->
            val cached = cachedCatalogOrEmpty()
            if (cached.isNotEmpty()) return cached
            throw OcrPluginException("无法读取 OCR 插件目录：${failure.message}", failure)
        }
        val entries = parseCatalog(content)
        require(entries.isNotEmpty()) { "OCR 插件目录没有可安装的插件" }
        runCatching {
            val temporary = File(catalogCache.parentFile, catalogCache.name + ".part")
            temporary.writeText(content)
            if (catalogCache.exists()) check(catalogCache.delete())
            check(temporary.renameTo(catalogCache))
        }
        return entries
    }

    /** Blocking network operation. Invoke from an IO/background thread. */
    fun downloadPluginApk(packageName: String): DownloadedOcrPluginApk {
        val entry = loadCatalog().firstOrNull { it.packageName == packageName }
            ?: throw OcrPluginException("OCR 插件目录中没有找到：$packageName")
        val release = entry.latestRelease ?: throw OcrPluginException("该 OCR 插件没有可安装的发行版本")
        require(release.versionName.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) { "插件版本号格式不合法" }
        require(release.versionCode > 0L) { "插件发行版缺少有效 versionCode" }
        val (apkUrl, expectedSha256) = release.assetForDevice()
        require(apkUrl.startsWith("https://")) { "插件 APK 下载地址必须使用 HTTPS" }
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "插件 APK SHA-256 格式不合法" }

        val directory = File(appContext.filesDir, "ocr-plugin-apks").apply { mkdirs() }
        check(directory.isDirectory) { "无法创建插件 APK 缓存目录" }
        val apkFile = File(directory, "${safeFileName(packageName)}-${release.versionName}.apk")
        downloadFile(apkUrl, apkFile, MAX_PLUGIN_APK_BYTES)
        require(sha256(apkFile).equals(expectedSha256, ignoreCase = true)) {
            "OCR 插件 APK SHA-256 校验失败，已拒绝安装"
        }
        val archiveInfo = packageInfoFromArchive(apkFile)
        require(archiveInfo.packageName == packageName) { "下载的 APK 包名与插件目录不匹配" }
        require(archiveInfo.versionName == release.versionName) { "下载的 APK versionName 与插件目录不匹配" }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        require(archiveVersionCode == release.versionCode) { "下载的 APK versionCode 与插件目录不匹配" }
        return DownloadedOcrPluginApk(apkFile, packageName, release.versionName, release.versionCode)
    }

    /** Backward-compatible convenience for the first Freedom PP-OCR plugin. */
    fun downloadLatestPluginApk(): DownloadedOcrPluginApk = downloadPluginApk(DEFAULT_PLUGIN_PACKAGE)

    /** Starts Android's package installer. Invoke from an Activity's main thread. */
    fun requestSystemInstall(activity: Activity, downloaded: DownloadedOcrPluginApk): OcrPluginInstallAction {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return OcrPluginInstallAction.UnknownSourcesPermissionRequired
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${appContext.packageName}.ocr.plugin.fileprovider",
            downloaded.file,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        return OcrPluginInstallAction.InstallerOpened(downloaded.packageName, downloaded.versionName)
    }

    private fun cachedCatalogOrEmpty(): List<OcrPluginCatalogEntry> =
        if (catalogCache.isFile) runCatching { parseCatalog(catalogCache.readText()) }.getOrDefault(emptyList())
        else emptyList()

    private fun parseCatalog(content: String): List<OcrPluginCatalogEntry> {
        val root = JSONObject(content)
        require(root.optInt("schemaVersion", 0) == 1) { "不支持的 OCR 插件目录版本" }
        val array = root.optJSONArray("plugins") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val packageName = obj.optString("packageName").trim()
                val pluginId = obj.optString("pluginId").trim()
                if (packageName.isBlank() || pluginId.isBlank()) continue
                val releaseArray = obj.optJSONArray("releases") ?: JSONArray()
                val releases = buildList {
                    for (releaseIndex in 0 until releaseArray.length()) {
                        val release = releaseArray.optJSONObject(releaseIndex) ?: continue
                        val versionName = release.optString("versionName").trim()
                        val versionCode = release.optLong("versionCode", 0L)
                        if (versionName.isBlank() || versionCode <= 0L) continue
                        val variants = LinkedHashMap<String, Pair<String, String>>()
                        val variantArray = release.optJSONArray("apkVariants") ?: JSONArray()
                        for (variantIndex in 0 until variantArray.length()) {
                            val variant = variantArray.optJSONObject(variantIndex) ?: continue
                            val abi = variant.optString("abi").trim()
                            val apkUrl = variant.optString("apkUrl").trim()
                            val hash = variant.optString("sha256").trim()
                            if (abi.isNotBlank() && apkUrl.isNotBlank() && hash.isNotBlank()) {
                                variants[abi] = apkUrl to hash
                            }
                        }
                        add(
                            OcrPluginRelease(
                                versionName = versionName,
                                versionCode = versionCode,
                                apkUrl = release.optString("apkUrl").takeIf { it.startsWith("https://") },
                                sha256 = release.optString("sha256").takeIf { it.isNotBlank() },
                                apkVariants = variants,
                            ),
                        )
                    }
                }
                add(
                    OcrPluginCatalogEntry(
                        packageName = packageName,
                        pluginId = pluginId,
                        title = obj.optString("title").ifBlank { packageName },
                        description = obj.optString("description"),
                        engine = obj.optString("engine").takeIf { it.isNotBlank() },
                        variant = obj.optString("variant").takeIf { it.isNotBlank() },
                        releases = releases,
                    ),
                )
            }
        }
    }

    private fun packageInfoFromArchive(apkFile: File) = if (Build.VERSION.SDK_INT >= 33) {
        appContext.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.PackageInfoFlags.of(0L),
        ) ?: throw OcrPluginException("无法读取已下载的 OCR 插件 APK")
    } else {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw OcrPluginException("无法读取已下载的 OCR 插件 APK")
    }

    private fun downloadText(rawUrl: String, maxBytes: Int): String {
        val connection = openConnection(rawUrl)
        try {
            val length = connection.contentLengthLong
            if (length > 0L) require(length <= maxBytes) { "OCR 插件目录文件过大" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(if (length in 1..maxBytes.toLong()) length.toInt() else 4096)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "OCR 插件目录超过大小限制" }
                    output.write(buffer, 0, count)
                }
                require(total > 0) { "OCR 插件目录为空" }
                if (length > 0L) require(total.toLong() == length) { "OCR 插件目录下载不完整" }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(rawUrl: String, destination: File, maxBytes: Long) {
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        val connection = openConnection(rawUrl)
        try {
            val length = connection.contentLengthLong
            require(length > 0L && length <= maxBytes) { "OCR 插件 APK 大小异常" }
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "OCR 插件 APK 超过允许大小" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    require(total == length) { "OCR 插件 APK 下载不完整" }
                }
            }
            if (destination.exists()) check(destination.delete()) { "无法替换旧版 OCR 插件 APK" }
            check(temporary.renameTo(destination)) { "无法保存 OCR 插件 APK" }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(rawUrl: String): HttpURLConnection {
        var target = URL(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(target.protocol.equals("https", ignoreCase = true)) { "插件下载重定向到非 HTTPS 地址" }
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Freedom-OCR-Plugin-Manager")
                connect()
            }
            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(redirectCount < MAX_REDIRECTS && !location.isNullOrBlank()) { "插件下载重定向次数过多" }
                target = URL(target, location)
            } else {
                require(connection.responseCode in 200..299) { "插件下载失败：HTTP ${connection.responseCode}" }
                return connection
            }
        }
        throw OcrPluginException("插件下载重定向次数过多")
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

    private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val DEFAULT_PLUGIN_PACKAGE = "com.freedom.ocr.plugin"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val CATALOG_CACHE_NAME = "ocr-plugin-catalog.json"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_CATALOG_BYTES = 256 * 1024
        private const val MAX_PLUGIN_APK_BYTES = 180L * 1024L * 1024L
    }
}
