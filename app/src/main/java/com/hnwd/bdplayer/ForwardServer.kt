package com.hnwd.bdplayer

import android.content.Context
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 用户自己填写的 MeTube 解析服务地址。程序不预置任何服务器、账号或密码。 */
internal data class ForwardServerConfig(
    val baseUrl: String,
) {
    val displayHost: String
        get() = ForwardServerLogic.displayHost(baseUrl)
}

internal object ForwardServerStore {
    private const val PREFS = "forward_server"
    private const val KEY_URL = "base_url"

    fun load(context: Context): ForwardServerConfig? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, "").orEmpty().trim()
        // 清掉旧版轻解析遗留的用户名/密码字段，避免继续残留在设备上。
        if (prefs.contains("username") || prefs.contains("password") || prefs.contains("password_iv")) {
            prefs.edit().remove("username").remove("password").remove("password_iv").apply()
        }
        if (url.isBlank()) return null
        return ForwardServerConfig(url)
    }

    fun isConfigured(context: Context): Boolean = load(context) != null

    fun save(context: Context, config: ForwardServerConfig) {
        val baseUrl = requireNotNull(ForwardServerLogic.normalizeBaseUrl(config.baseUrl)) {
            ForwardServerLogic.INVALID_SERVER_URL
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, baseUrl)
            .remove("username")
            .remove("password")
            .remove("password_iv")
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

internal data class ForwardParseResult(
    val title: String,
    val videoUrl: String,
    val duration: Double,
    val ext: String,
)

/** 服务能连通，但返回的是当前版本不处理的结果（例如图集、没有直链）。 */
internal class ForwardUnsupportedException(message: String) : IllegalStateException(message)

/** 只做纯字符串/JSON 逻辑，JVM 单测可以直接覆盖。 */
internal object ForwardServerLogic {
    const val INVALID_SERVER_URL = "请输入完整的解析服务地址（http:// 或 https://）"
    const val MEDIA_PROBE_BYTES = 4096L
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")
    private val supportedBmffBrands = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
        "mp41", "mp42", "avc1", "hvc1", "hev1", "av01", "dash", "msdh", "M4V ", "qt  ",
    )

    private fun parseAbsoluteUrl(raw: String): HttpUrl? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        if (uri.rawAuthority.endsWith(':')) return null
        // 解析地址由用户自己填写（自用场景可能是局域网 HTTP），这里只要求结构合法。
        // 真正的SSRF防护发生在服务端 /resolve 的 validate_url；本端对下载直链仍然严格校验。
        return raw.toHttpUrlOrNull()
    }

    fun normalizeBaseUrl(raw: String): String? {
        val url = parseAbsoluteUrl(raw.trim()) ?: return null
        if (url.encodedQuery != null) return null
        return url.toString().trimEnd('/')
    }

    fun isLoopback(url: HttpUrl): Boolean = url.host.lowercase() in loopbackHosts

    fun displayHost(baseUrl: String): String =
        baseUrl.substringAfter("://", baseUrl).trimEnd('/')

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    fun mediaUrl(raw: String, server: HttpUrl): HttpUrl? =
        parseAbsoluteUrl(raw)?.takeIf { it.isHttps || sameOrigin(it, server) }

    fun mediaRedirect(current: HttpUrl, location: String, server: HttpUrl): HttpUrl? {
        val reference = runCatching { URI(location) }.getOrNull() ?: return null
        if (location.isBlank() || reference.rawFragment != null || reference.rawUserInfo != null) return null
        val candidate = when {
            reference.isAbsolute -> mediaUrl(location, server)
            location.startsWith("//") -> mediaUrl("${current.scheme}:$location", server)
            else -> current.resolve(location)?.let { mediaUrl(it.toString(), server) }
        } ?: return null
        return candidate.takeUnless { current.isHttps && !it.isHttps }
    }

    fun mediaReferer(url: HttpUrl): String? {
        val host = url.host.trimEnd('.')
        fun matches(vararg domains: String) = domains.any { host == it || host.endsWith(".$it") }
        return when {
            matches("bilivideo.com", "bilivideo.cn", "bilibili.com") -> "https://www.bilibili.com/"
            matches("xhscdn.com", "xiaohongshu.com") -> "https://www.xiaohongshu.com/"
            matches("twimg.com", "twitter.com", "x.com") -> "https://x.com/"
            matches("douyin.com", "douyinvod.com", "douyinpic.com", "iesdouyin.com", "bytecdn.cn", "bytecdn.com") ->
                "https://www.douyin.com/"
            else -> null
        }
    }

    fun hasSupportedMedia(contentType: String?, prefix: ByteArray): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && !type.startsWith("video/") && type !in setOf(
                "application/octet-stream", "binary/octet-stream", "application/mp4",
            )
        ) return false
        // Never trust a filename or MIME header alone: require a supported container header.
        if (prefix.size >= 16 && prefix.ascii(4, 4) == "ftyp") {
            val size = (0..3).fold(0L) { n, i -> (n shl 8) or (prefix[i].toLong() and 0xff) }
            if (size !in 16L..prefix.size.toLong() || size % 4 != 0L) return false
            return prefix.ascii(8, 4) in supportedBmffBrands ||
                (16 until size.toInt() step 4).any { prefix.ascii(it, 4) in supportedBmffBrands }
        }
        return isWebm(prefix)
    }

    private fun ByteArray.ascii(start: Int, length: Int): String =
        String(this, start, length, Charsets.US_ASCII)

    private fun isWebm(bytes: ByteArray): Boolean {
        if (bytes.size < 5 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))) {
            return false
        }
        // EBML variable-length integers: preserve the marker for element IDs, strip it for lengths.
        var cursor = 4
        fun readVint(end: Int, forId: Boolean): Long? {
            if (cursor >= end) return null
            val first = bytes[cursor].toInt() and 0xff
            if (first == 0) return null
            val width = Integer.numberOfLeadingZeros(first) - 23
            if (width > (if (forId) 4 else 8) || width > end - cursor) return null
            var value = (if (forId) first else first and (0xff ushr width)).toLong()
            repeat(width - 1) { value = (value shl 8) or (bytes[++cursor].toLong() and 0xff) }
            cursor++
            return value
        }
        val length = readVint(bytes.size, false) ?: return false
        if (length > bytes.size - cursor) return false
        val end = cursor + length.toInt()
        while (cursor < end) {
            val id = readVint(end, true) ?: return false
            val size = readVint(end, false) ?: return false
            if (size > end - cursor) return false
            if (id == 0x4282L) return size == 4L && bytes.ascii(cursor, 4) == "webm"
            cursor += size.toInt()
        }
        return false
    }

    /** MeTube /resolve 返回 `{"status":"ok","url":...,"title":...,"duration":...,"ext":...}`。 */
    fun parseResponse(raw: String): ForwardParseResult {
        val root = JSONObject(raw)
        val status = root.optString("status")
        check(status != "error") {
            root.optString("msg").ifBlank { root.optString("detail") }.ifBlank { "解析服务返回错误" }
        }
        check(status == "ok") { "解析服务返回了无法识别的结果" }
        val url = root.optString("url").trim()
        if (url.isBlank()) throw ForwardUnsupportedException("解析服务没有返回视频直链")
        return ForwardParseResult(
            title = root.optString("title").trim(),
            videoUrl = url,
            duration = root.optDouble("duration", 0.0),
            ext = root.optString("ext").trim(),
        )
    }

    fun extensionFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path.substringAfterLast('.', "").lowercase().takeIf {
            it.matches(Regex("[a-z0-9]{2,5}"))
        } ?: "mp4"
    }
}

