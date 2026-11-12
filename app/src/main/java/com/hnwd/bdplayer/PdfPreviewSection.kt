package com.hnwd.bdplayer

import android.app.Activity
import android.net.Uri
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import java.io.File
import org.json.JSONObject

/**
 * 文档预览需要的宿主能力。取址与验证码直接复用 [MediaLinkSection]，
 * 这里只留账号校验、状态栏、一次请求和「打开阅读器」这一个导航出口。
 */
internal interface PdfPreviewHost {
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
    /** 该条目该记在哪个账号名下（夸克走 AccountScope，百度走已校验账号）。 */
    fun playbackAccount(entry: PanEntry): String
    /** 作废进行中的列表请求（PDF 取址与列表共用 LIST 通道）。 */
    fun bumpListRequest()
    fun verifyAccount()
    fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit)
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit)
    fun openPdfReader(entry: PanEntry, file: File)

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
 * 文档（PDF）预览：缓存命中直接用 -> 取 dlink（docview / 群预览）-> 失败退回下载取址 -> 下载校验 -> 打开阅读器。
 *
 * 三条不变式：
 * 1. 每一步都先过 [isCurrentPdf]，账号/页面状态变了就丢弃（PDF 下载慢，最容易撞上切号）。
 * 2. 下载失败若是链接失效（[InvalidPdfLink]），按 fromCache -> docview -> 下载取址 的顺序退一级，最多退两次。
 * 3. 同一时刻只允许一个下载弹窗与一次后台下载。
 */
