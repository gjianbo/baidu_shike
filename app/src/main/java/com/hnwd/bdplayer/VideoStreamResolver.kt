package com.hnwd.bdplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal data class VideoStream(val uri: Uri, val playlist: ByteArray)

/**
 * 取址/转码尚未就绪的**可恢复**状态：调用方应重试，而不是直接报错。
 *
 * 两类触发：
 * ① `errno` / `error_code` = 31341（HLS 转码任务排队中）；
 * ② HTTP 200 但返回体不是可播放的 m3u8 —— 冷文件首次取址时百度可能先返回占位内容
 *    或「尚未生成完」的清单（无 `#EXT-X-ENDLIST`），再请求一次才给完整清单；
 *    以及非 200 但未给出明确错误码的空错误体。
 *
 * 真机实测：这类失败「再试一次就正常」，所以必须自动重试，不能弹「无法原生播放」。
 */
internal class TranscodingPending : IllegalStateException("网盘仍在转码，请稍后重试。")

/**
 * 该转码档位对这个文件不成立（31023 档名不存在 / 31304 文件类型不支持该档）。
 *
 * 这是**档位问题**，不是状态问题：重试没用，换下一档才有意义。
 * [PlaylistFetcher.fetchFirst] 据此自动降档；其它错误（133/31341/网络）必须原样上抛，
 * 否则会把一个可自愈的状态错误重复几遍，白白拖长等待。
 */
internal class StreamTierUnsupported(message: String) : IllegalStateException(message)

internal class PlaylistRequestTimeout : IllegalStateException("获取播放清单超时。")

/**
 * 在线转码播放被服务端永久拒绝（无 adToken 的 errno=133，或广告校验后仍 133）：
 * 重试不会好转，调用方应改播原始文件，而不是死等或反复报错。
 */
internal class StreamingRestricted(message: String) : IllegalStateException(message)

/**
 * 非会员在线转码的「广告门」：`errno` / `error_code` = 133 **且响应体带 `adToken`**。
 *
 * 这不是永久限制。百度网页版拿到 `adToken`（`adTime` 为广告计时秒数）后，把它原样
 * 带进下一次 `/api/streaming` 请求即可拿到可播放的 m3u8 —— 真机抓包证见
 * `docs/百度网盘非会员视频播放-133广告token分析.md`。本解析器据此自带重试；
 * 只有 token 缺失、或按 adTime 计时后仍被拦，才降级为 [StreamingRestricted]。
 */
internal class AdGate(val adToken: String, val adTimeSeconds: Int) :
    IllegalStateException("非会员需观看广告后播放")

/** 纯逻辑：m3u8 是否是可播放的点播清单。顶层函数便于 JVM 单测直接调用（不用碰 Handler）。 */
internal fun isPlaybackPlaylist(body: String): Boolean =
    body.startsWith("#EXTM3U") && body.lineSequence().any {
        it.startsWith("#EXT-X-STREAM-INF:") || it.trimEnd() == "#EXT-X-ENDLIST"
    }

