package com.hnwd.bdplayer

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap

/** 一条下载记录对应文件的可展示信息，[location] 已拼好相对目录与文件名。 */
internal data class DownloadFileInfo(
    val name: String,
    val sizeBytes: Long,
    val location: String,
    val mime: String,
)

/**
 * 长按菜单里真正动文件的动作：重命名 / 删除 / 读取文件信息。
 *
 * 文件在 MediaStore 的「下载/拾课」，App 是这些条目的 owner，所以 update 与 delete
 * 都不需要额外存储权限；记录本身由 [DownloadCenter] 维护。
 */
internal object DownloadFileOps {

    enum class Result { OK, UNAVAILABLE, FAILED }

    /**
     * 改名只改 MediaStore 的 DISPLAY_NAME（MediaProvider 会同步重命名真实文件），
     * 再回写记录的 name，让列表与分享面板显示同一个名字。
     */
    fun rename(context: Context, task: DownloadTask, newName: String): Result {
        val uri = DownloadShare.uriOf(context, task) ?: return Result.UNAVAILABLE
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            mimeOf(newName)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
        }
        val rows = runCatching { context.contentResolver.update(uri, values, null, null) }
        if (rows.isFailure || rows.getOrDefault(0) <= 0) return Result.UNAVAILABLE
        DownloadCenter.renameRecord(context, task.id, newName)
        return Result.OK
    }

    /**
     * 删除文件并移除记录。条目已经被系统清理过时（delete 返回 0）同样按成功处理：
     * 用户的目标是「文件不在了」，此时只需要把记录一起清掉。
     */
    fun deleteFile(context: Context, task: DownloadTask): Result {
        val uri = DownloadShare.uriOf(context, task)
        if (uri != null) {
            val removed = runCatching { context.contentResolver.delete(uri, null, null) }
            if (removed.isFailure) return Result.FAILED
        }
        DownloadCenter.forget(context, task.id)
        return Result.OK
    }

    /** 直接查 MediaStore，拿真实文件名 / 体积 / 目录；查询失败退回记录里的字段。 */
    fun info(context: Context, task: DownloadTask): DownloadFileInfo {
        var name = task.name
        var size = if (task.bytesTotal > 0) task.bytesTotal else task.sizeBytes
        var dir = "${Environment.DIRECTORY_DOWNLOADS}/${DownloadLogic.FOLDER}/"
        var mime = task.mediaType
        val uri = DownloadShare.uriOf(context, task)
        if (uri != null) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                    cursor.getString(1)?.takeIf { it.isNotBlank() }?.let { dir = it }
                    val queried = cursor.getLong(2)
                    if (queried > 0) size = queried
                    cursor.getString(3)?.takeIf { it.isNotBlank() }?.let { mime = it }
                }
            }
        }
        return DownloadFileInfo(name, size, dir + name, mime)
    }

    private fun mimeOf(name: String): String? {
        val extension = DownloadFileLogic.extensionOf(name)
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}
