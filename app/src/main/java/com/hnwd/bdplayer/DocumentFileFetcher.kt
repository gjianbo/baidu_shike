package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection

/**
 * 百度走 dlink（跟随 302，UA 必须是生成 dlink 的 WebView UA），夸克走原文件 download_url。
 * 两条链路都校验 HTTP 响应，并根据打开目标检查文件长度和大小上限。
 */
internal class DocumentFileFetcher(private val context: Context) {
    private val cache = PreviewCache.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var generation = 0

    /** [userAgent] 必须是生成 dlink 的那个 WebView 的 UA，在主线程取好后传进来。 */
    fun start(
        source: Uri,
        userAgent: String,
        target: DocumentTarget,
        onProgress: (PdfProgress) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) = fetch({ file, emit -> download(source, file, userAgent, target, emit) }, onProgress, onResult)

    fun startQuark(
        fileId: String,
        name: String,
        target: DocumentTarget,
        onProgress: (PdfProgress) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) = fetch({ file, emit ->
        val userAgent = WebSettings.getDefaultUserAgent(context)
        val drive = QuarkPersonalDrive.openSession(userAgent) ?: throw QuarkLoginRequired()
        val uri = drive.download(QuarkFile(fileId, name, false))
        val request = QuarkPersonalDrive.fileRequest(uri, userAgent)
        QuarkPersonalDrive.fileClient.newCall(request).execute().use { response ->
            checkDocumentResponse(response.code, response.header("Content-Type"))
            val body = checkNotNull(response.body) { "文档内容为空，请重新打开。" }
            body.byteStream().use { save(file, it, body.contentLength(), target, emit) }
        }
    }, onProgress, onResult)

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
                val file = cache.target(PreviewCache.Kind.DOCUMENT)
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

    private fun download(
        source: Uri,
        file: File,
        userAgent: String,
        target: DocumentTarget,
        emit: (Long, Long) -> Unit,
    ): File {
        val connection = URL(source.toString()).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            checkDocumentResponse(connection.responseCode, connection.contentType)
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            return connection.inputStream.use { save(file, it, declared, target, emit) }
        } finally {
            connection.disconnect()
        }
    }

    private fun save(
        file: File,
        input: InputStream,
        declared: Long,
        target: DocumentTarget,
        emit: (Long, Long) -> Unit,
    ): File {
        check(!documentTooLarge(target, declared)) { documentTooLargeMessage(target) }
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
                check(!documentTooLarge(target, total)) { documentTooLargeMessage(target) }
                output.write(buffer, 0, count)
                val now = System.currentTimeMillis()
                if (total - lastEmitBytes >= EMIT_BYTES || now - lastEmitAt >= EMIT_INTERVAL_MS) {
                    emit(total, declared)
                    lastEmitAt = now
                    lastEmitBytes = total
                }
            }
            checkDocumentLength(target, declared, total)
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
