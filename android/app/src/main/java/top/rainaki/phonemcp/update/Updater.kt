package top.rainaki.phonemcp.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
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
 * ## 更新源是 GitHub Releases
 *
 * 直接从 Releases API 读最新版本，不再依赖自建服务器的版本清单。
 * 好处是发版只需推 GitHub 一处，不用同步两份元数据。
 *
 * 流程：
 *   1. 调 `GET /repos/{owner}/{repo}/releases/latest` 拿最新 release
 *   2. 从 tag 名（如 `v1.1.18`）解析版本号，与本地 versionCode 比对
 *   3. 下载 release 里的 APK 资产
 *   4. 校验大小与 MD5（清单里的 md5 由 release body 或本地资产摘要提供）
 *   5. 通过 FileProvider 交给系统安装器
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
            if (BuildConfig.UPDATE_REPO.isBlank()) {
                return CheckResult.Failed(
                    "未配置更新仓库。构建时用 -PupdateRepo=<owner/repo> 指定后重新打包。"
                )
            }
            val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
            val json = httpGetText(api)
            val obj = JSONObject(json)

            val tag = obj.optString("tag_name", "")
            val versionName = tag.removePrefix("v").trim()
            if (versionName.isBlank()) {
                return CheckResult.Failed("release 没有 tag 名，无法判断版本")
            }

            // 找 .apk 资产
            val assets = obj.optJSONArray("assets") ?: JSONArray()
            var apkUrl = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    size = a.optLong("size", 0)
                    break
                }
            }
            if (apkUrl.isBlank()) {
                return CheckResult.Failed("release $tag 里没有 APK 资产")
            }

            val body = obj.optString("body", "")
            // 机器可读的元数据只从尾部的 ```meta 块里取。
            // 正文（changelog）里可能顺口提到版本号，全局正则扫会抓到错的。
            val meta = parseMeta(body)
            val release = Release(
                versionCode = meta["versioncode"]?.toIntOrNull() ?: 0,
                versionName = versionName,
                apkUrl = apkUrl,
                md5 = meta["md5"].orEmpty().lowercase(),
                sizeBytes = meta["size"]?.toLongOrNull()?.takeIf { it > 0 } ?: size,
                changelog = stripMeta(body).take(1500),
            )

            if (!isNewer(versionName, release.versionCode)) {
                CheckResult.UpToDate
            } else {
                CheckResult.Available(release)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "check failed", t)
            CheckResult.Failed(friendlyError(t))
        }
    }

    /**
     * 判断远端版本是否比本地新。
     *
     * **不能把 versionName 换算成 versionCode 再比**——本项目里 versionCode 是
     * 单调自增的整数（49、50、51…），和 `1.1.19` 这种三段式版本名没有换算关系。
     * 早期版本按 `major*10000+minor*100+patch` 算，得出 10119，永远大于本地的 50，
     * 于是每次检查都提示有新版。
     *
     * 优先用 release 说明里显式写的 `versionCode: N`（发版脚本会写），
     * 没有就按版本名逐段比较。
     */
    private fun isNewer(remoteName: String, remoteCode: Int): Boolean {
        if (remoteCode > 0) return remoteCode > BuildConfig.VERSION_CODE
        val remote = parseVersion(remoteName)
        val local = parseVersion(BuildConfig.VERSION_NAME)
        if (remote == null || local == null) {
            // 版本名解析不出来时保守处理：当作没有更新，避免反复提示安装同一版
            return false
        }
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    /** `1.1.19` → [1, 1, 19]。解析失败返回 null。 */
    private fun parseVersion(name: String): List<Int>? {
        val parts = name.trim().removePrefix("v").split(".")
        val nums = parts.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        return nums.takeIf { it.isNotEmpty() }
    }

    /**
     * 解析尾部 ```meta 代码块里的键值对。
     *
     * 发版脚本会写：
     * ```
     * versionCode: 51
     * versionName: 1.1.20
     * size: 23847802
     * md5: 9fd3d5...
     * ```
     * 只在 meta 块内解析，不扫全文——正文里出现的数字不算数。
     */
    private fun parseMeta(body: String): Map<String, String> {
        val m = Regex("""```meta\s*\n([\s\S]*?)```""").find(body) ?: return emptyMap()
        return m.groupValues[1].lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val k = line.substring(0, idx).trim().lowercase()
                val v = line.substring(idx + 1).trim()
                if (k.isEmpty() || v.isEmpty()) null else k to v
            }
            .toMap()
    }

    /** 去掉 meta 块，剩下的才是给用户看的正文。 */
    private fun stripMeta(body: String): String =
        body.replace(Regex("""```meta\s*\n[\s\S]*?```"""), "").trim()

    /** GitHub 的错误信息对用户没意义，翻译成能看懂的说法。 */
    private fun friendlyError(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return when {
            msg.contains("403") || msg.contains("rate limit", true) ->
                "GitHub 请求过于频繁，请稍后再试"
            msg.contains("404") -> "更新仓库不存在或还没有发布过 release"
            msg.contains("Unable to resolve host", true) ||
                msg.contains("UnknownHost", true) -> "无法连接 GitHub，检查网络"
            else -> msg
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

        // 校验必须做，不能"没有 md5 就跳过"。
        // 缺失 md5 说明 release 元数据不完整或被篡改——宁可拒绝，也不要装一个
        // 未经校验的包。真正防篡改的是签名（同签名才能覆盖安装），
        // 但这里多一道完整性检查能挡住下载中断和中间人替换。
        if (release.md5.isBlank()) {
            target.delete()
            throw IllegalStateException(
                "更新包缺少校验信息（release 说明里没有 md5），已拒绝安装。"
            )
        }
        val actual = md5Of(target)
        if (!actual.equals(release.md5, ignoreCase = true)) {
            target.delete()
            throw IllegalStateException("校验失败：MD5 不匹配（期望 ${release.md5}，实际 $actual）")
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
        // 没有 md5 就认为不可复用——必须重新下载并校验
        if (release.md5.isBlank()) return false
        return runCatching { md5Of(file).equals(release.md5, ignoreCase = true) }
            .getOrDefault(false)
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