/** 文档 §12：错误码速查，把取址失败翻成人话 / 可恢复状态。顶层纯函数，便于单测。 */
internal fun playlistError(code: Int, body: String): IllegalStateException {
    val json = runCatching { JSONObject(body) }.getOrNull()
    val errno = json?.optInt("errno")
    val errorCode = json?.optInt("error_code")
    val reportedCode = errno?.takeUnless { it == 0 } ?: errorCode?.takeUnless { it == 0 }
    val show = json?.optString("show_msg").orEmpty().trim().take(60)
    val detail = if (show.isEmpty()) "" else " · 网盘提示：$show"
    return when {
        errno == -6 -> IllegalStateException("请重新登录百度网盘后再试。")
        errno == -20 || errorCode == -20 -> {
            // 音频/视频、DISK(/api/streaming)/GROUP(/mbox/msg/streaming) 共用本解析器，
            // 文案不能写死媒体类型或接口路径，否则会在别的链路串台。
            IllegalStateException("百度网盘拒绝了播放取址请求（错误码 -20，通常需验证码）$detail")
        }
        errno == 133 || errorCode == 133 -> {
            // 非会员广告门：带 adToken 时「可恢复」，交给 AdGate 用 token 重试；
            // 不带 adToken 才是真·受限（降级到原始文件）。
            val adToken = json?.optString("adToken").orEmpty()
            val adTime = json?.optInt("adTime", 0) ?: 0
            if (adToken.isNotEmpty()) AdGate(adToken, adTime)
            else StreamingRestricted(
                "百度网盘限制了该文件的在线转码播放（错误码 133，非会员且未取到广告 token）$detail",
            )
        }
        // 档位类错误：两个字段名（errno / error_code）百度会混用，统一比较 reportedCode。
        reportedCode == 31304 -> StreamTierUnsupported("该文件类型不支持此转码档位，请换档位或改用原始文件。")
        reportedCode == 31341 -> TranscodingPending()
        reportedCode == 31023 -> StreamTierUnsupported("网盘不支持此转码档位，已自动改用其它档位。")
        code == 401 || code == 403 -> IllegalStateException("播放地址已过期或无权访问，请重新打开（地址约 8 小时有效）。")
        // 非 200 但没给明确错误码：同样按「尚未就绪」处理，交给调用方重试。
        code != 200 -> if (reportedCode == null) TranscodingPending()
                       else IllegalStateException("播放清单请求失败（HTTP $code）。")
        reportedCode != null -> IllegalStateException("百度网盘未返回可播放的点播清单（错误码 $reportedCode）$detail")
        // 200 但清单未就绪：占位内容 / 未生成完的 m3u8 / 非标准 JSON。
        else -> TranscodingPending()
    }
}

/**
 * 把 `adToken` 写进 `/api/streaming` 的查询串：去掉已有的 `adToken=`，再追加编码后的 token。
 *
 * 纯字符串实现，便于 JVM 单测（不依赖 android.net.Uri）。token 是含 `+ / =` 的 base64，
 * 必须 URL 编码后再拼进 query，否则 `+` 会被服务端当成空格导致广告校验失败。
 */
internal fun withAdToken(url: String, token: String): String {
    val qIndex = url.indexOf('?')
    val base = if (qIndex >= 0) url.substring(0, qIndex) else url
    val query = if (qIndex >= 0) url.substring(qIndex + 1) else ""
    val kept = query.split('&').filter { it.isNotEmpty() && !it.startsWith("adToken=") }
    val tokenParam = "adToken=" + URLEncoder.encode(token, "UTF-8")
    val rebuilt = (kept + tokenParam).joinToString("&")
    return "$base?$rebuilt"
}

/**
 * 由「播放」取址 URL 推导网页版那条 `&nom3u8=1` 的**预检** URL：
 * 档位改 M3U8_FLV_264_480、加 nom3u8=1、去掉 isplayer/check_blue/adToken。
 *
 * 用途：个别情况下完整版取址只回 133 但不带 adToken，用预检请求补拿 adToken。
 * 纯字符串实现，便于单测。
 */
internal fun deriveProbeUrl(url: String): String {
    val qIndex = url.indexOf('?')
    val base = if (qIndex >= 0) url.substring(0, qIndex) else url
    val query = if (qIndex >= 0) url.substring(qIndex + 1) else ""
    val drop = setOf("type", "nom3u8", "isplayer", "check_blue", "adToken")
    val kept = query.split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in drop }
    val probeParams = listOf("type=M3U8_FLV_264_480", "nom3u8=1")
    val rebuilt = (kept + probeParams).joinToString("&")
    return "$base?$rebuilt"
}

internal class VideoStreamResolver {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var requestId = 0

    fun start(uri: Uri, userAgent: String, onResult: (Result<VideoStream>) -> Unit) =
        start(listOf(uri), userAgent, onResult)

