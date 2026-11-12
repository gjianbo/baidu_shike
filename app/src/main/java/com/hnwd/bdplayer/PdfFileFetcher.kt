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

internal class InvalidPdfLink(message: String) : IllegalStateException(message)

internal class PdfFileFetcher(private val context: Context) {
    private val cache = PreviewCache.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var generation = 0

    /** [userAgent] 必须是生成 dlink 的那个 WebView 的 UA，在主线程取好后传进来。 */
    fun start(
        source: Uri,
        userAgent: String,
        onProgress: (PdfProgress) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) = fetch({ file, emit -> download(source, file, userAgent, emit) }, onProgress, onResult)

    fun startQuark(
        fileId: String,
        onProgress: (PdfProgress) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) {
        val userAgent = WebSettings.getDefaultUserAgent(context)
        val drive = QuarkPersonalDrive.openSession(userAgent)
        fetch({ file, emit ->
            val uri = (drive ?: throw QuarkLoginRequired()).pdfLink(fileId)
            val request = QuarkPersonalDrive.fileRequest(uri, userAgent)
            QuarkPersonalDrive.fileClient.newCall(request).execute().use { response ->
                checkStatus(response.code)
                val body = checkNotNull(response.body) { "文档内容为空，请重新打开。" }
                body.byteStream().use { savePdf(file, it, body.contentLength(), emit) }
            }
        }, onProgress, onResult)
    }

    private fun fetch(
        download: (File, (Long, Long) -> Unit) -> File,
        onProgress: (PdfProgress) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) {
        cancel()
        val id = generation
        // 后台只负责投递，代次校验放在主线程：取消/换文档后迟到的进度不会刷新旧弹窗。
        val emit: (Long, Long) -> Unit = { done, total ->
            handler.post {
                if (id == generation) onProgress(PdfProgress(done, total))
            }
        }
        worker = executor.submit {
            val result = runCatching {
                val file = cache.target(PreviewCache.Kind.PDF)
                try {
                    download(file, emit)
                } catch (error: Throwable) {
                    cache.discard(file)
                    throw error
                }
            }
            handler.post {
                if (id != generation) {
                    result.getOrNull()?.let(cache::discard)
                    return@post
                }
                worker = null
                onResult(result)
            }
        }
    }

    private fun download(source: Uri, file: File, userAgent: String, emit: (Long, Long) -> Unit): File {
        val connection = URL(source.toString()).openConnection() as HttpsURLConnection
        try {
            // dlink 是 302 跳 CDN，签名自带，不需要 Cookie；但签名绑定了生成 dlink 时的 WebView UA，
            // 大文件 CDN 会严格校验，用 PanApi.DLINK_USER_AGENT("pan.baidu.com") 会 403 sign error。
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            checkStatus(connection.responseCode)
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            return connection.inputStream.use { savePdf(file, it, declared, emit) }
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

    private fun savePdf(file: File, input: InputStream, declared: Long, emit: (Long, Long) -> Unit): File {
        // Content-Length 才是实际要下的体积（docview 常把大原件转成小副本），
        // 按网盘列表里的原始 size 预拦会误伤。declared < 0 表示分块传输，交给下面的累计校验。
        check(!pdfTooLarge(declared)) { PDF_TOO_LARGE_MESSAGE }
        file.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            var lastEmitAt = 0L
            var lastEmitBytes = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) error("下载已取消。")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(!pdfTooLarge(total)) { PDF_TOO_LARGE_MESSAGE }
                output.write(buffer, 0, count)
                // 读一块就刷一次会把主线程打满：按字节增量或时间间隔节流。
                val now = System.currentTimeMillis()
                if (total - lastEmitBytes >= EMIT_BYTES || now - lastEmitAt >= EMIT_INTERVAL_MS) {
                    emit(total, declared)
                    lastEmitAt = now
                    lastEmitBytes = total
                }
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

    private companion object {
        const val EMIT_BYTES = 512L * 1024L
        const val EMIT_INTERVAL_MS = 120L
    }
}