internal class ForwardServerClient(
    private val config: ForwardServerConfig,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val baseUrl = requireNotNull(ForwardServerLogic.normalizeBaseUrl(config.baseUrl)) {
        ForwardServerLogic.INVALID_SERVER_URL
    }.toHttpUrl()
    private val directClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()
    private val loopbackClient by lazy {
        directClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname).filter { it.isLoopbackAddress }.ifEmpty {
                        throw UnknownHostException("环回地址没有解析到本机")
                    }
            })
            .build()
    }

    private fun execute(request: Request): Response {
        val transport = if (!request.url.isHttps && ForwardServerLogic.isLoopback(request.url)) loopbackClient else directClient
        return try {
            transport.newCall(request).execute()
        } catch (error: javax.net.ssl.SSLException) {
            throw java.io.IOException("TLS 连接失败，请检查该地址是否启用 HTTPS，以及证书是否受信任并与域名匹配", error)
        }
    }

    private fun endpoint(path: String): HttpUrl.Builder =
        baseUrl.newBuilder().encodedPath(baseUrl.encodedPath.trimEnd('/') + path)

    private fun resolveRequest(url: String): Request {
        val body = JSONObject().put("url", url).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(endpoint("/resolve").build())
            .post(body)
            .header("Accept", "application/json")
            .build()
    }

    /** 只解析元数据，服务端不下载、不写文件、不建下载队列。 */
    fun parse(url: String): ForwardParseResult =
        ForwardServerLogic.parseResponse(executeText(resolveRequest(url), "解析服务请求失败"))

    /**
     * 只确认服务可用：/resolve 能连通并返回可识别的 JSON 即可。
     * 服务端对某条链接报 error（例如该站点 cookies 失效）属于正常业务响应，不阻止保存地址。
     */
    fun checkService() {
        val json = JSONObject(executeText(resolveRequest(SAMPLE_URL), "解析服务请求失败"))
        val status = json.optString("status")
        val recognized = when (status) {
            "ok" -> (json.opt("url") as? String)?.let { ForwardServerLogic.mediaUrl(it.trim(), baseUrl) } != null
            "error" -> listOf("msg", "detail").any { (json.opt(it) as? String)?.isNotBlank() == true }
            else -> false
        }
        check(recognized) { "解析服务返回了无法识别的结果" }
    }

    companion object {
        // 用一个无需登录、能被快速拒绝的普通地址探测端点连通性；服务端 /resolve 不会下载文件。
        const val SAMPLE_URL = "https://example.com/"
    }

    /** Read only enough of the response to validate the media header. */
    fun probeVideo(url: String) = withVideoResponse(url, preview = true) { body, type ->
        validateMediaHeader(body, type)
    }

    fun download(url: String, destination: File, checkActive: () -> Unit = {}, onProgress: (Int) -> Unit) {
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()
        try {
            checkActive()
            withVideoResponse(url, preview = false) { body, type ->
                checkActive()
                saveMedia(body, type, part, checkActive, onProgress)
            }
            checkActive()
            check(part.renameTo(destination)) { "无法保存视频文件" }
        } finally {
            part.delete()
        }
    }

    private fun withVideoResponse(url: String, preview: Boolean, useBody: (ResponseBody, String?) -> Unit) {
        var target = requireNotNull(ForwardServerLogic.mediaUrl(url, baseUrl)) { "视频直链地址不安全或无效" }
        var followed = 0
        while (true) {
            // Rebuild each request from scratch: no API cookie, auth or previous-hop Referer.
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .apply {
                    ForwardServerLogic.mediaReferer(target)?.let { header("Referer", it) }
                    if (preview) header("Range", "bytes=0-${ForwardServerLogic.MEDIA_PROBE_BYTES - 1}")
                }
                .build()
            execute(request).use { response ->
                if (response.code !in setOf(301, 302, 303, 307, 308)) {
                    check(response.code == 200 || (preview && response.code == 206)) {
                        "视频直链返回 HTTP ${response.code}"
                    }
                    val body = checkNotNull(response.body) { "视频直链没有响应内容" }
                    useBody(body, response.header("Content-Type"))
                    return
                }
                check(followed++ < 5) { "视频直链重定向次数过多" }
                target = response.header("Location")?.let { ForwardServerLogic.mediaRedirect(target, it, baseUrl) }
                    ?: throw IllegalStateException("视频直链重定向地址不安全或无效")
            }
        }
    }

    private fun validateMediaHeader(body: ResponseBody, contentType: String?) {
        val source = body.source()
        val probe = ForwardServerLogic.MEDIA_PROBE_BYTES
        val prefix = source.peek().use { peek ->
            peek.request(probe)
            peek.readByteArray(minOf(probe, peek.buffer.size))
        }
        check(ForwardServerLogic.hasSupportedMedia(contentType, prefix)) {
            "视频直链未返回支持的 MP4、MOV 或 WebM 内容"
        }
    }

    private fun saveMedia(
        body: ResponseBody, contentType: String?, part: File, checkActive: () -> Unit, onProgress: (Int) -> Unit,
    ) {
        validateMediaHeader(body, contentType)
        val total = body.contentLength()
        body.byteStream().use { input ->
            part.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    checkActive()
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
                check(total < 0 || copied == total) { "视频下载不完整" }
            }
        }
    }

    private fun executeText(request: Request, prefix: String): String {
        execute(request).use { response ->
            check(response.code !in 300..399) { "$prefix：HTTP ${response.code}，解析接口不允许重定向" }
            // Bound the decoded response as well as the declared length (chunked/gzip may omit it).
            val body = response.body
            val text = if (body == null) "" else {
                val limit = 128L * 1024
                val source = body.source()
                check(body.contentLength() <= limit && !source.request(limit + 1)) {
                    "$prefix：响应内容过大"
                }
                source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            }
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).optString("msg").ifBlank {
                        JSONObject(text).optString("detail")
                    }
                }.getOrNull().orEmpty()
                throw IllegalStateException("$prefix：HTTP ${response.code}${if (detail.isBlank()) "" else " · $detail"}")
            }
            return text
        }
    }
}
