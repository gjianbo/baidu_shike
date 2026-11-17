package com.hnwd.bdplayer

import android.app.Activity
import android.net.Uri
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import java.io.File
import org.json.JSONObject

/**
 * 文本 / 通用文档预览需要的宿主能力。取址与验证码复用 [MediaLinkSection]，
 * 这里只留账号校验、状态栏、一次请求和两个导航出口（应用内阅读 / 交系统）。
 */
internal interface DocumentPreviewHost {
    val hostActivity: Activity

    fun setStatus(message: String)
    fun say(message: String)
    fun isVisible(): Boolean
    fun isDestroyed(): Boolean
    fun isReady(): Boolean
    fun account(): String
    fun accountVerified(): Boolean
    fun sessionReady(provider: SourceProvider): Boolean
    /** dlink 签名绑定生成它的 WebView UA，下载必须原样带上；主线程读取。 */
    fun userAgent(): String
    fun playbackAccount(entry: PanEntry): String
    fun bumpListRequest()
    fun verifyAccount()
    fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit)
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit)
    /** 纯文本：打开应用内阅读页。 */
    fun openTextReader(entry: PanEntry, file: File)
    /** Office / 压缩包：把下载好的本地文件交给系统应用。 */
    fun openDocumentExternally(entry: PanEntry, file: File)

    fun fetchJson(
        url: String,
        post: Boolean = false,
        form: String? = null,
        statusHint: String? = null,
        lane: RequestLane,
        onFailure: () -> Unit = {},
        onResult: (JSONObject) -> Unit,
    )
}

/**
 * 文本 / 通用文档预览：缓存命中直接用 -> 取原文件 dlink（群组 sharedownload / 网盘 /api/download）
 * -> 下载校验 -> 按类型分流到应用内阅读器或系统应用。
 *
 * 三条不变式：
 * 1. 每一步都过 [isCurrent]，账号/页面状态变了就丢弃（下载慢，最容易撞上切号）。
 * 2. 链接失效（[InvalidDocumentLink]）时清地址缓存并重取一次，只退一级，不无限重试。
 * 3. 同一时刻只允许一个下载弹窗与一次后台下载。
 *
 * 注意：不走 docview/docpreview —— 那是 PDF 转码专用接口。文本与 Office 直接取原文件 dlink，
 * 与图片原图链路一致；百度 dlink 的签名绑定 WebView UA，下载必须透传宿主 UA。
 */
