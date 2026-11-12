package com.hnwd.bdplayer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.edit
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载任务的唯一执行点：本地记录 + 系统 DownloadManager 队列。
 *
 * 传输交给系统（后台保活、断点续传、跨 Activity 存活），App 只负责排队与查询进度。
 * 并发上限由本对象控制：超过 [DownloadLogic.MAX_PARALLEL] 的任务留在本地记录里排队，
 * 有任务结束再补入队，避免一次性把临时直链的签名时效和大文件带宽耗光。
 */
internal object DownloadCenter {
    private const val PREF = "downloads"
    private const val KEY = "tasks"
    private val lock = Any()
    private val seq = AtomicLong()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(context: Context): MutableList<DownloadTask> =
        DownloadLogic.decodeTasks(prefs(context).getString(KEY, "[]")).toMutableList()

    private fun write(context: Context, tasks: List<DownloadTask>) {
        prefs(context).edit { putString(KEY, DownloadLogic.encodeTasks(tasks)) }
    }

    fun newId(): Long = System.currentTimeMillis() * 1000 + seq.incrementAndGet() % 1000

    fun tasks(context: Context): List<DownloadTask> = synchronized(lock) { read(context) }

    /**
     * 顶栏角标/设置页摘要读的是「实时活跃数」：必须先 refresh 把系统下载器已完成的
     * 任务推进到终态，否则用户一直停在主界面（不打开传输页）时角标永远不会消失。
     * refresh 内部只在状态真变化时写盘，因此每秒 ticker 调用没有额外写开销。
     */
    fun activeCount(context: Context): Int = synchronized(lock) {
        refresh(context).count { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
    }

    fun add(context: Context, task: DownloadTask) = synchronized(lock) {
        val list = read(context)
        list.add(task)
        write(context, list)
        pump(context, list)
    }

    /** 读一次系统进度、推进终态、补入队排队任务，返回最新列表。 */
    fun refresh(context: Context): List<DownloadTask> = synchronized(lock) {
        val list = read(context)
        val watched = list.filter {
            it.downloadId >= 0 && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING)
        }
        if (watched.isNotEmpty()) {
            // null 表示查询本身失败（权限/Cursor 异常），此时不能把任务误判成「记录丢失」。
            val statuses = query(context, watched.map { it.downloadId })
            if (statuses != null) {
                val now = System.currentTimeMillis()
                var changed = false
                for (index in list.indices) {
                    val task = list[index]
                    if (task.state != DownloadState.QUEUED && task.state != DownloadState.RUNNING) continue
                    // 本地排队、还没交给系统下载器的任务没有 id，不参与收敛。
                    if (task.downloadId < 0) continue
                    val status = statuses[task.downloadId]
                    val updated = when {
                        // 用户在系统下载管理器里删掉了记录：不能让它永远卡在 RUNNING、
                        // 占着并发槽与顶栏角标，标失败并给出可重试的文案。
                        status == null -> DownloadLogic.lostRecordTask(task, now)
                        status.code == DownloadManager.STATUS_SUCCESSFUL -> task.copy(
                            state = DownloadState.SUCCESS,
                            bytesDone = status.done,
                            bytesTotal = if (status.total > 0) status.total else task.sizeBytes,
                            finishedAt = now,
                            error = "",
                        )
                        status.code == DownloadManager.STATUS_FAILED -> task.copy(
                            state = DownloadState.FAILED,
                            bytesDone = status.done,
                            finishedAt = now,
                            error = DownloadLogic.failureText(
                                status.reason,
                                if (task.provider == SourceProvider.QUARK) DownloadRoute.QUARK
                                else DownloadRoute.BAIDU_GROUP,
                            ),
                        )
                        else -> task.copy(
                            state = DownloadState.RUNNING,
                            bytesDone = status.done,
                            bytesTotal = status.total,
                        )
                    }
                    if (updated != task) {
                        list[index] = updated
                        changed = true
                    }
                }
                if (changed) write(context, list)
                // pump 自己会判断是否需要写盘（有排队任务入队时）。
                pump(context, list)
            }
        }
        list.toList()
    }

