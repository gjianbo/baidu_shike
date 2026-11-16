package com.hnwd.bdplayer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 版本检测 / 下载 / 验签 / 安装。
 *
 * 所有回调都发生在 AppIo 的后台线程上，UI 层自行 runOnUiThread。判定逻辑全在
 * [UpdateLogic.kt]，这里只做 IO 与 Android API 接线。
 *
 * 优先使用 releases.atom，避免消耗匿名 API 配额。版本列表或 APK 探测失败时，
 * 改用 GitHub REST API，它在版本列表里直接提供已上传的 APK 信息。
 */
internal object UpdateManager {

    private const val PREF = "app_update"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_IGNORED = "ignored_version"
    private const val APK_DIR = "update"
    private const val USER_AGENT = "bdplayer-update-checker"
    private const val HEX = "0123456789abcdef"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    private val checkClient by lazy {
        client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun lastCheckAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)
    fun markChecked(context: Context, now: Long) =
        prefs(context).edit { putLong(KEY_LAST_CHECK, now) }
    fun ignoredVersion(context: Context): String? = prefs(context).getString(KEY_IGNORED, null)
    fun ignoreVersion(context: Context, version: String) =
        prefs(context).edit { putString(KEY_IGNORED, version) }

    @Suppress("DEPRECATION")
    fun currentVersion(context: Context): AppVersion? = runCatching {
        parseAppVersion(
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        )
    }.getOrDefault(null)

    data class ReleaseInfo(
        val version: String,
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
    )

    sealed class CheckResult {
        data class Available(val info: ReleaseInfo) : CheckResult()
        object UpToDate : CheckResult()
        /** 24h 内不重复联网。 */
        object Skipped : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    class CheckRequest {
        private var currentCall: Call? = null
        @Volatile var isCancelled = false
            private set

        @Synchronized
        fun cancel() {
            isCancelled = true
            currentCall?.cancel()
        }

        fun <T> execute(request: Request, read: (Response) -> T): T {
            val call = checkClient.newCall(request)
            synchronized(this) {
                if (isCancelled) throw IOException("检查更新已取消")
                currentCall = call
            }
            try {
                return call.execute().use(read)
            } finally {
                synchronized(this) { currentCall = null }
            }
        }
    }

    /**
     * [force] 为手动检查：跳过 24h 节流（忽略版本的判定仍由 UI 层用 [shouldNotify] 决定）。
     */
    fun check(context: Context, force: Boolean, check: CheckRequest, onResult: (CheckResult) -> Unit) {
        val now = System.currentTimeMillis()
        if (!force && !shouldAutoCheck(lastCheckAt(context), now)) {
            onResult(CheckResult.Skipped)
            return
        }
        AppIo.execute {
            if (check.isCancelled) return@execute
            val result = check.checkBlocking(context) ?: return@execute
            synchronized(check) {
                if (check.isCancelled) return@execute
                if (result !is CheckResult.Failed) markChecked(context, now)
            }
            onResult(result)
        }
    }

    private fun CheckRequest.checkBlocking(context: Context): CheckResult? {
        val current = currentVersion(context) ?: return CheckResult.Failed("无法读取当前版本。")
        try {
            return checkAtom(current)
        } catch (error: IOException) {
            if (isCancelled) return null
            Log.w("UpdateManager", "GitHub Atom 检查失败，尝试 REST API", error)
        }
        try {
            return checkApi(current)
        } catch (error: IOException) {
            if (isCancelled) return null
            Log.w("UpdateManager", "GitHub REST API 检查失败", error)
        } catch (error: JSONException) {
            Log.w("UpdateManager", "无法解析 GitHub REST API 响应", error)
        }
        return CheckResult.Failed("暂时无法从 GitHub 获取版本信息。请检查网络后重试，或在浏览器中查看发布页。")
    }

    private fun CheckRequest.fetchReleaseData(url: String, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Cache-Control", "no-cache")
            .build()
        return execute(request) { response ->
            if (!response.isSuccessful) throw IOException("GitHub 返回 HTTP ${response.code}")
            response.body?.string() ?: throw IOException("GitHub 响应为空")
        }
    }

    private fun CheckRequest.checkAtom(current: AppVersion): CheckResult {
        val xml = fetchReleaseData(UPDATE_ATOM_URL, "application/atom+xml")
        if (!xml.contains("<feed") || !xml.contains("</feed>")) throw IOException("无效的 Atom 响应")
        val candidates = candidateReleases(parseReleaseAtom(xml), current).take(UPDATE_MAX_PROBE)
        for (release in candidates) {
            val version = release.version.toString()
            val url = apkDownloadUrl(release.tag, version)
            if (assetExists(url)) {
                return CheckResult.Available(
                    ReleaseInfo(version, release.tag, release.title, stripHtmlNotes(release.notesHtml), url)
                )
            }
        }
        return CheckResult.UpToDate
    }

