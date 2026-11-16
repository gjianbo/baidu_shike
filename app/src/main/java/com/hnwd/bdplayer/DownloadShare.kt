package com.hnwd.bdplayer

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri

/**
 * 「把已下载的文件交给别的 App」的接线层：只做 uri 解析 + ACTION_SEND + 授权。
 *
 * 下载完成的文件落在 MediaStore 的「下载/拾课」，记录的 outputUri 就是
 * `content://media/external/downloads/<id>`，可以直接作为 EXTRA_STREAM 分享，
 * 接收方靠 FLAG_GRANT_READ_URI_PERMISSION 读到内容，不需要把文件再复制一份到缓存。
 * 历史记录若没有 outputUri，退回系统下载队列的 downloadId 取 uri。
 */
internal object DownloadShare {

    enum class Outcome { LAUNCHED, NOT_READY, NO_TARGET }

    /** 批量分享的结果：[skipped] 是文件已不在（uri 读不到）而被跳过的条数。 */
    data class MultiResult(val outcome: Outcome, val shared: Int = 0, val skipped: Int = 0)

    /** 记录对应的文件 uri：优先 MediaStore 输出 uri，历史记录退回系统下载队列。 */
    fun uriOf(context: Context, task: DownloadTask): Uri? =
        task.outputUri.takeIf(String::isNotBlank)?.toUri()
            ?: runCatching {
                context.getSystemService(DownloadManager::class.java)?.getUriForDownloadedFile(task.downloadId)
            }.getOrNull()

    fun share(context: Context, task: DownloadTask): Outcome {
        val file = shareableFile(context, task) ?: return Outcome.NOT_READY
        return launchChooser(context, singleFileIntent(context, file), ShareLogic.CHOOSER_TITLE)
    }

    /** 先过滤读不到的文件，再按实际文件数选择分享方式。 */
    fun shareMultiple(context: Context, tasks: List<DownloadTask>): MultiResult {
        val files = tasks.mapNotNull { shareableFile(context, it) }
        val skipped = tasks.size - files.size
        if (files.isEmpty()) return MultiResult(Outcome.NOT_READY, skipped = skipped)

        val single = files.singleOrNull()
        val intent = if (single != null) singleFileIntent(context, single) else multipleFilesIntent(files)
        val title = if (single != null) ShareLogic.CHOOSER_TITLE else ShareLogic.multiChooserTitle(files.size)
        val outcome = launchChooser(context, intent, title)
        return MultiResult(outcome, if (outcome == Outcome.LAUNCHED) files.size else 0, skipped)
    }

    private data class ShareableFile(val uri: Uri, val type: String, val name: String)

    private fun shareableFile(context: Context, task: DownloadTask): ShareableFile? {
        if (!ShareLogic.canShare(task.state, task.outputUri, task.downloadId)) return null
        val uri = uriOf(context, task) ?: return null
        if (!readable(context, uri)) return null
        val type = ShareLogic.typeOf(task.mediaType, task.name) { extension ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return ShareableFile(uri, type, task.name)
    }

    private fun singleFileIntent(context: Context, file: ShareableFile): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = file.type
            putExtra(Intent.EXTRA_STREAM, file.uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, file.uri)
        }

    private fun multipleFilesIntent(files: List<ShareableFile>): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            val uris = ArrayList(files.map { it.uri })
            type = ShareLogic.multiType(files.map { it.type })
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(ShareLogic.CHOOSER_TITLE, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }

    private fun launchChooser(context: Context, intent: Intent, title: String): Outcome {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(flags)
        val chooser = Intent.createChooser(intent, title).addFlags(flags)
        return runCatching {
            context.startActivity(chooser)
            Outcome.LAUNCHED
        }.getOrDefault(Outcome.NO_TARGET)
    }

    /** 打开一次文件描述符就够判断：能打开才值得把 uri 交给别人。 */
    private fun readable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
