package com.hnwd.bdplayer

import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

/**
 * PDF / 图片预览里不依赖 Android View 的判定与状态机。
 *
 * PdfPreviewSection 与 ImagePreviewSection 都靠 AlertDialog / Activity 工作，JVM 单测实例化不了；
 * 这里抽出的是两条链路上最容易出边界错的部分：账号探测（viewer uk）、打开代次、
 * 下载失败后的降级、切图列表与下标装配。宿主状态用惰性 lambda 传入以保留短路。
 */

// ===================== 账号探测（两条预览链路共用） =====================

private val NUMERIC_UK = Regex("[0-9]{1,24}")

internal fun isNumericUk(value: String?): Boolean = value != null && value.matches(NUMERIC_UK)

/** evaluateJavascript 回传的是 JSON 字面量（含引号），取不出来或不是字符串都回落到 viewer 接口。 */
internal fun parseViewerUk(raw: String?): String? =
    raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }

internal fun viewerUkFromJson(json: JSONObject): String =
    json.optJSONObject("result")?.optString("uk").orEmpty()

internal const val VIEWER_UK_ERROR = "无法识别当前网盘账号，请重新登录后重试。"

internal fun requireViewerUk(uk: String): String {
    check(isNumericUk(uk)) { VIEWER_UK_ERROR }
    return uk
}

/**
 * 打开代次 + 页面可见 + 账号归属，三项必须同时成立。
 * [accountMatches] 惰性传入：夸克走 AccountScope + sessionReady，百度走已校验账号，
 * 两条分支对宿主的读取不同，且代次不匹配时一次都不该读。
 */
internal fun previewStillCurrent(
    id: Int,
    openId: Int,
    destroyed: () -> Boolean,
    visible: () -> Boolean,
    accountMatches: () -> Boolean,
): Boolean = id == openId && !destroyed() && visible() && accountMatches()

// ===================== 文档体积上限（按网盘区分） =====================

/**
 * 夸克非会员的 `file/download` 实测在约 50MB 以上直接返回 `23018 download file size limit`，
 * 且 `pdf/preview` 给的是**原件直链**（不像百度会转码降采样），所以在发请求前按原始大小拦掉，
 * 给一句能解释原因的提示。size 解析不出来时放行，宁可漏拦也不误拦。
 */
internal const val MAX_QUARK_PDF_BYTES = 50L * 1024 * 1024
internal const val QUARK_PDF_TOO_LARGE_MESSAGE =
    "夸克网盘非会员单个文件下载上限约 50MB，请开通会员或到夸克网页版查看。"

internal fun quarkPdfTooLarge(sizeRaw: String?): Boolean =
    (sizeRaw?.toLongOrNull() ?: 0L) > MAX_QUARK_PDF_BYTES

/**
 * 应用内 PDF 预览要把整份文件下到本地，因此百度只按「实际下载体积」限量：
 * 判据是下载响应的 Content-Length（docview 的 real_size），不是网盘列表里的原始 size
 * —— 240MB 的原件常转成 29.6MB 副本，用原始 size 预拦会误伤一大片。
 *
 * 历史上全局的 50MB 预拦 + 下载器里的 80MB 上限，是在掩盖真正的故障：
 * dlink 签名绑定生成它时的 WebView UA，用 `pan.baidu.com` 下载大文件会 403 sign error。
 * UA 修正后两条限制合并成本常量，覆盖 docview / 群预览 / 下载取址回退全部路径；
 * 夸克不走 docview，仍按上面的独立上限在入口拦。
 * 声明长度缺失（declared < 0，分块传输）时放行，由下载过程中的累计字节数兜底。
 */
internal const val MAX_PDF_BYTES = 200L * 1024 * 1024
internal const val PDF_TOO_LARGE_MESSAGE = "文档超过 200MB，暂不支持在 App 内在线阅读。"

internal fun pdfTooLarge(bytes: Long): Boolean = bytes > MAX_PDF_BYTES

// ===================== PDF 下载进度 =====================

/** bytesTotal <= 0 表示响应没有 Content-Length（分块传输），此时只能显示已加载量。 */
internal data class PdfProgress(val bytesDone: Long, val bytesTotal: Long)

/** 返回 0..100；总长度未知时返回 -1，调用方据此切回不确定进度条。 */
internal fun pdfProgressPercent(done: Long, total: Long): Int =
    if (total <= 0L || done < 0L) -1
    else (done.coerceAtMost(total) * 100L / total).toInt().coerceIn(0, 100)

private fun pdfSizeText(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.coerceAtLeast(0L) / 1024.0 / 1024.0)

