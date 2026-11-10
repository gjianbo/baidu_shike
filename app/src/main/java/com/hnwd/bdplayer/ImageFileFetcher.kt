package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** 图片扩展名、MIME 与文件头校验，全部基于平台 API。 */
internal object ImageTypes {
    val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImageName(name: String): Boolean = ext(name) in EXTENSIONS

    fun mime(extension: String): String = when (extension) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }

    private fun hasSignature(head: ByteArray, size: Int, vararg bytes: Int): Boolean {
        if (size < bytes.size) return false
        return bytes.indices.all { head[it] == bytes[it].toByte() }
    }

    /** dlink 失效时 CDN 常返回 HTML 错误页，先看文件头再交给解码器。 */
    fun looksLikeImage(head: ByteArray, size: Int): Boolean {
        if (hasSignature(head, size, 0xFF, 0xD8, 0xFF)) return true
        if (hasSignature(head, size, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return true
        if (size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) return true
        if (size >= 6) {
            val tag = String(head, 0, 6, Charsets.US_ASCII)
            if (tag == "GIF87a" || tag == "GIF89a") return true
        }
        if (size >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return true
        // HEIC / HEIF / AVIF 容器都是 ISO BMFF，偏移 4 处为 ftyp
        if (size >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return true
        return false
    }
}

/** 图片缓存独立存放，不与已稳定的 PDF 缓存互相影响。 */
internal object ImageCache {
    const val USER_AGENT = PanApi.DLINK_USER_AGENT
    const val MAX_BYTES = 30L * 1024 * 1024
    private const val ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L
    private const val CACHE_BYTES = 160L * 1024 * 1024
    private val activeFiles = mutableSetOf<File>()

    private fun dir(context: Context): File = File(context.cacheDir, "images")
    private fun cached(context: Context, key: String, ext: String): File = File(dir(context), "$key.$ext")

    @Synchronized
    fun find(context: Context, key: String, ext: String): File? =
        cached(context, key, ext).takeIf { it.isFile && it.length() > 12 }?.also {
            it.setLastModified(System.currentTimeMillis())
        }

    @Synchronized
    fun publish(context: Context, key: String, ext: String, temp: File): File {
        find(context, key, ext)?.let {
            discard(temp)
            return it
        }
        val target = cached(context, key, ext)
        dir(context).mkdirs()
        check(temp.renameTo(target)) { "无法保存图片缓存，请重试。" }
        activeFiles.remove(temp)
        activeFiles.add(target)
        try {
            prune(context)
        } finally {
            release(target)
        }
        return target
    }

    @Synchronized
    fun target(context: Context): File {
        val directory = dir(context).apply { mkdirs() }
        prune(context)
        return File.createTempFile("image-", ".tmp", directory).also { activeFiles.add(it) }
    }

    @Synchronized
    fun retain(file: File): Boolean {
        if (!file.isFile) return false
        activeFiles.add(file)
        return true
    }

    @Synchronized
    fun discard(file: File) {
        release(file)
        file.delete()
    }

    @Synchronized
    fun release(file: File) {
        activeFiles.remove(file)
    }

    @Synchronized
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
        val files = dir(context).listFiles().orEmpty().filter { it.isFile }
        val temps = files.filter { it.name.startsWith("image-") }
        val cached = files.filterNot { it.name.startsWith("image-") }
        temps.filter { it !in activeFiles && it.lastModified() < cutoff }.forEach(File::delete)
        var total = cached.filter { it in activeFiles }.sumOf(File::length)
        cached.filterNot { it in activeFiles }.sortedByDescending(File::lastModified).forEach { file ->
            if (total + file.length() <= CACHE_BYTES) total += file.length()
            else file.delete()
        }
    }
}

internal class InvalidImageLink(message: String) : IllegalStateException(message)

/**
 * 图片下载器。百度走 dlink（UA=pan.baidu.com、跟随 302），夸克走原文件 download_url。
 * 两条链路下载完成后统一做大小与文件头校验。
 */
internal class ImageFileFetcher(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var generation = 0

    fun start(source: Uri, onResult: (Result<File>) -> Unit) =
        fetch({ file -> download(source, file) }, onResult)

    fun startQuark(fileId: String, name: String, onResult: (Result<File>) -> Unit) =
        fetch({ file -> downloadQuark(fileId, name, file) }, onResult)

    private fun fetch(download: (File) -> File, onResult: (Result<File>) -> Unit) {
        cancel()
        val id = generation
        worker = executor.submit {
            val result = runCatching {
                val file = ImageCache.target(context)
                try {
                    download(file)
                } catch (error: Throwable) {
                    ImageCache.discard(file)
                    throw error
                }
            }
            handler.post {
                if (id != generation) {
                    result.getOrNull()?.let(ImageCache::discard)
                    return@post
                }
                worker = null
                onResult(result)
            }
        }
    }

    private fun download(source: Uri, file: File): File {
        val connection = URL(source.toString()).openConnection() as HttpsURLConnection
        try {
            // dlink 302 跳 CDN，签名自带，UA 与文档/音视频链路一致
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", ImageCache.USER_AGENT)
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            checkStatus(connection.responseCode)
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            connection.inputStream.use { saveImage(file, it, declared) }
        } finally {
            connection.disconnect()
        }
        return file
    }

    private fun downloadQuark(fileId: String, name: String, file: File): File {
        val userAgent = WebSettings.getDefaultUserAgent(context)
        val drive = QuarkPersonalDrive.openSession(userAgent) ?: throw QuarkLoginRequired()
        val uri = drive.download(QuarkFile(fileId, name, false))
        val request = QuarkPersonalDrive.fileRequest(uri, userAgent)
        QuarkPersonalDrive.fileClient.newCall(request).execute().use { response ->
            checkStatus(response.code)
            val body = checkNotNull(response.body) { "图片内容为空，请重新打开。" }
            body.byteStream().use { saveImage(file, it, body.contentLength()) }
        }
        return file
    }

    private fun checkStatus(code: Int) {
        if (code in setOf(400, 401, 403, 404, 410)) {
            throw InvalidImageLink("图片下载失败（HTTP $code），请重新打开。")
        }
        check(code == HttpURLConnection.HTTP_OK) { "图片下载失败（HTTP $code），请重新打开。" }
    }

    private fun saveImage(file: File, input: InputStream, declared: Long): File {
        check(declared <= ImageCache.MAX_BYTES) { "图片超过 30 MB，暂不支持在线预览。" }
        file.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) error("下载已取消。")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= ImageCache.MAX_BYTES) { "图片超过 30 MB，暂不支持在线预览。" }
                output.write(buffer, 0, count)
            }
            // 内容为空或长度不符多半是 dlink 已失效/被劫持，按地址失效处理以便重新取址
            if (total <= 12) throw InvalidImageLink("图片内容为空，请重新打开。")
            if (declared > 0 && total != declared) {
                throw InvalidImageLink("图片下载不完整，请重新打开。")
            }
        }
        file.inputStream().use { header ->
            val head = ByteArray(16)
            val count = header.read(head)
            if (!ImageTypes.looksLikeImage(head, count)) {
                throw InvalidImageLink("下载内容不是有效图片，地址可能已失效，请重试。")
            }
        }
        return file
    }

    fun cancel() {
        generation++
        worker?.cancel(true)
        worker = null
    }

    fun close() {
        cancel()
        executor.shutdownNow()
    }
}

