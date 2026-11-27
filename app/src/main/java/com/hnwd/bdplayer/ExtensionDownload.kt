package com.hnwd.bdplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.hnwd.bdplayer.extension.api.IDownloadCallback
import com.hnwd.bdplayer.extension.api.IDownloadService
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ExtensionShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.action == "com.hnwd.bdplayer.action.EXTENSION_SHARE" &&
            ExtensionDownload.installed(this)
        ) {
            val link = VideoCollectorLogic.parse(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            if (link != null && ExtensionDownload.available(this, link.platform.name)) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link.canonicalUrl)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
        finish()
    }
}

internal object ExtensionDownload {
    const val PACKAGE = "com.hnwd.bdplayer_ext_share"
    private const val PERMISSION = "$PACKAGE.BIND_DOWNLOAD"
    private val component = ComponentName(PACKAGE, "com.hnwd.bdplayer.extension.DownloadService")

    fun supports(platform: String): Boolean = CollectorSite.fromPlatform(platform) != null

    fun requiresLocalExtension(platform: String): Boolean =
        CollectorSite.fromPlatform(platform)?.allowLegacyParser == false

    fun available(context: Context, platform: String): Boolean {
        val site = CollectorSite.fromPlatform(platform) ?: return false
        if (!installed(context)) return false
        if (site.minExtensionVersion <= 1) return true
        return runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode >= site.minExtensionVersion
        }.getOrDefault(false)
    }

    fun installed(context: Context): Boolean =
        context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH &&
            context.packageManager.checkPermission(PERMISSION, context.packageName) == PackageManager.PERMISSION_GRANTED

    class LoginRequired : IllegalStateException(LOGIN_MESSAGE)
    const val LOGIN_MESSAGE = "站点要求登录或会话已失效"

    fun download(
        context: Context, taskId: Long, platform: String, url: String, cookies: String,
        destination: File, checkActive: () -> Unit, progress: (String, Int) -> Unit,
    ): String {
        check(available(context, platform)) { "请安装或更新与拾课签名一致的分享下载扩展" }
        val attempt = UUID.randomUUID().toString()
        val connected = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val lastStateAt = AtomicLong(SystemClock.elapsedRealtime())
        var remote: IDownloadService? = null
        var result: Pair<String, Bundle>? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = IDownloadService.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected()
            override fun onBindingDied(name: ComponentName) = disconnected()
            override fun onNullBinding(name: ComponentName) = disconnected()

            private fun disconnected() {
                connected.countDown()
                terminal.countDown()
            }
        }
        val callback = object : IDownloadCallback.Stub() {
            override fun onState(id: String, attemptId: String, state: String, details: Bundle) {
                if (id != taskId.toString() || attemptId != attempt || terminal.count == 0L) return
                lastStateAt.set(SystemClock.elapsedRealtime())
                when (state) {
                    "PARSING" -> progress("正在解析视频…", 0)
                    "DOWNLOADING" -> progress("正在下载视频…", details.getInt("percent").coerceIn(0, 100))
                    "MERGING" -> progress("正在合并音视频…", 99)
                    "COPYING" -> progress("正在交接视频…", 99)
                    "READY", "LOGIN_REQUIRED", "FAILED", "CANCELLED" -> {
                        result = state to details
                        terminal.countDown()
                    }
                }
            }
        }
        val bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            error("无法连接分享下载扩展")
        }
        var accepted = false
        try {
            check(connected.await(10, TimeUnit.SECONDS)) { "连接分享下载扩展超时" }
            val service = checkNotNull(remote) { "分享下载扩展已断开" }
            val capabilities = service.capabilities()
            check(capabilities.getInt("protocol") == 1 && platform in capabilities.getStringArray("sites").orEmpty()) {
                "分享下载扩展版本不兼容，请更新扩展"
            }
            checkActive()
            ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { descriptor ->
                accepted = service.start(taskId.toString(), attempt, url, cookies, descriptor, callback)
            }
            check(accepted) { "扩展未接受任务，请确认站点会话并稍后重试" }
            while (!terminal.await(500, TimeUnit.MILLISECONDS)) {
                checkActive()
                check(SystemClock.elapsedRealtime() - lastStateAt.get() < TimeUnit.MINUTES.toMillis(10)) {
                    "分享下载长时间没有进展，请重试"
                }
            }
            checkActive()
            val (state, details) = checkNotNull(result) { "分享下载扩展已断开，请重试" }
            when (state) {
                "LOGIN_REQUIRED" -> throw LoginRequired()
                "CANCELLED" -> error("分享下载已取消")
                "FAILED" -> error(when (details.getString("code")) {
                    "LOW_SPACE" -> "扩展可用空间不足"
                    "ENGINE_UNAVAILABLE" -> "无法启动扩展的下载引擎"
                    "INVALID_ARTIFACT" -> "扩展未生成完整的 MP4 文件"
                    "NO_VIDEO" -> "未找到可下载的视频，请确认这是视频笔记或重新分享链接"
                    "LIVE_NOT_READY" -> "直播结束并生成回放后即可下载"
                    else -> "扩展下载失败，请检查链接或站点状态"
                })
            }
            check(state == "READY" && details.getString("format") == "mp4" &&
                destination.length() > 0 && destination.length() == details.getLong("bytes") &&
                hasRequiredTracks(destination, requireAudio = platform != CollectorSite.XIAOHONGSHU.name)) {
                "扩展交接的视频文件不完整"
            }
            return details.getString("name").orEmpty().ifBlank { "分享视频.mp4" }
        } finally {
            if (accepted && terminal.count != 0L) runCatching {
                remote?.cancel(taskId.toString(), attempt)
                terminal.await(5, TimeUnit.SECONDS)
            }
            context.unbindService(connection)
        }
    }

    private fun hasRequiredTracks(file: File, requireAudio: Boolean): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val types = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            types.any { it.startsWith("audio/") } && types.any { it.startsWith("video/") }
        } finally {
            extractor.release()
        }
    }
}
