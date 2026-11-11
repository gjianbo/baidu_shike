package com.hnwd.bdplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import java.io.File
import org.json.JSONObject

/**
 * 图片预览需要的宿主能力。取址复用 [MediaLinkSection]，
 * 导航只在两条路上：内置查看器（[launchViewer]）与原图下载完成后的回跳。
 */
internal interface ImagePreviewHost {
    val hostActivity: Activity

    fun setStatus(message: String)
    fun isVisible(): Boolean
    fun isDestroyed(): Boolean
    fun isReady(): Boolean
    fun account(): String
    fun accountVerified(): Boolean
    fun sessionReady(provider: SourceProvider): Boolean
    fun playbackAccount(entry: PanEntry): String
    fun bumpListRequest()
    fun verifyAccount()
    fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit)
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit)
    /** 交给宿主注册的 ActivityResultLauncher，切图结果要能回传到同一份列表。 */
    fun launchViewer(intent: Intent)

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
 * 图片预览：签名看图地址直连 -> 缺失则按文件取原图 -> 下载校验 -> 打开查看器。
 *
 * 三条不变式：
 * 1. 每次回调都过 [isCurrentImage]（打开代次 + 账号 + 页面可见），切图或切号后旧结果必须丢弃。
 * 2. 查看器切图靠回传下标，所以 [gallery] 必须与传给查看器的列表完全一致。
 * 3. 原图下载失败且是链接失效时，清地址缓存后重取一次，不无限重试。
 */
