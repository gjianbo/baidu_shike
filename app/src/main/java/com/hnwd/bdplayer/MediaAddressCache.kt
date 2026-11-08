package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

internal fun mediaCacheKey(account: String, entry: PanEntry): String =
    MessageDigest.getInstance("SHA-256")
        .digest(listOf(account, entry.gid, entry.messageId, entry.owner, entry.fileId)
            .joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

internal class MediaAddressCache(context: Context) {
    private val directory = File(context.cacheDir, "media-addresses")

    fun playlist(key: String, uri: Uri): VideoStream? =
        read(key, "m3u8")?.let { VideoStream(uri, it) }

    fun savePlaylist(key: String, stream: VideoStream) = write(key, "m3u8", stream.playlist)

    fun clearPlaylist(key: String) = file(key, "m3u8").delete()

    fun link(key: String): Uri? =
        read(key, "url")?.let { Uri.parse(it.decodeToString()).takeIf(PanApi::isFileLink) }

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