internal class PdfPreviewSection(
    private val host: PdfPreviewHost,
    private val mediaLinks: MediaLinkSection,
    private val addresses: MediaAddressCache,
) {
    private val activity: Activity get() = host.hostActivity
    private val cache by lazy { PreviewCache.get(activity) }
    private var openId = 0
    private var loading: AlertDialog? = null
    private val fetcher by lazy { PdfFileFetcher(host.hostActivity) }

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
        // 夸克 pdf/preview 给的是原件直链且非会员有约 50MB 的官方下载上限，直接给准确提示；
        // 百度 docview 常把 240MB 原件转成 29.6MB 副本，按原始大小预拦会误伤，
        // 实际要下多少只有响应头的 Content-Length 知道，判定放在 PdfFileFetcher。
        if (quark && quarkPdfTooLarge(entry.size)) {
            host.setStatus("")
            host.say(QUARK_PDF_TOO_LARGE_MESSAGE)
            return
        }
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
        cache.acquire(PreviewCache.Kind.PDF, "$key.pdf")?.let {
            host.setStatus("")
            openCachedPdf(entry, it)
            return
        }
        if (quark) {
            downloadPdf(entry, account, id, key)
            return
        }
        addresses.link(key)?.let {
            downloadPdf(entry, account, id, key, it, fromCache = true)
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
            if (!isCurrentPdf(id, account)) return@evaluateJavascript
            val viewer = parseViewerUk(result)
            if (viewer != null && isNumericUk(viewer)) {
                requestPdfLink(entry, viewer, id)
            } else {
                host.fetchJson(PanApi.viewer(), lane = RequestLane.LIST) { json ->
                    if (!isCurrentPdf(id, account)) return@fetchJson
                    val uk = requireViewerUk(viewerUkFromJson(json))
                    requestPdfLink(entry, uk, id)
                }
            }
        }
    }

    private fun isCurrentPdf(id: Int, account: String, provider: SourceProvider = SourceProvider.BAIDU) =
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

    private fun requestPdfLink(entry: PanEntry, viewer: String, id: Int) {
        if (viewer != host.account()) {
            host.verifyAccount()
            return
        }
        val key = mediaCacheKey(viewer, entry)
        fun receive(link: Uri) {
            if (!isCurrentPdf(id, viewer)) return
            addresses.saveLink(key, link)
            downloadPdf(entry, viewer, id, key, link, fromCache = false)
        }
        if (entry.isDisk) {
            host.fetchJson(
                PanApi.diskDocview(entry, viewer), lane = RequestLane.LIST,
                statusHint = "正在获取文档地址…",
                onFailure = { requestPdfFallback(entry, viewer, id) },
            ) { json ->
                if (!isCurrentPdf(id, viewer)) return@fetchJson
                if (json.optInt("need_passwd", 0) == 1) {
                    host.setStatus("该文档需要密码，当前版本暂不支持。")
                    return@fetchJson
                }
                val link = runCatching { PanApi.parseDocviewLink(json) }.getOrElse {
                    requestPdfFallback(entry, viewer, id)
                    return@fetchJson
                }
                receive(link)
            }
            return
        }
        if (!entry.isContact && entry.owner.isNotEmpty()) {
            host.fetchJson(
                PanApi.groupPdfPreview(entry), post = true, form = PanApi.groupPdfPreviewForm(entry),
                lane = RequestLane.LIST, statusHint = "正在获取文档地址…",
                onFailure = { requestPdfFallback(entry, viewer, id) },
            ) { json ->
                val link = PanApi.parseGroupPreviewLink(json)
                if (link == null) requestPdfFallback(entry, viewer, id) else receive(link)
            }
        } else {
            requestPdfFallback(entry, viewer, id)
        }
    }

    /** 预览接口不可用时退回下载取址：群文件走 sharedownload，网盘走 /api/download。 */
    private fun requestPdfFallback(entry: PanEntry, viewer: String, id: Int) {
        fun isCurrent() = isCurrentPdf(id, viewer)
        if (!isCurrent()) return
        val key = mediaCacheKey(viewer, entry)
        val fail = { if (isCurrent()) host.setStatus("文档打开失败，请重试。") }
        fun receive(link: Uri) {
            if (!isCurrent()) return
            addresses.saveLink(key, link)
            downloadPdf(entry, viewer, id, key, link, fromCache = false, fromFallback = true)
        }
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

    private fun downloadPdf(
        entry: PanEntry,
        account: String,
        id: Int,
        key: String,
        link: Uri? = null,
        fromCache: Boolean = false,
        fromFallback: Boolean = false,
    ) {
        val quark = entry.provider == SourceProvider.QUARK
        if (!isCurrentPdf(id, account, entry.provider)) return
        loading?.dismiss()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            // 拿到 Content-Length 之前只转不确定进度；拿到后切成百分比。
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
            // 弹窗已被取消/替换时，迟到的进度回调不能再刷新 UI。
            if (loading !== dialog) return
            val percent = pdfProgressPercent(progress.bytesDone, progress.bytesTotal)
            bar.isIndeterminate = percent < 0
            if (percent >= 0) bar.progress = percent
            dialog.setMessage(pdfProgressText(progress.bytesDone, progress.bytesTotal))
        }
        fun complete(result: Result<File>) {
            if (loading === dialog) {
                dialog.dismiss()
                loading = null
            }
            if (!isCurrentPdf(id, account, entry.provider)) {
                result.getOrNull()?.let(cache::discard)
                return
            }
            result.mapCatching { temp ->
                try {
                    openCachedPdf(entry, cache.publish(PreviewCache.Kind.PDF, "$key.pdf", temp))
                } finally {
                    cache.discard(temp)
                }
            }.onFailure {
                val plan = pdfFailurePlan(
                    quark = quark,
                    invalidLink = it is InvalidPdfLink,
                    hostReady = { host.isReady() },
                    fromCache = fromCache,
                    fromFallback = fromFallback,
                )
                if (plan.clearCachedLink) addresses.clearLink(key)
                when (plan.action) {
                    PdfRetry.RETRY_LINK -> {
                        requestPdfLink(entry, account, id)
                        return@onFailure
                    }
                    PdfRetry.RETRY_FALLBACK -> {
                        requestPdfFallback(entry, account, id)
                        return@onFailure
                    }
                    PdfRetry.SHOW_ERROR -> Unit
                }
                val message = pdfFailureMessage(it)
                host.setStatus(message)
                if (quark) host.say(message)
            }
        }
        if (quark) fetcher.startQuark(entry.fileId, { paint(it) }, ::complete)
        else fetcher.start(requireNotNull(link), host.userAgent(), { paint(it) }, ::complete)
    }

    private fun openCachedPdf(entry: PanEntry, file: File) {
        try {
            host.openPdfReader(entry, file)
        } finally {
            cache.release(file)
        }
    }
}
