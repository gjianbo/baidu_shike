package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection

/** PDF 存在应用私有缓存中，下载验证完成后才供阅读器复用。 */
internal object PdfCache {
    // 与音频 dlink 播放、文档下载共用同一 UA（文档 §5.3 / §6.3）
    const val USER_AGENT = PanApi.DLINK_USER_AGENT
    const val MAX_BYTES = 80L * 1024 * 1024

    private const val ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L
    private const val CACHE_BYTES = 320L * 1024 * 1024
    private val activeFiles = mutableSetOf<File>()
    private fun dir(context: Context): File = File(context.cacheDir, "pdf")

    @Synchronized
    fun find(context: Context, key: String): File? =
        File(dir(context), "$key.pdf").takeIf { it.isFile }?.also {
            it.setLastModified(System.currentTimeMillis())
        }

    @Synchronized
    fun publish(context: Context, key: String, temp: File): File {
        val existing = find(context, key)
        if (existing != null) {
            discard(temp)
            return existing
        }
        val cached = File(dir(context), "$key.pdf")
        check(temp.renameTo(cached)) { "无法保存文档缓存，请重试。" }
        activeFiles.remove(temp)
        activeFiles.add(cached)
        try {
            prune(context)
        } finally {
            release(cached)
        }
        return cached
    }

    @Synchronized
    fun target(context: Context): File {
        val directory = dir(context).apply { mkdirs() }
        prune(context)
        return File.createTempFile("document-", ".pdf", directory).also { activeFiles.add(it) }
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
        val files = dir(context).listFiles().orEmpty().filter { it.isFile && it.extension == "pdf" }
        val cached = files.filterNot { it.name.startsWith("document-") }
        files.filter { it.name.startsWith("document-") && it !in activeFiles && it.lastModified() < cutoff }
            .forEach(File::delete)
        var total = cached.filter { it in activeFiles }.sumOf(File::length)
        cached.filterNot { it in activeFiles }.sortedByDescending(File::lastModified).forEach { file ->
            if (total + file.length() <= CACHE_BYTES) total += file.length()
            else file.delete()
        }
    }
}

internal class InvalidPdfLink(message: String) : IllegalStateException(message)

internal class PdfFileFetcher(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var generation = 0

    fun start(source: Uri, onResult: (Result<File>) -> Unit) =
        fetch({ download(source, it) }, onResult)

    fun startQuark(fileId: String, onResult: (Result<File>) -> Unit) {
        val userAgent = WebSettings.getDefaultUserAgent(context)
        val drive = QuarkPersonalDrive.openSession(userAgent)
        fetch({ file ->
            val uri = (drive ?: throw QuarkLoginRequired()).pdfLink(fileId)
            val request = QuarkPersonalDrive.fileRequest(uri, userAgent)
            QuarkPersonalDrive.fileClient.newCall(request).execute().use { response ->
                checkStatus(response.code)
                val body = checkNotNull(response.body) { "文档内容为空，请重新打开。" }
                body.byteStream().use { savePdf(file, it, body.contentLength()) }
            }
        }, onResult)
    }

    private fun fetch(download: (File) -> File, onResult: (Result<File>) -> Unit) {
        cancel()
        val id = generation
        worker = executor.submit {
            val result = runCatching {
                val file = PdfCache.target(context)
                try {
                    download(file)
                } catch (error: Throwable) {
                    PdfCache.discard(file)
                    throw error
                }
            }
            handler.post {
                if (id != generation) {
                    result.getOrNull()?.let(PdfCache::discard)
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
            // dlink 是 302 跳 CDN，签名自带，不需要 Cookie；UA 与文档实测一致
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", PdfCache.USER_AGENT)
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            checkStatus(connection.responseCode)
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            return connection.inputStream.use { savePdf(file, it, declared) }
        } finally {
            connection.disconnect()
        }
    }

    private fun checkStatus(code: Int) {
        if (code in setOf(400, 401, 403, 404, 410)) {
            throw InvalidPdfLink("文档下载失败（HTTP $code），请重新打开。")
        }
        check(code == HttpURLConnection.HTTP_OK) { "文档下载失败（HTTP $code），请重新打开。" }
    }

    private fun savePdf(file: File, input: InputStream, declared: Long): File {
        check(declared <= PdfCache.MAX_BYTES) { "文档超过 80 MB，暂不支持在线阅读。" }
        file.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) error("下载已取消。")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= PdfCache.MAX_BYTES) { "文档超过 80 MB，暂不支持在线阅读。" }
                output.write(buffer, 0, count)
            }
            check(total > 5) { "文档内容为空，请重新打开。" }
            check(declared <= 0 || total == declared) { "文档下载不完整，请重新打开。" }
        }
        file.inputStream().use { header ->
            val head = ByteArray(5)
            if (header.read(head) != 5 || String(head, Charsets.US_ASCII) != "%PDF-") {
                throw InvalidPdfLink("下载内容不是有效的 PDF，请重新打开。")
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
