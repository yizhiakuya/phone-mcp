package top.rainaki.phonemcp.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import top.rainaki.phonemcp.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App 内自更新。
 *
 * Android 不允许应用静默安装自己——系统一定会弹一次安装确认。所以这里把
 * "下载 → 找文件 → 点安装" 全自动化，只留最后一次系统确认给用户点。
 *
 * 流程：
 *   1. 拉版本清单（version.json），比对 versionCode
 *   2. 下载 APK 到应用私有 cache 目录
 *   3. 校验 MD5（防止下到半截或中间被替换）
 *   4. 通过 FileProvider 交给系统安装器
 */
class Updater(private val activity: Activity) {

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val md5: String,
        val sizeBytes: Long,
        val changelog: String,
    )

    sealed interface CheckResult {
        data class Available(val release: Release) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    /** 检查更新。网络操作，必须在后台线程调用。 */
    fun check(): CheckResult {
        return try {
            val base = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
            if (base.isBlank()) {
                return CheckResult.Failed(
                    "未配置更新源。构建时用 -PupdateBaseUrl=<地址> 指定后重新打包。"
                )
            }
            val json = httpGetText("$base/version.json")
            val obj = JSONObject(json)

            val remoteCode = obj.optInt("versionCode", 0)
            val apkPath = obj.optString("apk", "/apk/phonemcp-latest.apk")
            val release = Release(
                versionCode = remoteCode,
                versionName = obj.optString("versionName", "?"),
                apkUrl = if (apkPath.startsWith("http")) apkPath else "$base$apkPath",
                md5 = obj.optString("md5", "").lowercase(),
                sizeBytes = obj.optLong("sizeBytes", 0),
                changelog = obj.optString("changelog", ""),
            )

            if (remoteCode <= BuildConfig.VERSION_CODE) {
                CheckResult.UpToDate
            } else {
                CheckResult.Available(release)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "check failed", t)
            CheckResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 下载并校验，返回本地 APK 文件。
     * 后台线程调用；进度通过 onProgress(已下载, 总大小) 回调。
     */
    data class DownloadResult(val file: File, val reused: Boolean)

    /** 本地是否已有该版本可用的安装包（不联网，供 UI 提前显示"已下载"）。 */
    fun hasDownloaded(release: Release): Boolean {
        val target = File(File(activity.cacheDir, "updates"), "phonemcp-${release.versionName}.apk")
        return isUsable(target, release)
    }

    fun download(release: Release, onProgress: (Long, Long) -> Unit = { _, _ -> }): DownloadResult {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        // 每次用版本号命名，避免旧文件残留混淆
        val target = File(dir, "phonemcp-${release.versionName}.apk")

        // 已经下过且校验通过就直接复用：用户点了"稍后"或退出 App 再回来，
        // 不该重新下一遍。只有文件不存在 / 大小或 MD5 对不上才重下。
        if (target.exists() && isUsable(target, release)) {
            onProgress(target.length(), target.length())
            return DownloadResult(target, reused = true)
        }
        if (target.exists()) target.delete()

        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("下载失败：HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes

            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (release.md5.isNotBlank()) {
            val actual = md5Of(target)
            if (!actual.equals(release.md5, ignoreCase = true)) {
                target.delete()
                throw IllegalStateException("校验失败：MD5 不匹配（期望 ${release.md5}，实际 $actual）")
            }
        }
        return DownloadResult(target, reused = false)
    }

    /**
     * 拉起系统安装器。返回 null 表示成功拉起，否则返回需要用户先处理的提示。
     */
    fun install(apk: File): String? {
        // Android 8+ 需要"安装未知应用"权限。没开就先引导去开。
        if (!activity.packageManager.canRequestPackageInstalls()) {
            return "NEED_PERMISSION"
        }
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            null
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    /** 跳转到本应用的"安装未知应用"授权页。 */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 判断本地已下载的 APK 是否可直接复用。
     *
     * 先比大小（便宜），大小一致再比 MD5（贵但可靠）。清单没给 MD5 时
     * 只靠大小判断，并额外确认文件非空。
     */
    private fun isUsable(file: File, release: Release): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes) return false
        if (release.md5.isNotBlank()) {
            return runCatching { md5Of(file).equals(release.md5, ignoreCase = true) }
                .getOrDefault(false)
        }
        return true
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            // 版本清单可能被 CDN/nginx 缓存，加个时间戳绕过
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "Updater"
    }
}
