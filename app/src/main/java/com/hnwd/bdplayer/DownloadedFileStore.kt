package com.hnwd.bdplayer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

/** The system downloader only owns the staged file. Published files belong to MediaStore. */
internal object DownloadedFileStore {
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    class InvalidStage : IOException("下载暂存文件已丢失或不完整")

    fun stagedFile(context: Context, name: String): File {
        require(name.matches(Regex("[0-9a-f-]+\\.part")))
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("无法访问下载缓存")
        return File(directory, name)
    }

    fun reserve(context: Context, task: DownloadTask, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, task.mediaType.takeUnless { '*' in it } ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${DownloadLogic.FOLDER}/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values) ?: throw IOException("无法创建下载文件")
    }

    fun isPending(context: Context, output: Uri): Boolean? {
        val cursor = context.contentResolver.query(
            output, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null,
        ) ?: throw IOException("无法读取下载目录")
        return cursor.use { if (it.moveToFirst()) it.getInt(0) != 0 else null }
    }

    fun requireCompleteStage(context: Context, task: DownloadTask): File {
        val file = stagedFile(context, task.stagingName)
        if (!file.isFile || task.bytesTotal >= 0 && file.length() != task.bytesTotal) throw InvalidStage()
        return file
    }

    fun publish(context: Context, task: DownloadTask, file: File) {
        val resolver = context.contentResolver
        val output = task.outputUri.toUri()
        val input = try {
            file.inputStream()
        } catch (error: java.io.FileNotFoundException) {
            if (!file.exists()) throw InvalidStage()
            throw error
        }
        input.use {
            val stream = resolver.openOutputStream(output, "wt") ?: throw IOException("无法写入下载目录")
            val copied = stream.use { input.copyTo(it, 128 * 1024) }
            if (task.bytesTotal >= 0 && copied != task.bytesTotal) throw InvalidStage()
        }
        val updated = resolver.update(output, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
        check(updated == 1) { "无法发布下载文件" }
    }

    fun discardPending(context: Context, uri: Uri) {
        runCatching {
            val resolver = context.contentResolver
            if (isPending(context, uri) == true) resolver.delete(uri, null, null)
        }
    }
}