    /**
     * 按优先级逐档取址（见 [PlaylistFetcher.fetchAny]）：清单里的第一档不被该文件支持时
     * 自动降到下一档，只有全部档位都判为「不适用」才失败。
     *
     * 超时是按「整条候选链」算的 30s，不是每档 30s —— 冷文件降档时最坏情况要请求
     * 2~3 次，若每档都独立计时，用户会等 90s 才看到弹窗。
     */
    fun start(uris: List<Uri>, userAgent: String, onResult: (Result<VideoStream>) -> Unit) {
        cancel()
        val id = requestId
        val cookie = playlistCookie(uris.first())
        worker = executor.submit {
            val result = runCatching { PlaylistFetcher.fetchAny(uris, userAgent, cookie) }
            handler.post {
                if (id != requestId) return@post
                cancel()
                onResult(result)
            }
        }
        // 非会员广告门可能要多等一个 adTime（最长 MAX_AD_WAIT_SECONDS），总超时放宽到 30s。
        handler.postDelayed({
            if (id != requestId) return@postDelayed
            cancel()
            onResult(Result.failure(PlaylistRequestTimeout()))
        }, 30_000)
    }

    fun cancel() {
        requestId++
        worker?.cancel(true)
        worker = null
        handler.removeCallbacksAndMessages(null)
    }

    fun close() {
        cancel()
        executor.shutdownNow()
    }
}

/** 取 m3u8 主清单用的 Cookie：优先当前 URL 域，退化到网盘域（播放页与接口同源）。 */
internal fun playlistCookie(uri: Uri): String? =
    CookieManager.getInstance().getCookie(uri.toString())?.takeIf { it.isNotBlank() }
        ?: CookieManager.getInstance().getCookie("https://${PanApi.HOST}")

/**
 * 同步版取址核心：播放（[VideoStreamResolver]）与下载（[GroupVideoDownload]）共用同一套
 * 重试语义 —— 31341 排队、133 广告门、占位清单都要「再试一次」，不能各自实现一份。
 *
 * 调用方负责放到后台线程；本函数最多阻塞 MAX_ROUNDS 次请求 + 一次广告等待。
 */
internal object PlaylistFetcher {
    /**
     * 按 [candidates]（`stream_type` 优先级列表）逐档取址，返回第一个成功的。
     *
     * 存在的理由：高清档并非对所有文件都成立 —— 冷文件先出 480、老文件没有 1080 转码、
     * 音频档更是直接 31304。若不像这样逐档降级，选「1080p」的用户会遇到一批文件
     * 报「不支持此档位」，而其实 480 是好的。
     *
     * [buildUri] 把档位翻成该链路的取址 URL（群组与网盘两条路径的拼法不同）。
     * 降级只在 [StreamTierUnsupported]（31023/31304）时发生；其它错误原样上抛，
     * 免得把一个 3 秒就能自愈的 31341 排队错误变成「所有档位都失败」。
     */
    /** 按 [candidates] 逐档取址，返回第一个成功的 `stream_type` 对应的清单。 */
    fun fetchFirst(
        candidates: List<String>,
        buildUri: (String) -> Uri,
        userAgent: String,
        cookie: String?,
    ): VideoStream = fetchAny(candidates.map(buildUri), userAgent, cookie)

    /**
     * 按给定顺序逐档取址，返回第一个成功的清单。
     *
     * 降级只在 [StreamTierUnsupported]（31023/31304）时发生；其它错误原样上抛，
     * 免得把一个 3 秒就能自愈的 31341 排队错误变成「所有档位都失败」。
     */
    fun fetchAny(uris: List<Uri>, userAgent: String, cookie: String?): VideoStream {
        var last: StreamTierUnsupported? = null
        for ((index, uri) in uris.withIndex()) {
            try {
                val stream = fetch(uri, userAgent, cookie)
                android.util.Log.i(LOG_TAG, "tier #$index ok (${tierOf(uri)})")
                return stream
            } catch (error: StreamTierUnsupported) {
                last = error
                android.util.Log.i(LOG_TAG, "tier #$index unsupported, trying next")
            }
        }
        throw last ?: TranscodingPending()
    }

    /** 取址 URL 里的档位（`stream_type` 或 `type`），只用于日志定位，取不到给空串。 */
    private fun tierOf(uri: Uri): String =
        uri.getQueryParameter("stream_type") ?: uri.getQueryParameter("type").orEmpty()

