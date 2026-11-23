package com.hnwd.bdplayer

import android.content.Context
import androidx.core.content.edit

/**
 * 在线转码流的清晰度档位（`stream_type`）。
 *
 * 实测依据见 `docs/百度网盘视频清晰度档位-实测结论.md`：百度的 `/api/streaming`
 * （我的网盘）与 `/mbox/msg/streaming`（群组）都接受多档 `stream_type`，
 * 且群组侧 1080 档给的是**真 1920×1080 HEVC**，不是 480 放大。
 *
 * 各档在两条链路上的真实产出并不对称（网盘侧 1080 档被服务端降级到 720），
 * 所以档位选项只表达「用户想要的清晰度」，实际落到的档由 [qualityCandidates] 决定。
 */
internal enum class VideoQuality(val label: String, val hint: String) {
    AUTO("自动", "优先最高码率，取不到自动降档"),
    FHD("1080p", "群组为原画 1920×1080（HEVC）"),
    HD("720p", "1280×720（HEVC，体积更小）"),
    SD("480p", "854×480（H.264，最省流量）"),
}

/** 实测可用的 `stream_type` 常量。写错一个字符服务端就回 31023。 */
internal object StreamType {
    const val H264_480 = "M3U8_AUTO_480"
    const val HEVC_720 = "M3U8_AUTO_720"
    const val HEVC_1080 = "M3U8_AUTO_1080"
}

/**
 * 把「想要的清晰度」翻译成按优先级排列的候选档位。
 *
 * 关键取舍：每个链尾都保留 [StreamType.H264_480]。实测它是唯一在所有文件上都成立的
 * 档位（冷文件最先出的就是这一档），作为兜底保证「至少能播」；前面的高码率档
 * 取不到时由 [PlaylistFetcher.fetchFirst] 自动往下试，用户不会看到失败。
 *
 * 网盘侧不放 720：实测 `/api/streaming` 的 720 与 1080 给的是同一份转码产物
 * （分片目录相同、都是 1280×720），多要一档只是多一次请求。
 *
 * 纯函数，便于 JVM 单测（不碰 Context / SharedPreferences）。
 */
internal fun qualityCandidates(origin: EntryOrigin, quality: VideoQuality): List<String> = when (quality) {
    VideoQuality.SD -> listOf(StreamType.H264_480)
    VideoQuality.HD -> listOf(StreamType.HEVC_720, StreamType.H264_480)
    VideoQuality.FHD -> listOf(StreamType.HEVC_1080, StreamType.H264_480)
    VideoQuality.AUTO -> if (origin == EntryOrigin.GROUP) {
        // 群组三档给三份不同数据，按体积从大到小试。
        listOf(StreamType.HEVC_1080, StreamType.HEVC_720, StreamType.H264_480)
    } else {
        listOf(StreamType.HEVC_1080, StreamType.H264_480)
    }
}

/**
 * 「这一档不存在 / 不适用」是否值得换下一档重试。
 *
 * 31023 = 档名不存在；31304 = 该文件类型不支持此档（例如给音频要视频档）。
 * 这两种是**档位问题**，换档有意义；133 / 31341 / 网络错误是**状态问题**，
 * 换档只会把同一个错误重复几遍，必须原样上抛让上层重试或降级。
 */
internal fun isUnsupportedTier(code: Int, body: String): Boolean {
    val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return false
    val value = json.optInt("errno", 0).takeIf { it != 0 } ?: json.optInt("error_code", 0)
    return value == 31023 || value == 31304
}

/** 档位选择持久化：与外观/倍速同在 `app_settings`，不参与账号隔离。 */
internal object VideoQualityStore {
    private const val PREFS = "app_settings"
    private const val KEY = "video_quality"

    /** 缺省自动：原先硬编码 480，等于把可选的高清能力整个扔掉。 */
    fun get(context: Context): VideoQuality {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return VideoQuality.entries.firstOrNull { it.name == raw } ?: VideoQuality.AUTO
    }

    fun set(context: Context, quality: VideoQuality) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, quality.name) }
    }

    fun label(context: Context): String = get(context).label
}
