package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection

/**
 * PDF 的临时落盘位置。文档没有转码流，只有 sharedownload 的 dlink；
 * androidx.pdf 只接受 file:// / content://，所以必须先下载成文件。
 * 这里只写应用私有 cacheDir，退出即删，不写用户可见存储、不持久缓存。
 */
internal object PdfCache {
    // 与音频 dlink 播放、文档下载共用同一 UA（文档 §5.3 / §6.3）
    const val USER_AGENT = PanApi.DLINK_USER_AGENT
    const val MAX_BYTES = 80L * 1024 * 1024

    private const val ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L
    private val activeFiles = mutableSetOf<File>()
    private fun dir(context: Context): File = File(context.cacheDir, "pdf")

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
        activeFiles.remove(file)
        file.delete()
    }

    @Synchronized
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
        dir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.extension == "pdf" && file !in activeFiles && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}

internal class PdfFileFetcher(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var generation = 0

    fun start(source: Uri, onResult: (Result<File>) -> Unit) {
        cancel()
        val id = generation
        worker = executor.submit {
            val result = runCatching {
                val file = PdfCache.target(context)
                try {
                    download(source, file)
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
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "文档下载失败（HTTP $code），请重新打开。" }
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            check(declared <= 0 || declared <= PdfCache.MAX_BYTES) { "文档超过 80 MB，暂不支持在线阅读。" }
            connection.inputStream.use { input ->
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
                }
            }
            file.inputStream().use { input ->
                val head = ByteArray(5)
                check(input.read(head) == 5 && String(head, Charsets.US_ASCII) == "%PDF-") {
                    "下载内容不是有效的 PDF，可能登录已失效，请重新登录后重试。"
                }
            }
            return file
        } finally {
            connection.disconnect()
        }
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