    /**
     * 取消后保留一条 FAILED 记录（与上传侧「已取消上传」一致），这样历史里查得到、
     * 也能凭 url 重试；直接删记录会让用户以为任务凭空消失。
     */
    fun cancel(context: Context, id: Long) = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val task = list[index]
        if (task.downloadId >= 0) {
            runCatching { manager(context)?.remove(task.downloadId) }
        }
        list[index] = DownloadLogic.cancelledTask(task, System.currentTimeMillis())
        write(context, list)
        pump(context, list)
    }

    /** 页内重试复用已取到的直链；链接为空说明取址就没成功，必须回列表重新取。 */
    fun retry(context: Context, id: Long): Boolean = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        val task = list[index]
        if (task.url.isBlank()) return false
        if (task.downloadId >= 0) runCatching { manager(context)?.remove(task.downloadId) }
        list[index] = task.copy(
            state = DownloadState.QUEUED,
            downloadId = -1,
            bytesDone = 0,
            bytesTotal = -1,
            finishedAt = 0,
            error = "",
        )
        write(context, list)
        pump(context, list)
        true
    }

    /** 只清成功记录，失败项保留着方便重试；不删除本机已下载的文件。 */
    fun clearFinished(context: Context) = synchronized(lock) {
        val list = read(context)
        if (list.removeAll { it.state == DownloadState.SUCCESS }) write(context, list)
    }

    /**
     * 「清理失败的记录」：先让系统下载器删掉失败任务（连同它的半成品文件），再删本地记录。
     * 成功记录、排队/进行中任务、已完成文件一律不动；返回清掉的条数。
     */
    fun clearFailed(context: Context): Int = synchronized(lock) {
        val list = read(context)
        list.filter { it.state == DownloadState.FAILED && it.downloadId >= 0 }.forEach { task ->
            runCatching { manager(context)?.remove(task.downloadId) }
        }
        val before = list.size
        if (list.removeAll { it.state == DownloadState.FAILED }) write(context, list)
        before - list.size
    }

    fun open(context: Context, task: DownloadTask): Boolean {
        if (task.downloadId < 0) return false
        val uri = runCatching { manager(context)?.getUriForDownloadedFile(task.downloadId) }
            .getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, task.mediaType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun manager(context: Context): DownloadManager? =
        context.getSystemService(DownloadManager::class.java)

    /** 把排队任务补进系统队列，直到并发上限。 */
    private fun pump(context: Context, list: MutableList<DownloadTask>) {
        var slots = DownloadLogic.MAX_PARALLEL - list.count {
            it.downloadId >= 0 && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING)
        }
        if (slots <= 0) return
        var changed = false
        list.indices.sortedBy { list[it].createdAt }.forEach { index ->
            if (slots <= 0) return@forEach
            val task = list[index]
            if (task.state != DownloadState.QUEUED || task.downloadId >= 0) return@forEach
            val id = enqueue(context, task)
            if (id < 0) {
                list[index] = task.copy(
                    state = DownloadState.FAILED,
                    error = "无法加入系统下载队列",
                    finishedAt = System.currentTimeMillis(),
                )
            } else {
                list[index] = task.copy(downloadId = id)
                slots--
            }
            changed = true
        }
        if (changed) write(context, list)
    }

    private fun enqueue(context: Context, task: DownloadTask): Long {
        val service = manager(context) ?: return -1
        val request = DownloadManager.Request(task.url.toUri())
        DownloadLogic.headers(
            if (task.provider == SourceProvider.QUARK) DownloadRoute.QUARK else DownloadRoute.BAIDU_GROUP,
            task.userAgent,
        ).forEach { (key, value) -> request.addRequestHeader(key, value) }
        // 同一毫秒内重复下载同名文件会撞名并互相覆盖；id 后缀保证目标路径唯一，
        // 且重试复用同一个 task.id，不会反复产生新文件名。
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA).format(Date(task.createdAt)) +
            "-${task.id % 100_000}"
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            DownloadLogic.relativePath(task.name, stamp),
        )
        request.setTitle(task.name)
        request.setMimeType(task.mediaType)
        // 页面内已有进度，隐藏系统通知避免重复提示；缺少权限时退回系统通知。
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
        request.setAllowedOverMetered(true)
        return runCatching { service.enqueue(request) }.getOrElse {
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            runCatching { service.enqueue(request) }.getOrDefault(-1)
        }
    }

    private data class Status(val code: Int, val reason: Int, val done: Long, val total: Long)

    /**
     * 返回 null 表示「查询失败」（权限/Cursor 异常），与「查询成功但某些 id 不存在」区分开；
     * 后者才允许把任务收敛成失败，前者必须原样保留，避免一次偶发异常把全部在跑任务判死。
     */
    private fun query(context: Context, ids: List<Long>): Map<Long, Status>? {
        val service = manager(context) ?: return null
        return runCatching {
            val result = mutableMapOf<Long, Status>()
            service.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
                fun column(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 }
                val idAt = column(DownloadManager.COLUMN_ID) ?: return@use
                val statusAt = column(DownloadManager.COLUMN_STATUS)
                val reasonAt = column(DownloadManager.COLUMN_REASON)
                val doneAt = column(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalAt = column(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (cursor.moveToNext()) {
                    result[cursor.getLong(idAt)] = Status(
                        code = statusAt?.let { cursor.getInt(it) } ?: DownloadManager.STATUS_PENDING,
                        reason = reasonAt?.let { cursor.getInt(it) } ?: 0,
                        done = doneAt?.let { cursor.getLong(it) } ?: 0,
                        total = totalAt?.let { cursor.getLong(it) } ?: -1,
                    )
                }
            }
            result
        }.getOrNull()
    }
}