    private fun CheckRequest.checkApi(current: AppVersion): CheckResult {
        val json = fetchReleaseData(UPDATE_API_URL, "application/vnd.github+json")
        val release = newestApiRelease(json, current) ?: return CheckResult.UpToDate
        val version = release.version.toString()
        return CheckResult.Available(
            ReleaseInfo(version, release.tag, release.title, release.notes, apkDownloadUrl(release.tag, version))
        )
    }

    /** 用 Range: bytes=0-0 探测，比 HEAD 更不容易被 CDN 拒绝，且不会真的下载整个包。 */
    private fun CheckRequest.assetExists(url: String): Boolean {
        val probe = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT)
            .build()
        return execute(probe) { response ->
            when {
                response.isSuccessful -> true
                response.code == 404 -> false
                else -> throw IOException("APK 探测返回 HTTP ${response.code}")
            }
        }
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        object Cancelled : DownloadResult()
        data class Failed(val message: String) : DownloadResult()
    }

    /**
     * [onProgress] 只在百分比变化时回调（最多约 100 次）。[cancelled] 由 UI 在关闭弹窗时置位。
     */
    fun download(
        context: Context,
        info: ReleaseInfo,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        onDone: (DownloadResult) -> Unit,
    ) {
        AppIo.execute {
            onDone(downloadBlocking(context, info, cancelled, onProgress))
        }
    }

    private fun downloadBlocking(
        context: Context,
        info: ReleaseInfo,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult {
        val dir = File(context.cacheDir, APK_DIR)
        if (!dir.exists() && !dir.mkdirs()) return DownloadResult.Failed("无法创建下载目录")
        dir.listFiles()?.filter { it.name.endsWith(".apk") || it.name.endsWith(".part") }
            ?.forEach { it.delete() }
        val dest = File(dir, "$UPDATE_APK_NAME_PREFIX${info.version}$UPDATE_APK_NAME_SUFFIX")
        val tmp = File(dir, "${dest.name}.part")
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            var stopped = false
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadResult.Failed("下载失败（HTTP ${response.code}）")
                }
                val body = response.body ?: return DownloadResult.Failed("下载响应为空")
                val total = body.contentLength()
                var done = 0L
                var lastPercent = -2
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) {
                                stopped = true
                                break
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val percent = downloadPercent(done, total)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(done, total)
                            }
                        }
                        out.flush()
                    }
                }
            }
            when {
                stopped || cancelled() -> {
                    tmp.delete()
                    DownloadResult.Cancelled
                }
                !verifySignature(context, tmp) -> {
                    tmp.delete()
                    DownloadResult.Failed("安装包签名与当前应用不一致，已取消安装")
                }
                !tmp.renameTo(dest) -> {
                    tmp.delete()
                    DownloadResult.Failed("安装包保存失败")
                }
                else -> DownloadResult.Success(dest)
            }
        } catch (e: Exception) {
            tmp.delete()
            DownloadResult.Failed(e.message ?: "下载失败")
        }
    }

    /**
     * 覆盖安装要求签名证书完全一致。CI 与本地发版目前共用同一 keystore
     * （V2 证书 SHA-256 75ec2ca0…6055），一旦哪天换了签名，这一步会拦住而不是
     * 让用户装到 INSTALL_FAILED_UPDATE_INCOMPATIBLE 才失败。
     */
    private fun verifySignature(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return@runCatching false
        val mine = signerDigests(installed)
        mine.isNotEmpty() && mine == signerDigests(archive)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers: Array<Signature> = info.signingInfo?.apkContentsSigners
            ?: info.signatures
            ?: emptyArray()
        return signers.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    sealed class InstallResult {
        object Launched : InstallResult()
        object NeedPermission : InstallResult()
        data class Failed(val message: String) : InstallResult()
    }

    fun install(context: Context, file: File): InstallResult {
        if (!file.exists() || file.length() == 0L) {
            return InstallResult.Failed("安装包已丢失，请重新下载")
        }
        // minSdk 29，canRequestPackageInstalls 恒可用，不需要 SDK 版本判断。
        if (!context.packageManager.canRequestPackageInstalls()) {
            return InstallResult.NeedPermission
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.Launched
        }.getOrElse { InstallResult.Failed(it.message ?: "无法调起安装器") }
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 国内直连 GitHub 失败时的兜底：让用户用浏览器去 Release 页下载。 */
    fun openReleasePage(context: Context, tag: String? = null) {
        val intent = Intent(Intent.ACTION_VIEW, releasePageUrl(tag).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
