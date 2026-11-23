package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import androidx.core.net.toUri

internal fun mediaCacheKey(account: String, entry: PanEntry): String =
    MessageDigest.getInstance("SHA-256")
        .digest(listOf(account, entry.origin.name, entry.sourceId, entry.gid, entry.messageId, entry.owner, entry.fileId)
            .joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * 清单缓存键：在 [mediaCacheKey] 上再带上清晰度档位。
 *
 * 为什么必须带：切档后如果还命中上一档的清单，用户会「选了 1080p 却还是 480 的画面」，
 * 而且看起来像功能没生效。原始文件直链（`link`）仍用 [mediaCacheKey]，它与档位无关。
 */
internal fun playlistCacheKey(account: String, entry: PanEntry, quality: VideoQuality): String =
    mediaCacheKey(account, entry) + ":q" + quality.name

internal class MediaAddressCache(context: Context) {
    private val directory = File(context.cacheDir, "media-addresses")

    fun playlist(key: String, uri: Uri): VideoStream? =
        read(key, "m3u8")?.let { VideoStream(uri, it) }

    fun savePlaylist(key: String, stream: VideoStream) = write(key, "m3u8", stream.playlist)

    fun clearPlaylist(key: String) = file(key, "m3u8").delete()

    /**
     * 清掉该视频**所有档位**的清单缓存。
     *
     * 重试/切清晰度时用：不知道用户上一档存的是哪一档（也可能几档都存过），
     * 只清当前档会留下会命中的旧清单。
     */
    fun clearPlaylists(key: String) {
        directory.listFiles().orEmpty().forEach { cached ->
            if (cached.name.startsWith("$key:q") && cached.name.endsWith(".m3u8")) cached.delete()
        }
    }

    fun link(key: String): Uri? =
        read(key, "url")?.let { it.decodeToString().toUri().takeIf(PanApi::isCachedFileLink) }

    fun saveLink(key: String, uri: Uri) = write(key, "url", uri.toString().encodeToByteArray())

    fun clearLink(key: String) = file(key, "url").delete()

    private fun file(key: String, suffix: String) = File(directory, "$key.$suffix")

    private fun read(key: String, suffix: String): ByteArray? {
        val file = file(key, suffix)
        if (!isFresh(file, System.currentTimeMillis())) {
            file.delete()
            return null
        }
        return runCatching { AtomicFile(file).readFully() }.getOrNull()
    }

    private fun write(key: String, suffix: String, bytes: ByteArray) {
        runCatching {
            directory.mkdirs()
            val atomic = AtomicFile(file(key, suffix))
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            val now = System.currentTimeMillis()
            var total = 0L
            directory.listFiles().orEmpty().sortedByDescending(File::lastModified).forEach { cached ->
                if (isFresh(cached, now) && total + cached.length() <= MAX_BYTES) {
                    total += cached.length()
                } else cached.delete()
            }
        }
    }

    private fun isFresh(file: File, now: Long): Boolean =
        file.isFile && now - file.lastModified() in 0 until TTL_MS

    private companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val MAX_BYTES = 16 * 1024 * 1024L
    }
}