internal class DocumentPreviewSection(
    private val host: DocumentPreviewHost,
    private val mediaLinks: MediaLinkSection,
    private val addresses: MediaAddressCache,
) {
    private val activity: Activity get() = host.hostActivity
    private val cache by lazy { PreviewCache.get(activity) }
    private var openId = 0
    private var loading: AlertDialog? = null
    private val fetcher by lazy { DocumentFileFetcher(host.hostActivity) }

    /** 账号失效 / 页面不可见：中止进行中的加载。 */
    fun close() {
        openId++
        fetcher.cancel()
        loading?.dismiss()
        loading = null
    }

    /** Activity 销毁：连下载线程一起释放。 */
    fun release() {
        close()
        fetcher.close()
    }

    fun open(entry: PanEntry) {
        val quark = entry.provider == SourceProvider.QUARK
        if (if (quark) !host.sessionReady(SourceProvider.QUARK) else !host.accountVerified()) {
            host.setStatus("请确认网盘登录后重试。")
            return
        }
        val id = ++openId
        host.bumpListRequest()
        mediaLinks.dismissCaptcha(RequestLane.LIST)
        if (loading != null) {
            fetcher.cancel()
            loading?.dismiss()
            loading = null
        }
        val account = host.playbackAccount(entry)
        val key = mediaCacheKey(account, entry)
        val target = documentTarget(entry)
        val ext = entry.name.substringAfterLast('.', "").lowercase().ifEmpty { "txt" }
        val cacheName = "$key.$ext"
        cache.acquire(PreviewCache.Kind.DOCUMENT, cacheName)?.let {
            host.setStatus("")
            openCached(entry, target, it)
            return
        }
        if (quark) {
            download(entry, target, account, id, key, cacheName, ext)
            return
        }
        addresses.link(key)?.let {
            download(entry, target, account, id, key, cacheName, ext, it)
            return
        }
        if (!host.isReady()) {
            host.setStatus("请确认网盘登录后重试。")
            return
        }
        host.setStatus("正在确认网盘账号…")
        host.evaluateJavascript(
            "location.origin === 'https://pan.baidu.com' ? String(window.yunData?.MYUK || window.yunData?.uk || '') : ''",
        ) { result ->
            if (!isCurrent(id, account)) return@evaluateJavascript
            val viewer = parseViewerUk(result)
            if (viewer != null && isNumericUk(viewer)) {
                requestLink(entry, target, viewer, id, key, cacheName, ext)
            } else {
                host.fetchJson(PanApi.viewer(), lane = RequestLane.LIST, onFailure = {
                    if (isCurrent(id, account)) host.setStatus(VIEWER_UK_ERROR)
                }) { json ->
                    if (!isCurrent(id, account)) return@fetchJson
                    val uk = runCatching { requireViewerUk(viewerUkFromJson(json)) }.getOrNull()
                    if (uk != null) requestLink(entry, target, uk, id, key, cacheName, ext)
                    else host.setStatus(VIEWER_UK_ERROR)
                }
            }
        }
    }

    private fun isCurrent(id: Int, account: String, provider: SourceProvider = SourceProvider.BAIDU) =
        previewStillCurrent(
            id, openId,
            destroyed = { host.isDestroyed() },
            visible = { host.isVisible() },
            accountMatches = {
                if (provider == SourceProvider.QUARK) {
                    account == AccountScope.current && host.sessionReady(provider)
                } else {
                    account == host.account() && host.accountVerified()
                }
            },
        )

    private fun requestLink(
        entry: PanEntry,
        target: DocumentTarget,
        viewer: String,
        id: Int,
        key: String,
        cacheName: String,
        ext: String,
        retried: Boolean = false,
    ) {
        if (viewer != host.account()) {
            host.verifyAccount()
            return
        }
        fun isCurrent() = isCurrent(id, viewer)
        val fail = { if (isCurrent()) host.setStatus("文档打开失败，请重试。") }
        fun receive(link: Uri) {
            if (!isCurrent()) return
            addresses.saveLink(key, link)
            download(entry, target, viewer, id, key, cacheName, ext, link, retried = retried)
        }
        // 文本 / Office 没有 docview 转码接口，直接取原文件 dlink：群组 sharedownload，网盘 /api/download。
        if (!entry.isDisk) {
            mediaLinks.resolve(entry, viewer, lane = RequestLane.LIST, onFailure = fail) { receive(it) }
            return
        }
        host.withBdstoken(
            onReady = { token ->
                if (isCurrent()) {
                    mediaLinks.requestDiskLink(
                        entry, token, RequestLane.LIST, ::isCurrent, onFailure = fail,
                    ) { receive(it) }
                }
            },
            onFailure = fail,
        )
    }

    private fun download(
        entry: PanEntry,
        target: DocumentTarget,
        account: String,
        id: Int,
        key: String,
        cacheName: String,
        ext: String,
        link: Uri? = null,
        retried: Boolean = false,
    ) {
        val quark = entry.provider == SourceProvider.QUARK
        if (!isCurrent(id, account, entry.provider)) return
        loading?.dismiss()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setMessage("正在加载文档…")
            .setView(activity.padded(bar))
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                openId++
                fetcher.cancel()
                loading = null
                host.setStatus("")
            }
            .show()
        loading = dialog
        fun paint(progress: PdfProgress) {
            if (loading !== dialog) return
            val percent = pdfProgressPercent(progress.bytesDone, progress.bytesTotal)
            bar.isIndeterminate = percent < 0
            if (percent >= 0) bar.progress = percent
            dialog.setMessage(documentProgressText(progress.bytesDone, progress.bytesTotal))
        }
        fun complete(result: Result<File>) {
            if (loading === dialog) {
                dialog.dismiss()
                loading = null
            }
            if (!isCurrent(id, account, entry.provider)) {
                result.getOrNull()?.let(cache::discard)
                return
            }
            result.mapCatching { temp ->
                try {
                    openCached(entry, target, cache.publish(PreviewCache.Kind.DOCUMENT, cacheName, temp))
                } finally {
                    cache.discard(temp)
                }
            }.onFailure { error ->
                val plan = documentFailurePlan(
                    quark = quark,
                    invalidLink = error is InvalidDocumentLink,
                    hostReady = { host.isReady() },
                    retried = retried,
                )
                if (plan.clearCachedLink) addresses.clearLink(key)
                if (plan.action == DocumentRetry.RETRY_LINK) {
                    // 最多重取一次：第二次失败时 retried 已为 true，降级为 SHOW_ERROR。
                    requestLink(entry, target, account, id, key, cacheName, ext, retried = true)
                    return@onFailure
                }
                val message = documentFailureMessage(error)
                host.setStatus(message)
                if (quark) host.say(message)
            }
        }
        if (quark) {
            fetcher.startQuark(entry.fileId, entry.name, target, ::paint, ::complete)
        } else {
            fetcher.start(requireNotNull(link), host.userAgent(), target, ::paint, ::complete)
        }
    }

    private fun openCached(entry: PanEntry, target: DocumentTarget, file: File) {
        try {
            if (target == DocumentTarget.TEXT_READER) host.openTextReader(entry, file)
            else host.openDocumentExternally(entry, file)
        } finally {
            cache.release(file)
        }
    }
}

/** 文本走应用内阅读，Office / 压缩包交系统。 */
internal fun documentTarget(entry: PanEntry): DocumentTarget =
    if (PanApi.kindOf(entry) == MediaKind.TEXT) DocumentTarget.TEXT_READER else DocumentTarget.SYSTEM
