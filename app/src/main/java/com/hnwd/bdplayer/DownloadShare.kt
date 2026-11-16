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
        if (!ShareLogic.canShare(task.state, task.outputUri, task.downloadId)) return Outcome.NOT_READY
        val uri = uriOf(context, task) ?: return Outcome.NOT_READY
        // 文件可能已被用户在系统文件管理器里删掉：记录还在，uri 已经读不到了。
        if (!readable(context, uri)) return Outcome.NOT_READY

        val type = ShareLogic.typeOf(task.mediaType, task.name) { extension ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, task.name)
            // ClipData 让系统的授权逻辑与「最近分享」都能拿到正确的 uri 与类型。
            clipData = ClipData.newUri(context.contentResolver, task.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, ShareLogic.CHOOSER_TITLE).apply {
            // 部分 ROM 的选择器会丢掉内层 flag，这里再挂一次。
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            Outcome.LAUNCHED
        }.getOrDefault(Outcome.NO_TARGET)
    }

    /**
     * 批量分享：一次 ACTION_SEND_MULTIPLE 带多个 uri，同样不复制文件。
     * 读不到的文件（已被清理）会被跳过，跳过几条如实回传，界面要说出来。
     */
    fun shareMultiple(context: Context, tasks: List<DownloadTask>): MultiResult {
        val items = tasks.mapNotNull { task ->
            if (!ShareLogic.canShare(task.state, task.outputUri, task.downloadId)) return@mapNotNull null
            val uri = uriOf(context, task) ?: return@mapNotNull null
            if (!readable(context, uri)) return@mapNotNull null
            uri to ShareLogic.typeOf(task.mediaType, task.name) { extension ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            }
        }
        val skipped = tasks.size - items.size
        if (items.isEmpty()) return MultiResult(Outcome.NOT_READY, shared = 0, skipped = skipped)
        val uris = ArrayList(items.map { it.first })
        val type = ShareLogic.multiType(items.map { it.second })
        val clip = ClipData.newRawUri(ShareLogic.CHOOSER_TITLE, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, ShareLogic.multiChooserTitle(uris.size)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            MultiResult(Outcome.LAUNCHED, shared = uris.size, skipped = skipped)
        }.getOrDefault(MultiResult(Outcome.NO_TARGET, shared = 0, skipped = skipped))
    }

    /** 打开一次文件描述符就够判断：能打开才值得把 uri 交给别人。 */
    private fun readable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
