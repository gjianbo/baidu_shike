package com.hnwd.bdplayer

import java.net.URI
import java.util.Locale

/**
 * 视频收集器的纯逻辑层：分享文本 → 平台/视频 ID → 目标路径校验。
 * 不碰 Android / OkHttp / yt-dlp，可在 JVM 单测里直接跑；下载交给引擎，这里不再手搓直链解析。
 */
internal object VideoCollectorLogic {
    enum class Platform { BILIBILI, X, DOUYIN }

    data class Parsed(
        val platform: Platform,
        val id: String,
        val canonicalUrl: String,
        val apiUrl: String,
        val suggestedName: String,
    )

    /**
     * URL 里的 ID 约定：B 站 BV 号大小写敏感且固定 12 位；aid 为纯数字；
     * X 的状态 ID 为纯数字；抖音长链 ID 为纯数字，短链是一段 code。
     * 用白名单字符而不是 `.*`，否则会把后面的路径/查询串一起吞掉。
     */
    private val BILIBILI_BV = Regex("""(BV[0-9A-Za-z]{10})""")
    private val BILIBILI_AV = Regex("""(?:^|/)av(\d{1,15})(?:/|\?|#|$)""")
    private val X_STATUS = Regex("""(?:^|/)status/(\d{5,25})""")
    private val DOUYIN_VIDEO = Regex("""/video/(\d{8,25})""")
    private val DOUYIN_SHORT = Regex("""v\.douyin\.com/([A-Za-z0-9_-]{4,40})""")

    private fun parsedOrNull(platform: Platform, id: String, canonical: String, name: String): Parsed =
        Parsed(platform, id, canonical, canonical, name)

    /**
     * 从分享文本里抽取第一个可识别的视频链接。分享语里常同时含短链、表情与中文说明，
     * 所以先找出所有 http(s) URL，再逐个按平台规则解析，命中即返回。
     */
    fun parse(text: String): Parsed? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val urls = Regex("""https?://[^\s<>"'）】]+""").findAll(trimmed).map { it.value }
        for (raw in urls) {
            val clean = raw.trimEnd('。', '，', ',', '.', ';', '；', '!', '！', ')', ']')
            val lower = clean.lowercase(Locale.US)
            when {
                "bilibili.com/video/" in lower || "b23.tv/" in lower -> {
                    BILIBILI_BV.find(clean)?.let { bv ->
                        val id = bv.value
                        return parsedOrNull(Platform.BILIBILI, id, "https://www.bilibili.com/video/$id/", "B站-$id")
                    }
                    BILIBILI_AV.find(clean)?.let { av ->
                        val aid = av.groupValues[1]
                        return parsedOrNull(Platform.BILIBILI, "av$aid", "https://www.bilibili.com/video/av$aid/", "B站-av$aid")
                    }
                }
                "x.com/" in lower || "twitter.com/" in lower -> {
                    X_STATUS.find(clean)?.let { status ->
                        val id = status.groupValues[1]
                        return parsedOrNull(Platform.X, id, "https://x.com/i/status/$id", "X-$id")
                    }
                }
                "douyin.com" in lower -> {
                    val uri = runCatching { URI(clean) }.getOrNull() ?: continue
                    if (uri.rawUserInfo != null || uri.port != -1) continue
                    val host = uri.host?.lowercase(Locale.US)
                    val path = uri.rawPath.orEmpty()
                    if (host == "douyin.com" || host == "www.douyin.com") {
                        val id = DOUYIN_VIDEO.matchEntire(path.trimEnd('/'))?.groupValues?.get(1)
                            ?: if (path.trimEnd('/') == "/jingxuan") {
                                uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "modal_id" }
                                    .singleOrNull()?.substringAfter('=', "")?.takeIf { it.matches(Regex("[0-9]{8,25}")) }
                            } else null
                        if (id != null) return parsedOrNull(Platform.DOUYIN, id, "https://www.douyin.com/video/$id", "抖音-$id")
                    } else if (host == "v.douyin.com") {
                        val id = DOUYIN_SHORT.matchEntire("v.douyin.com${path.trimEnd('/')}")?.groupValues?.get(1)
                        if (id != null) return parsedOrNull(Platform.DOUYIN, id, "https://v.douyin.com/$id/", "抖音-$id")
                    }
                }
            }
        }
        return null
    }

    /**
     * 子目录是一层目录名（选择器逐层下钻，不允许一次填多级路径）。
     * 空表示直接存默认根；拒绝绝对路径、`..` 跳转与网盘不支持的字符。
     */
    fun collectorSubDirValid(subDir: String): Boolean {
        if (subDir.isEmpty()) return true
        if (subDir.startsWith('/') || subDir.startsWith('\\')) return false
        if (subDir.split('/', '\\').any { it == ".." }) return false
        return diskNameError(subDir) == null
    }

    /** 从网盘返回的完整路径推导相对路径，拒绝无关目录与越界路径。 */
    fun collectorRelativeDir(rootDir: String, selectedDir: String): String? {
        val root = rootDir.trimEnd('/').ifBlank { "/" }
        val selected = selectedDir.trimEnd('/').ifBlank { "/" }
        if (root == selected) return ""
        val prefix = root.trimEnd('/') + "/"
        if (!selected.startsWith(prefix)) return null
        return selected.removePrefix(prefix).takeIf(::validRelativeDir)
    }

    private fun validRelativeDir(path: String): Boolean =
        path.split('/').all { it.isNotEmpty() && collectorSubDirValid(it) }

    fun collectorBaiduDir(rootDir: String, subDir: String): String {
        val root = rootDir.trimEnd('/').ifBlank { "/" }
        if (subDir.isEmpty() || !validRelativeDir(subDir)) return root
        return "${root.trimEnd('/')}/$subDir"
    }

    fun collectorOutputPrefix(platform: String): String = when (platform) {
        "BILIBILI" -> "B站-"
        "X" -> "X-"
        "DOUYIN" -> "抖音-"
        else -> "视频-"
    }

    fun collectorDownloadedName(custom: String?, fileName: String, platform: String, url: String): String {
        val title = fileName.substringBeforeLast('.').removePrefix(collectorOutputPrefix(platform)).trim()
        val fallback = parse(url)?.suggestedName ?: "分享视频"
        return collectorFileName(custom, title.takeUnless { it.isBlank() || it.equals("NA", ignoreCase = true) } ?: fallback)
    }

    /** 自定义名可带扩展名；引擎标题已经去掉扩展名，保留标题中的句点。 */
    fun collectorFileName(custom: String?, title: String?): String {
        val customName = custom?.trim()?.takeIf(String::isNotBlank)
            ?.let { it.substringBeforeLast('.').ifBlank { it } }
        val raw = customName ?: title?.takeIf(String::isNotBlank) ?: "视频"
        return DownloadLogic.safeName(raw).ifBlank { "视频" }.take(60)
    }
}