internal data class ImagePreviewItem(
    val fileId: String,
    val title: String,
    val key: String,
    val url: String,
    val shareInfoUrl: String = "",
    val diskDirectory: String? = null,
)

internal data class LoadedImagePreview(val url: String, val bytes: ByteArray)

private class ImagePreviewExpired : IllegalStateException("预览地址已失效")

/**
 * 优先使用列表自带的签名；缺失或失效时按文件重新取址，图片字节仅在内存中缓存。
 */
internal object ImagePreviewLoader {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, LoadedImagePreview>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LoadedImagePreview): Int = value.bytes.size
    }

    private val lock = Any()
    /** 同一 key 只发一次网络请求，后来的加载（含预取）挂到同一结果上。 */
    private val inFlight = HashMap<String, MutableList<(Result<LoadedImagePreview>) -> Unit>>()

    fun load(
        item: ImagePreviewItem,
        provider: SourceProvider,
        thumbnail: Boolean = false,
        userAgent: String? = null,
        onResult: (Result<LoadedImagePreview>) -> Unit,
    ) {
        val key = item.key
        cache.get(key)?.let {
            onResult(Result.success(it))
            return
        }
        val first = synchronized(lock) {
            val waiting = inFlight[key]
            if (waiting != null) {
                waiting.add(onResult)
                false
            } else {
                inFlight[key] = mutableListOf(onResult)
                true
            }
        }
        if (!first) return
        val worker = if (thumbnail) thumbnailExecutor else executor
        worker.execute {
            val result = runCatching { loadPreview(item, provider, userAgent) }
            result.getOrNull()?.let { cache.put(key, it) }
            handler.post {
                val callbacks = synchronized(lock) { inFlight.remove(key).orEmpty() }
                callbacks.forEach { it(result) }
            }
        }
    }

    fun discard(key: String) {
        cache.remove(key)
    }

    private fun loadPreview(item: ImagePreviewItem, provider: SourceProvider, userAgent: String?): LoadedImagePreview {
        if (item.url.isNotBlank()) {
            try {
                return LoadedImagePreview(item.url, fetch(item.url))
            } catch (_: ImagePreviewExpired) {
                // 只重取一次，避免对失效地址重复请求。
            }
        }
        val url = when (provider) {
            SourceProvider.BAIDU -> refreshBaiduPreview(item, userAgent)
            SourceProvider.QUARK -> QuarkPersonalDrive.openSession(userAgent)?.imagePreviewUrl(item.fileId, item.title).orEmpty()
        }
        check(url.isNotBlank() && url != item.url) { "未获取到可用的预览地址" }
        return LoadedImagePreview(url, fetch(url))
    }

    private fun refreshBaiduPreview(item: ImagePreviewItem, userAgent: String?): String {
        if (item.shareInfoUrl.isNotEmpty()) {
            return PanApi.parseImageShareInfo(readBaiduMetadata(item.shareInfoUrl, userAgent), item.fileId)
        }
        val directory = item.diskDirectory ?: return ""
        val token = readBaiduMetadata(PanApi.groupListSession(), userAgent)
            .optJSONObject("result")?.optString("bdstoken").orEmpty()
        if (token.isEmpty()) return ""
        val search = readBaiduMetadata(PanApi.diskSearch(token, item.title, directory), userAgent)
        return PanApi.parseDiskSearch(search).firstOrNull { it.entry.fileId == item.fileId }?.entry?.previewUrl.orEmpty()
    }

    private fun readBaiduMetadata(url: String, userAgent: String?): JSONObject {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && uri.authority == PanApi.HOST)
        val cookie = CookieManager.getInstance().getCookie(url)
        check(!cookie.isNullOrBlank()) { "请登录百度网盘后重试" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Cookie", cookie)
            userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "无法获取图片预览地址" }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            check(json.optInt("errno", -1) == 0) { "无法获取图片预览地址" }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(url: String): ByteArray {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) throw ImagePreviewExpired()
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            // 两个网盘的看图地址都是 URL 自带签名，不需要 Cookie / Referer / 特殊 UA
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            if (code in setOf(400, 401, 403, 404, 410)) throw ImagePreviewExpired()
            check(code == HttpURLConnection.HTTP_OK) { "预览图片加载失败（HTTP $code）" }
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            check(declared <= MAX_BYTES) { "预览图片过大，请改用原图打开。" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_BYTES) { "预览图片过大，请改用原图打开。" }
                    output.write(buffer, 0, count)
                }
                if (total <= 12) throw ImagePreviewExpired()
            }
            val bytes = output.toByteArray()
            // 403 之外的失效也可能返回 HTML 错误页，统一按地址过期处理
            if (!ImageTypes.looksLikeImage(bytes, bytes.size)) throw ImagePreviewExpired()
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}
