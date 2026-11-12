package com.hnwd.bdplayer

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

// ===================== 文档打开大小限制 =====================

/**
 * 应用内 PDF 预览必须先把整份文件下载下来；实测夸克 / 百度的直链在 100MB 以上
 * 会下载失败，因此在打开入口按 50MB 拦截，避免起一个注定失败的下载弹窗。
 * size 来自网盘 JSON，是字节数的十进制字符串；解析不出来（空串 / 非数字 / 老数据）
 * 一律放行，宁可漏拦也不误拦能正常打开的文件。
 */
internal const val MAX_PDF_BYTES = 50L * 1024 * 1024
internal const val PDF_TOO_LARGE_MESSAGE = "文件超过 50MB，暂不支持在 App 内打开。"

internal fun pdfTooLarge(sizeRaw: String?): Boolean =
    (sizeRaw?.toLongOrNull() ?: 0L) > MAX_PDF_BYTES

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
