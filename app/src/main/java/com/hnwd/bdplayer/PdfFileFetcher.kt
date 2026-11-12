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
                val file = cache.target(PreviewCache.Kind.PDF)
                try {
                    download(file)
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

    private fun download(source: Uri, file: File): File {
        val connection = URL(source.toString()).openConnection() as HttpsURLConnection
        try {
            // dlink 是 302 跳 CDN，签名自带，不需要 Cookie；UA 与文档实测一致
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", PanApi.DLINK_USER_AGENT)
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
        check(declared <= MAX_BYTES) { "文档超过 80 MB，暂不支持在线阅读。" }
        file.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) error("下载已取消。")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= MAX_BYTES) { "文档超过 80 MB，暂不支持在线阅读。" }
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

    private companion object {
        const val MAX_BYTES = 80L * 1024 * 1024
    }
}
