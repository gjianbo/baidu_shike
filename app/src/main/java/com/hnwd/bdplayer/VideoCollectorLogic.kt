package com.hnwd.bdplayer

import java.util.Locale

/**
 * 视频收集器的纯逻辑层：只做「分享文本 → 平台/视频 ID → 直链地址 → 文件名」，
 * 不碰 Android / OkHttp，可在 JVM 单测里直接跑。真正的网络请求在 VideoCollector 里完成。
 */
internal object VideoCollectorLogic {
    enum class Platform { BILIBILI, X }

    data class Parsed(
        val platform: Platform,
        val id: String,
        val canonicalUrl: String,
        val apiUrl: String,
        val suggestedName: String,
    )

    data class Resolution(
        val url: String,
        val filename: String,
        val mediaType: String,
        val needReferer: Boolean,
    )

    /**
     * URL 里的 ID 约定：B 站 BV 号大小写敏感且固定 12 位；aid 为纯数字；X 的状态 ID 为纯数字。
     * 用白名单字符而不是 `.*`，否则会把后面的路径/查询串一起吞掉。
     */
    private val BILIBILI_BV = Regex("""(BV[0-9A-Za-z]{10})""")
    private val BILIBILI_AV = Regex("""(?:^|/)av(\d{1,15})(?:/|\?|#|$)""")
    private val X_STATUS = Regex("""(?:^|/)status/(\d{5,25})""")

    private fun parsedOrNull(platform: Platform, id: String, canonical: String, api: String, name: String): Parsed? =
        if (id.isBlank()) null else Parsed(platform, id, canonical, api, name)

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
                        return parsedOrNull(
                            Platform.BILIBILI, id,
                            "https://www.bilibili.com/video/$id/",
                            "https://api.bilibili.com/x/web-interface/view?bvid=$id",
                            "B站-$id",
                        )
                    }
                    BILIBILI_AV.find(clean)?.let { av ->
                        val aid = av.groupValues[1]
                        return parsedOrNull(
                            Platform.BILIBILI, "av$aid",
                            "https://www.bilibili.com/video/av$aid/",
                            "https://api.bilibili.com/x/web-interface/view?aid=$aid",
                            "B站-av$aid",
                        )
                    }
                }
                lower.contains("x.com/") || lower.contains("twitter.com/") || lower.contains("mobile.twitter.com/") -> {
                    X_STATUS.find(clean)?.let { status ->
                        val id = status.groupValues[1]
                        return parsedOrNull(
                            Platform.X, id,
                            "https://x.com/i/status/$id",
                            "https://api.vxtwitter.com/Twitter/status/$id",
                            "X-$id",
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * 把 B 站 view API 的 JSON（播放页同源接口）解析成可下载媒体地址。
     * 只取 DASH 里同时带视频/音频且 url 已给出的项；没有音频时退回纯视频流，
     * 优先返回最高清晰度的可直链项。无 cookies 的公开视频才有 url，会员/登录项会被跳过。
     */
    fun resolveBilibili(json: String): Resolution? {
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val title = data.optString("title", "").takeIf { it.isNotBlank() } ?: "B站视频"
        val dash = data.optJSONObject("dash")
        val pick: (String) -> Pair<String, String>? = { key ->
            val arr = dash?.optJSONArray(key)
            var best: Pair<String, String>? = null
            if (arr != null) for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url", "").ifBlank { item.optString("backupUrl", "").let { b -> b.substringBefore(',') } }
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    val bandwidth = item.optLong("bandwidth", 0)
                    val current = best
                    if (current == null || bandwidth > current.second.toLongOrNull().let { it ?: 0 }) {
                        best = url to bandwidth.toString()
                    }
                }
            }
            best?.let { it.first to it.second }
        }
        val video = pick("video")
        val audio = pick("audio")
        if (video == null && audio == null) return null
        val url = video?.first ?: audio!!.first
        val extension = if (audio != null && video != null) "mp4" else if (video != null) "m4v" else "m4a"
        val type = when (extension) {
            "mp4" -> "video/mp4"
            "m4v" -> "video/mp4"
            else -> "audio/mp4"
        }
        return Resolution(
            url = url,
            filename = safeFilename("$title.$extension"),
            mediaType = type,
            needReferer = true,
        )
    }

    /**
     * vxtwitter 的公开推文 JSON 里，media 数组给出视频直链。优先选 mp4，
     * 没有则取第一个带 URL 的媒体项；宽度只作为排序线索，不影响可用性。
     */
    fun resolveX(json: String): Resolution? {
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val medias = root.optJSONArray("media") ?: return null
        var best: org.json.JSONObject? = null
        var bestWidth = -1
        for (i in 0 until medias.length()) {
            val item = medias.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            if (type != "video" && type != "animated_gif") continue
            val variants = item.optJSONArray("variants") ?: continue
            for (j in 0 until variants.length()) {
                val variant = variants.optJSONObject(j) ?: continue
                val url = variant.optString("url", "")
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                val width = variant.optInt("width", item.optInt("width", 0))
                if (width >= bestWidth) {
                    best = variant
                    bestWidth = width
                }
            }
        }
        val url = best?.optString("url", "").takeIf { !it.isNullOrBlank() } ?: return null
        val name = root.optString("user_name", "X").ifBlank { "X" }
        return Resolution(
            url = url,
            filename = safeFilename("X-$name-${System.currentTimeMillis()}.mp4"),
            mediaType = "video/mp4",
            needReferer = false,
        )
    }

    private fun safeFilename(name: String): String = DownloadLogic.safeName(name)
}