internal class ImagePreviewSection(
    private val host: ImagePreviewHost,
    private val mediaLinks: MediaLinkSection,
    private val addresses: MediaAddressCache,
) {
    private val activity: Activity get() = host.hostActivity
    private var openId = 0
    private var loading: AlertDialog? = null
    private var gallery: List<PanEntry> = emptyList()
    private val fetcher by lazy { ImageFileFetcher(host.hostActivity) }

    /** 查看器回传下标时取对应条目。 */
    fun galleryEntry(index: Int): PanEntry? = gallery.getOrNull(index)

    fun entries(): List<PanEntry> = gallery

    /** 账号失效 / 页面不可见：中止进行中的下载。 */
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

    fun open(entry: PanEntry, entries: List<PanEntry>) {
        val quark = entry.provider == SourceProvider.QUARK
        val sessionReady = if (quark) host.sessionReady(SourceProvider.QUARK) else host.accountVerified()
        if (!sessionReady) {
            host.setStatus("请确认网盘登录后重试。")
            return
        }
        val galleryState = imageGallery(entry, entries)
        gallery = galleryState.items
        if (imageNeedsOriginal(quark, entry)) {
            openOriginal(entry)
            return
        }
        close()
        val account = host.playbackAccount(entry)
        val items = imagePreviewItems(
            galleryState.items, quark,
            cacheKey = { image -> mediaCacheKey(account, image) },
            shareInfo = { image -> PanApi.imageShareInfo(image) },
        )
        host.launchViewer(ImageViewerActivity.galleryIntent(activity, items, galleryState.position, entry.provider))
        host.setStatus("")
    }

    fun openOriginal(entry: PanEntry) {
        if (!host.accountVerified()) {
            host.setStatus("请确认网盘登录后重试。")
            return
        }
        close()
        val id = openId
        host.bumpListRequest()
        mediaLinks.dismissCaptcha(RequestLane.LIST)
        val account = host.playbackAccount(entry)
        val key = mediaCacheKey(account, entry)
        val ext = ImageTypes.ext(entry.name)
        ImageCache.find(activity, key, ext)?.let {
            startImageViewer(entry, it)
            return
        }
        addresses.link(key)?.let {
            downloadImage(entry, account, id, key, it, fromCache = true)
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
            if (!isCurrentImage(id, account)) return@evaluateJavascript
            val viewer = parseViewerUk(result)
            if (viewer != null && isNumericUk(viewer)) {
                requestImageLink(entry, viewer, id)
            } else {
                host.fetchJson(PanApi.viewer(), lane = RequestLane.LIST) { json ->
                    if (!isCurrentImage(id, account)) return@fetchJson
                    val uk = requireViewerUk(viewerUkFromJson(json))
                    requestImageLink(entry, uk, id)
                }
            }
        }
    }

    /** 图片不走 docview/docpreview（PDF 专用），直接取原文件 dlink：群组 sharedownload，我的网盘 /api/download。 */
    private fun requestImageLink(entry: PanEntry, viewer: String, id: Int) {
        if (viewer != host.account()) {
            host.verifyAccount()
            return
        }
        val key = mediaCacheKey(viewer, entry)
        fun isCurrent() = isCurrentImage(id, viewer)
        fun fail() {
            if (isCurrent()) host.setStatus("图片打开失败，请重试。")
        }
        fun receive(link: Uri) {
            if (!isCurrent()) return
            addresses.saveLink(key, link)
            downloadImage(entry, viewer, id, key, link)
        }
        if (entry.isDisk) {
            host.withBdstoken(
                onReady = { token ->
                    if (isCurrent()) {
                        mediaLinks.requestDiskLink(
                            entry, token, RequestLane.LIST, { isCurrent() }, onFailure = { fail() },
                        ) { receive(it) }
                    }
                },
                onFailure = { fail() },
            )
        } else {
            mediaLinks.resolve(entry, viewer, lane = RequestLane.LIST, onFailure = { fail() }) { receive(it) }
        }
    }

    private fun downloadImage(
        entry: PanEntry,
        account: String,
        id: Int,
        key: String,
        link: Uri,
        fromCache: Boolean = false,
    ) {
        if (!isCurrentImage(id, account)) return
        loading?.dismiss()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setMessage("正在加载原图…")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                close()
                host.setStatus("")
            }
            .show()
        loading = dialog
        val ext = ImageTypes.ext(entry.name)
        fun complete(result: Result<File>) {
            if (loading === dialog) {
                dialog.dismiss()
                loading = null
            }
            if (!isCurrentImage(id, account)) {
                result.getOrNull()?.let(ImageCache::discard)
                return
            }
            result.mapCatching { temp ->
                try {
                    startImageViewer(entry, ImageCache.publish(activity, key, ext, temp))
                } finally {
                    ImageCache.discard(temp)
                }
            }.onFailure {
                val plan = imageFailurePlan(it is InvalidImageLink, { host.isReady() }, fromCache)
                if (plan.clearCachedLink) addresses.clearLink(key)
                if (plan.retry) {
                    requestImageLink(entry, account, id)
                    return@onFailure
                }
                host.setStatus(imageFailureMessage(it))
            }
        }
        fetcher.start(link, ::complete)
    }

    private fun startImageViewer(entry: PanEntry, file: File) {
        // 字段与传给查看器的列表必须完全一致：切图回传的是列表下标
        val images = effectiveImageGallery(gallery, entry)
        gallery = images
        val index = imageViewerIndex(images, entry)
        host.launchViewer(Intent(activity, ImageViewerActivity::class.java).apply {
            putExtra(ImageViewerActivity.EXTRA_FILE, file.absolutePath)
            putExtra(ImageViewerActivity.EXTRA_TITLE, entry.name)
            putExtra(ImageViewerActivity.EXTRA_MIME, ImageTypes.mime(ImageTypes.ext(entry.name)))
            putExtra(ImageViewerActivity.EXTRA_INDEX, index)
            putExtra(ImageViewerActivity.EXTRA_COUNT, images.size)
        })
        host.setStatus("")
    }

    private fun isCurrentImage(id: Int, account: String) = previewStillCurrent(
        id, openId,
        destroyed = { host.isDestroyed() },
        visible = { host.isVisible() },
        accountMatches = { account == host.account() && host.accountVerified() },
    )

}
