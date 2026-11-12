package com.hnwd.bdplayer

import android.app.Activity
import android.net.Uri

/** 取址链路需要的最小宿主能力；隐藏 WebView 与账号状态仍归 MainActivity。 */
internal interface DownloadHost {
    val hostActivity: Activity

    fun account(): String
    fun userAgent(): String
    fun sessionReady(provider: SourceProvider): Boolean
    fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit)
    fun verifyAccount()
    fun isCurrent(): Boolean
    fun say(message: String)
    fun setStatus(message: String)
    fun runOnUiThread(action: () -> Unit)
    fun onAdded(name: String)
}

/**
 * 长按「下载」的取址分派：复用媒体取址层的 sharedownload / /api/download，
 * 夸克则直接走 file/download。拿到直链后交给 [DownloadCenter] 排队，不自己传字节。
 *
 * 用独立的 [RequestLane.DOWNLOAD]，避免取址时把正在跑的列表请求顶掉；
 * MainActivity.load() 对该 lane 也放开了并发（不比较计数器），因此连续下载多个文件
 * 不会互相取消。
 */
internal class DownloadSection(
    private val host: DownloadHost,
    private val mediaLinks: MediaLinkSection,
) {
    fun start(entry: PanEntry) {
        if (!DownloadLogic.canDownload(entry)) {
            host.say("文件夹不能下载")
            return
        }
        if (!host.sessionReady(entry.provider)) {
            host.say(if (entry.provider == SourceProvider.QUARK) "请先登录夸克网盘" else "请先登录百度网盘")
            return
        }
        val route = DownloadLogic.routeOf(entry)
        val userAgent = host.userAgent()

        fun receive(uri: Uri) {
            if (!host.isCurrent()) return
            // 取址开始时写过状态栏提示，成功/失败都要收尾，否则「正在获取下载地址…」
            // 会一直挂在页面上误导用户（项目里其它页面也是用空串清状态栏）。
            host.setStatus("")
            val task = DownloadTask(
                id = DownloadCenter.newId(),
                name = entry.name,
                sizeBytes = entry.size.toLongOrNull() ?: 0L,
                provider = entry.provider,
                sourceLabel = DownloadLogic.sourceLabel(route),
                createdAt = System.currentTimeMillis(),
                url = uri.toString(),
                userAgent = userAgent,
                referer = DownloadLogic.headers(route, userAgent).first { it.first == "Referer" }.second,
                mediaType = DownloadLogic.mimeType(entry.name),
            )
            DownloadCenter.add(host.hostActivity, task)
            host.onAdded(entry.name)
        }

        fun fail(message: String) {
            if (!host.isCurrent()) return
            host.setStatus("")
            host.say(message)
        }

        when (route) {
            DownloadRoute.QUARK -> AppIo.execute {
                val result = runCatching {
                    val drive = QuarkPersonalDrive.openSession(userAgent) ?: throw QuarkLoginRequired()
                    drive.download(QuarkFile(entry.fileId, entry.name, false))
                }
                host.runOnUiThread {
                    result.onSuccess(::receive).onFailure { fail(quarkError(it)) }
                }
            }
            DownloadRoute.BAIDU_GROUP -> {
                val viewer = host.account()
                if (viewer.isEmpty()) {
                    host.verifyAccount()
                    host.say("账号校验中，请稍后再试下载")
                    return
                }
                host.setStatus("正在获取下载地址…")
                mediaLinks.resolve(
                    entry, viewer, lane = RequestLane.DOWNLOAD,
                    onFailure = { fail("无法获取下载地址，请重试") },
                ) { receive(it) }
            }
            DownloadRoute.BAIDU_DISK -> {
                host.setStatus("正在获取下载地址…")
                host.withBdstoken(
                    onReady = { token ->
                        if (!host.isCurrent()) return@withBdstoken
                        mediaLinks.requestDiskLink(
                            entry, token, RequestLane.DOWNLOAD, { host.isCurrent() },
                            onFailure = { fail("无法获取下载地址，请重试") },
                        ) { receive(it) }
                    },
                    onFailure = { fail("网盘会话未就绪，请稍后重试") },
                )
            }
        }
    }

    private fun quarkError(error: Throwable): String = when {
        error is QuarkLoginRequired -> "请先登录夸克网盘"
        error.message?.contains("23018") == true -> "夸克网盘非会员单个文件下载上限约 50MB"
        else -> "无法获取夸克下载地址：${error.message ?: "请重试"}"
    }
}