    fun fetch(uri0: Uri, userAgent: String, cookie: String?): VideoStream {
        var uri = uri0
        var adAttempts = 0
        var pendingAttempts = 0
        var probed = false
        var lastError: IllegalStateException? = null
        repeat(MAX_ROUNDS) { round ->
            val (code, body) = request(uri, userAgent, cookie)
            if (code == 200 && isPlaybackPlaylist(body)) {
                android.util.Log.i(LOG_TAG, "round=$round playlist received")
                return VideoStream(uri, body.toByteArray(Charsets.UTF_8))
            }
            var error = playlistError(code, body)
            android.util.Log.i(
                LOG_TAG,
                "round=$round code=$code err=${error.javaClass.simpleName} " +
                    "adTime=${(error as? AdGate)?.adTimeSeconds ?: -1}",
            )
            // 完整版取址只回 133 但不带 adToken：补一次网页版那条 nom3u8 预检再取 token。
            // 预检参数是 /api/streaming 的语义；群组 mbox 用 stream_type 且 type 是群/联系人标志，不能套用。
            if (error is StreamingRestricted && !probed && uri0.path == "/api/streaming") {
                probed = true
                val probe = request(Uri.parse(deriveProbeUrl(uri0.toString())), userAgent, cookie)
                val probeError = playlistError(probe.first, probe.second)
                android.util.Log.i(LOG_TAG, "probe code=${probe.first} err=${probeError.javaClass.simpleName}")
                if (probeError is AdGate) {
                    uri = Uri.parse(withAdToken(uri0.toString(), probeError.adToken))
                    return@repeat
                }
                error = probeError
            }
            lastError = error
            if (error is AdGate) {
                adAttempts++
                // 第一次立即带 adToken 重试（多数可跳过广告片直接播）；仍被拦说明服务端
                // 按 adTime 计时，第二次先等够 adTime 再重试。
                if (adAttempts >= 2) Thread.sleep(error.adTimeSeconds.coerceIn(0, MAX_AD_WAIT_SECONDS) * 1000L)
                uri = Uri.parse(withAdToken(uri0.toString(), error.adToken))
                return@repeat
            }
            // 31341「转码任务排队中」是可恢复的：批量下载时同时向 streaming 取址，
            // 冷文件常常还没生成完，隔一会儿再要就有了。真机实测过「再试一次就正常」，
            // 早期实现直接抛错，把一个 3 秒就能自愈的状态变成了失败记录。
            if (error is TranscodingPending && pendingAttempts < MAX_PENDING_ATTEMPTS) {
                val wait = PENDING_BACKOFF_MS * (pendingAttempts + 1)
                pendingAttempts++
                android.util.Log.i(LOG_TAG, "pending retry #$pendingAttempts after ${wait}ms")
                Thread.sleep(wait)
                return@repeat
            }
            throw error
        }
        // 轮次用完时真实原因可能是 31341 排队 / 133 广告门 / 占位清单，
        // 一律说成「非会员广告校验未通过」会误导；把最后一次错误原样抛出。
        throw lastError ?: StreamingRestricted(RESTRICTED_MESSAGE)
    }

    /** 读取一次响应，返回 (HTTP 状态码, 响应体)。200 读 input，其余读 errorStream。 */
    private fun request(uri: Uri, userAgent: String, cookie: String?): Pair<Int, String> {
        val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(8192)
                val output = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(output.length + count <= MAX_PLAYLIST_CHARS) { "播放清单大小异常。" }
                    output.append(buffer, 0, count)
                }
                output.toString()
            }.orEmpty()
            return code to body
        } finally {
            connection.disconnect()
        }
    }

    private const val LOG_TAG = "ShikeStream"
    private const val MAX_PLAYLIST_CHARS = 1024 * 1024
    private const val MAX_ROUNDS = 5

    /** 转码排队（31341）最多再等 3 次，退避 0.8s / 1.6s / 2.4s，合计不到 5s。 */
    private const val MAX_PENDING_ATTEMPTS = 3
    private const val PENDING_BACKOFF_MS = 800L
    private const val MAX_AD_WAIT_SECONDS = 15
    private const val RESTRICTED_MESSAGE =
        "百度网盘限制了该文件的在线转码播放（错误码 133，非会员广告校验未通过）"
}
