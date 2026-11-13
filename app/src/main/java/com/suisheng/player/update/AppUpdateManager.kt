package com.suisheng.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.suisheng.player.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdateInfo(
    val version: String,
    val versionCode: Int,
    /**
     * 首选下载地址：GitHub 资产 API（api.github.com 域名）。
     * 国内网络下 github.com/releases/download 常被拦截，而 api.github.com 一般可通，
     * 该地址会 302 到 release-assets.githubusercontent.com 返回二进制。
     */
    val apkUrl: String,
    /** 备用下载地址：浏览器用的 release 直链，API 通道失败时再试 */
    val backupUrl: String,
    /** 期望的 SHA-256（小写十六进制）；为空表示发布方未提供，只做大小校验 */
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
) {
    val isNewer: Boolean
        get() = if (versionCode > 0) {
            versionCode > BuildConfig.VERSION_CODE
        } else {
            compareVersions(version, BuildConfig.VERSION_NAME) > 0
        }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }
}

sealed interface InstallRequest {
    data object Launched : InstallRequest
    data class PermissionRequired(val intent: Intent) : InstallRequest
}

/**
 * 更新检查走 GitHub Releases：仓库的「最新正式 Release」就是发布入口。
 * 发布新版本时只需在 GitHub 上发 Release 并上传签名 APK，客户端即可发现。
 *
 * 依赖 Release 里两项约定：
 * 1. 附件名以 `.apk` 结尾（当前为 `suisheng-<version>-signed.apk`）
 * 2. 说明正文里写 `versionCode = <数字>`，用于跨版本比较（GitHub 本身不提供 versionCode）
 */
class AppUpdateManager(private val context: Context) {

    suspend fun check(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val connection = open(RELEASE_API_URL)
        try {
            val code = connection.responseCode
            check(code in 200..299) {
                when (code) {
                    403, 429 -> "检查更新失败：请求过于频繁，请稍后再试"
                    404 -> "检查更新失败：尚未发布可用的版本"
                    else -> "检查更新失败（$code）"
                }
            }
            check(connection.url.protocol == "https") { "更新服务被重定向到不安全地址" }
            val json = JSONObject(
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
            )

            val tag = json.getString("tag_name").trim().removePrefix("v").removePrefix("V")
            val notes = json.optString("body", "")

            val assets = json.optJSONArray("assets")
            check(assets != null && assets.length() > 0) { "该版本没有可下载的安装包" }
            var apiUrl: String? = null
            var browserUrl = ""
            var sizeBytes = 0L
            var digest = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apiUrl = asset.optString("url", "")
                browserUrl = asset.optString("browser_download_url", "")
                sizeBytes = asset.optLong("size", 0L)
                digest = asset.optString("digest", "")
                break
            }
            val downloadUrl = apiUrl?.takeIf { it.isNotBlank() }
                ?: browserUrl.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("该版本没有可下载的安装包")
            check(URL(downloadUrl).protocol == "https") { "更新地址不安全" }
            check(sizeBytes > 0L) { "更新包大小无效" }

            val sha256 = if (digest.startsWith("sha256:")) {
                digest.substringAfter("sha256:").lowercase()
            } else {
                ""
            }
            if (sha256.isNotEmpty()) {
                check(sha256.matches(Regex("^[a-f0-9]{64}$"))) { "更新包校验信息无效" }
            }

            AppUpdateInfo(
                version = tag,
                versionCode = parseVersionCode(notes),
                apkUrl = downloadUrl,
                backupUrl = browserUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                notes = notes,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVersionCode(notes: String): Int =
        Regex("versionCode\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(notes)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1

    suspend fun download(info: AppUpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val candidates = listOf(info.apkUrl, info.backupUrl)
                .filter { it.isNotBlank() }
                .distinct()
            var lastError: Throwable? = null
            for (url in candidates) {
                try {
                    return@withContext downloadFrom(url, info, onProgress)
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastError = error
                }
            }
            throw lastError ?: IllegalStateException("没有可用的下载地址")
        }

    private fun downloadFrom(
        url: String,
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit,
    ): File {
        // 资产 API 需要 octet-stream 才会返回二进制，否则返回的是 JSON 元数据
        val connection = open(url, accept = "application/octet-stream")
        val updateDir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updateDir, UpdateFilePolicy.apkFileName(info.version))
        val partial = File(updateDir, "${target.name}.download")
        try {
            check(connection.responseCode in 200..299) { "下载更新失败（${connection.responseCode}）" }
            check(connection.url.protocol == "https") { "更新下载被重定向到不安全地址" }
            val expected = when {
                info.sizeBytes > 0 -> info.sizeBytes
                connection.contentLengthLong > 0 -> connection.contentLengthLong
                else -> -1L
            }
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastProgress = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (expected > 0) {
                            val progress = ((copied * 100L) / expected).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            if (info.sizeBytes > 0) {
                check(partial.length() == info.sizeBytes) { "更新包大小校验失败" }
            }
            if (info.sha256.isNotEmpty()) {
                check(UpdateIntegrity.sha256(partial) == info.sha256) { "更新包完整性校验失败" }
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "无法保存更新包" }
            onProgress(100)
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun requestInstall(apk: File): InstallRequest {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallRequest.PermissionRequired(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update_files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return InstallRequest.Launched
    }

    private fun open(
        rawUrl: String,
        accept: String = "application/vnd.github+json, application/json",
    ): HttpURLConnection =
        (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "MP3Player/${BuildConfig.VERSION_NAME}")
        }

    companion object {
        private const val REPO = "ylayljzmm/mp3-"

        /** GitHub「最新正式 Release」接口，未认证访问限流 60 次/小时，足够日常检查 */
        const val RELEASE_API_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /** 手动下载页：自动检查失败时可直接用浏览器打开 */
        const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases"
    }
}