internal fun pdfProgressText(done: Long, total: Long): String {
    if (done <= 0L) return "正在加载文档…"
    val percent = pdfProgressPercent(done, total)
    val loaded = pdfSizeText(done)
    return if (percent < 0) "已加载 $loaded" else "已加载 $loaded / ${pdfSizeText(total)} · $percent%"
}

// ===================== PDF 下载失败降级 =====================

internal enum class PdfRetry { RETRY_LINK, RETRY_FALLBACK, SHOW_ERROR }

internal data class PdfFailurePlan(val clearCachedLink: Boolean, val action: PdfRetry)

/**
 * 链接失效时按「缓存地址 -> docview -> 下载取址」退一级，最多退两次。
 * [clearCachedLink] 与降级动作分开：即使宿主未就绪，失效的地址也必须清掉，
 * 否则下次打开还会命中同一条坏链接。夸克走的不是 URL 取址，不参与降级。
 */
internal fun pdfFailurePlan(
    quark: Boolean,
    invalidLink: Boolean,
    hostReady: () -> Boolean,
    fromCache: Boolean,
    fromFallback: Boolean,
): PdfFailurePlan {
    if (quark || !invalidLink) return PdfFailurePlan(false, PdfRetry.SHOW_ERROR)
    if (!hostReady()) return PdfFailurePlan(true, PdfRetry.SHOW_ERROR)
    val action = when {
        fromCache -> PdfRetry.RETRY_LINK
        !fromFallback -> PdfRetry.RETRY_FALLBACK
        else -> PdfRetry.SHOW_ERROR
    }
    return PdfFailurePlan(true, action)
}

internal fun pdfFailureMessage(error: Throwable): String = error.message ?: "文档打开失败，请重试。"

// ===================== 图片画廊装配 =====================

internal data class ImageGallery(val items: List<PanEntry>, val position: Int)

/**
 * 传给查看器的列表必须与 gallery 完全一致（切图回传的是下标）。
 * 只保留同网盘、同类型的图片；目标不在列表里时插到首位。
 */
internal fun imageGallery(entry: PanEntry, entries: List<PanEntry>): ImageGallery {
    val siblings = entries.filter {
        it.provider == entry.provider && PanApi.kindOf(it) == MediaKind.IMAGE
    }.toMutableList()
    val key = entryKey(entry)
    val position = siblings.indexOfFirst { entryKey(it) == key }
    if (position < 0) siblings.add(0, entry)
    return ImageGallery(siblings, position.coerceAtLeast(0))
}

/** 百度个人网盘里没有列表自带缩略图时，只能先按文件取原图再看。 */
internal fun imageNeedsOriginal(quark: Boolean, entry: PanEntry): Boolean =
    !quark && entry.isDisk && entry.previewUrl.isEmpty()

/**
 * 组装查看器条目。[cacheKey] / [shareInfo] 由宿主注入，保持这里不碰 MessageDigest 与
 * 网盘 URL 拼接；shareInfo 只在群组图片时调用，短路顺序与原实现一致。
 */
internal fun imagePreviewItems(
    items: List<PanEntry>,
    quark: Boolean,
    cacheKey: (PanEntry) -> String,
    shareInfo: (PanEntry) -> String,
): List<ImagePreviewItem> = items.map { image ->
    ImagePreviewItem(
        image.fileId, image.name, cacheKey(image), image.previewUrl,
        shareInfoUrl = if (!quark && !image.isDisk) shareInfo(image) else "",
        diskDirectory = if (!quark && image.isDisk) {
            image.path.substringBeforeLast('/').ifEmpty { "/" }
        } else null,
    )
}

internal fun effectiveImageGallery(gallery: List<PanEntry>, entry: PanEntry): List<PanEntry> =
    gallery.ifEmpty { listOf(entry) }

internal fun imageViewerIndex(images: List<PanEntry>, entry: PanEntry): Int {
    val key = entryKey(entry)
    return images.indexOfFirst { entryKey(it) == key }.coerceAtLeast(0)
}

internal data class ImageFailurePlan(val clearCachedLink: Boolean, val retry: Boolean)

/** 原图下载失败且链接失效：清缓存地址；仅「用的是缓存地址」时才重取一次，避免无限重试。 */
internal fun imageFailurePlan(
    invalidLink: Boolean,
    hostReady: () -> Boolean,
    fromCache: Boolean,
): ImageFailurePlan = ImageFailurePlan(
    clearCachedLink = invalidLink,
    // 原实现是 host.isReady() && fromCache：isReady 先求值，fromCache 为 false 时也会读宿主。
    retry = invalidLink && hostReady() && fromCache,
)

internal fun imageFailureMessage(error: Throwable): String = error.message ?: "图片打开失败，请重试。"
