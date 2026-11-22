package com.hnwd.bdplayer

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.net.toUri
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Persists the queue. DownloadManager transfers bytes and wakes the queue on completion. */
internal object DownloadCenter {
    private const val PREF = "downloads"
    private const val KEY = "tasks"
    private const val MAX_LINK_RETRIES = 1

    /** 分片进度落盘节流：太少 UI 不动，太频整表重编码会卡。 */
    private const val PROGRESS_INTERVAL_MS = 700L
    private val lock = Any()
    private val seq = AtomicLong()
    private val resolving = mutableMapOf<Long, Any>()

    /**
     * 正在跑 HLS 分片下载的任务（群组视频原文件直链拿不到时的通路）。
     * value 是取消标志：分片循环每片检查一次，取消后立刻停手。
     */
    private val playlistJobs = mutableMapOf<Long, AtomicBoolean>()
    private val publishing = mutableSetOf<Long>()
    private val waiters = mutableListOf<() -> Unit>()

    // DownloadProvider stores raw status codes. A conditional delete protects completed
    // files even when completion races the UI. Never fall back to unconditional remove here.
    private val downloadsUri = "content://downloads/my_downloads".toUri()
    private const val PROVIDER_SUCCESS = "200"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(context: Context): MutableList<DownloadTask> =
        DownloadLogic.decodeTasks(prefs(context).getString(KEY, "[]")).toMutableList()

    private fun write(context: Context, tasks: List<DownloadTask>, durable: Boolean = false) {
        val editor = prefs(context).edit().putString(KEY, DownloadLogic.encodeTasks(tasks))
        if (durable) check(editor.commit()) { "无法保存下载队列" } else editor.apply()
    }

    fun newId(): Long = seq.updateAndGet { previous ->
        maxOf(previous + 1, System.currentTimeMillis() * 1000)
    }

    fun tasks(context: Context): List<DownloadTask> = synchronized(lock) { read(context) }

    fun activeCount(context: Context): Int = refresh(context).count(DownloadLogic::isActive)

    fun resume(context: Context) {
        if (tasks(context).any(DownloadLogic::isActive)) DownloadQueueJob.enqueue(context)
    }

    fun add(context: Context, tasks: List<DownloadTask>): Boolean = synchronized(lock) {
        val list = read(context)
        val previous = list.toList()
        list.addAll(tasks)
        write(context, list, durable = true)
        if (DownloadQueueJob.enqueue(context)) return true
        write(context, previous, durable = true)
        false
    }

    fun drain(context: Context, onIdle: () -> Unit) = synchronized(lock) {
        val list = read(context)
        var recovered = false
        for (index in list.indices) {
            val task = list[index]
            if (DownloadLogic.isActive(task) && !task.publishing && task.downloadId < 0 &&
                task.stagingName.isNotBlank() && task.id !in playlistJobs
            ) {
                val id = findStagedDownload(context, task)
                list[index] = if (id == null) {
                    // 系统队列里找不到这条暂存下载（进程在入队前后被杀、或分片任务中断）：
                    // 只清 stagingName 会留下一个「RUNNING 但永不推进」的僵尸任务，
                    // 必须整条退回 QUEUED 重新取址。
                    runCatching { DownloadedFileStore.stagedFile(context, task.stagingName).delete() }
                    DownloadLogic.requeue(task)
                } else {
                    task.copy(downloadId = id)
                }
                recovered = true
            }
        }
        if (recovered) write(context, list, durable = true)
        pump(context, refreshLocked(context, retryOnQueryFailure = true))
        // 分片任务不在 resolving/publishing 里，却同样占着槽位下载几百 MB：
        // 这里漏判会让 JobService 立刻收工、进程失去保活，后台下载容易被系统回收。
        if (resolving.isEmpty() && publishing.isEmpty() && playlistJobs.isEmpty()) {
            onIdle()
        } else waiters.add(onIdle)
    }

    private fun completeDrainIfIdle() {
        // 分片下载任务不计入 resolving/publishing，但同样在占槽干活：
        // 提前让 JobService 收工会让进程失去保活，几百 MB 的任务很容易被系统回收。
        if (resolving.isNotEmpty() || publishing.isNotEmpty() || playlistJobs.isNotEmpty()) return
        val completed = waiters.toList()
        waiters.clear()
        completed.forEach { it() }
    }

    fun refresh(context: Context): List<DownloadTask> = synchronized(lock) { refreshLocked(context) }

    private fun refreshLocked(context: Context, retryOnQueryFailure: Boolean = false): List<DownloadTask> {
        val list = read(context)
        val watched = list.filter { it.downloadId >= 0 && !it.publishing && DownloadLogic.isActive(it) }
        val statuses = if (watched.isEmpty()) emptyMap() else query(context, watched.map { it.downloadId })
        if (statuses == null) {
            if (retryOnQueryFailure) throw IOException("无法读取系统下载队列")
            return list
        }

        val now = System.currentTimeMillis()
        var changed = false
        for (index in list.indices) {
            val task = list[index]
            if (!DownloadLogic.isActive(task) || task.downloadId < 0 || task.publishing) continue
            val status = statuses[task.downloadId]
            val updated = when (status?.code) {
                null -> DownloadLogic.lostRecordTask(task, now)
                DownloadManager.STATUS_SUCCESSFUL -> task.copy(
                    state = if (task.stagingName.isBlank()) DownloadState.SUCCESS else DownloadState.RUNNING,
                    bytesDone = status.done,
                    bytesTotal = status.total.takeIf { it >= 0 } ?: status.done,
                    finishedAt = if (task.stagingName.isBlank()) now else 0,
                    error = "", url = "", publishing = task.stagingName.isNotBlank(),
                )
                DownloadManager.STATUS_FAILED -> {
                    if (DownloadLogic.hasAccountBinding(task) && task.linkAttempts < MAX_LINK_RETRIES &&
                        DownloadLogic.canRefreshLink(status.reason) && removeUnsuccessful(context, task.downloadId)
                    ) {
                        cleanupStage(context, task.copy(downloadId = -1))
                        DownloadLogic.requeue(task).copy(linkAttempts = task.linkAttempts + 1)
                    } else {
                        task.copy(
                            bytesDone = status.done,
                            url = task.url.takeUnless { DownloadLogic.canRefreshLink(status.reason) }.orEmpty(),
                        ).failed(DownloadLogic.failureText(status.reason, route(task)), now)
                    }
                }
                else -> task.copy(
                    state = if (status.code == DownloadManager.STATUS_PENDING) {
                        DownloadState.QUEUED
                    } else {
                        DownloadState.RUNNING
                    },
                    bytesDone = status.done,
                    bytesTotal = status.total,
                )
            }
            if (updated != task) {
                list[index] = updated
                changed = true
            }
        }
        if (changed) write(context, list, durable = list.any { it.publishing })
        return list
    }

    fun cancel(context: Context, id: Long): Boolean = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst { it.id == id && DownloadLogic.isActive(it) }
        if (index < 0) return false
        val task = list[index]
        val playlist = playlistJobs[task.id]
        if (playlist != null) {
            // 分片循环每片读一次标志；这里只置位并改记录，暂存文件的清理交给下载线程收尾。
            playlist.set(true)
            playlistJobs.remove(task.id)
            list[index] = DownloadLogic.cancelledTask(task, System.currentTimeMillis())
            write(context, list, durable = true)
            DownloadQueueJob.enqueue(context)
            completeDrainIfIdle()
            return true
        }
        // Legacy system requests target the public file directly. Let those finish safely.
        if (task.publishing || task.downloadId >= 0 && task.stagingName.isBlank()) return false
        val systemId = if (task.downloadId >= 0) task.downloadId else {
            if (task.stagingName.isBlank()) -1 else {
                runCatching { findStagedDownload(context, task) ?: -1 }.getOrElse { return false }
            }
        }
        if (systemId >= 0) {
            val status = query(context, listOf(systemId)) ?: return false
            if (status[systemId]?.code == DownloadManager.STATUS_SUCCESSFUL) {
                refresh(context)
                DownloadQueueJob.enqueue(context)
                return false
            }
            if (!removeUnsuccessful(context, systemId)) {
                refresh(context)
                DownloadQueueJob.enqueue(context)
                return false
            }
        }
        resolving.remove(id)
        list[index] = DownloadLogic.cancelledTask(task, System.currentTimeMillis())
        write(context, list, durable = true)
        cleanupStage(context, task.copy(downloadId = -1))
        DownloadQueueJob.enqueue(context)
        completeDrainIfIdle()
        true
    }

    fun retry(context: Context, id: Long): Boolean = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst { it.id == id && it.state == DownloadState.FAILED }
        if (index < 0) return false
        val task = list[index]
        if (task.publishing) {
            list[index] = task.copy(state = DownloadState.RUNNING, error = "", finishedAt = 0)
        } else {
            if (!DownloadLogic.hasAccountBinding(task) || task.needsVerification) return false
            if (task.downloadId >= 0 && !removeUnsuccessful(context, task.downloadId)) return false
            cleanupStage(context, task.copy(downloadId = -1))
            list[index] = DownloadLogic.requeue(task).copy(linkAttempts = 0)
        }
        write(context, list, durable = true)
        if (DownloadQueueJob.enqueue(context)) return true
        list[index] = task
        write(context, list, durable = true)
        false
    }

    fun submitVerified(
        context: Context, id: Long, resolution: DownloadLinkResolver.Resolution,
    ): Boolean = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst {
            it.id == id && it.state == DownloadState.FAILED && it.needsVerification &&
                it.provider == SourceProvider.BAIDU && DownloadLogic.hasAccountBinding(it)
        }
        if (index < 0 || id in resolving) return false
        if (usedSlots(list) >= DownloadLogic.MAX_PARALLEL) return false
        val task = list[index]
        if (task.downloadId >= 0 && !removeUnsuccessful(context, task.downloadId)) return false
        submit(context, list, index, DownloadLogic.requeue(task).copy(linkAttempts = 0), resolution)
        DownloadQueueJob.enqueue(context)
        true
    }

    /** 重命名成功后同步记录里的显示名，列表、分享面板与打开方式都以它为准。 */
    fun renameRecord(context: Context, id: Long, name: String) = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        list[index] = list[index].copy(name = name)
        write(context, list, durable = true)
    }

    /** 只移除记录，不动已发布的文件（是否删文件由 [DownloadFileOps.delete] 的 withFile 决定）。 */
    fun forget(context: Context, id: Long) = synchronized(lock) {
        val list = read(context)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        // 半成品暂存要一并清掉，成功记录的 stagingName 早已为空。
        cleanupStage(context, list[index])
        list.removeAt(index)
        write(context, list, durable = true)
    }

    /**
     * 批量移除记录：一次读写、一次 `commit()`。
     * 逐条调 [forget] 是 N 次 fsync + N 次整表重写，删几十条能卡出几百毫秒。
     */
    fun forgetMany(context: Context, ids: Collection<Long>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val list = read(context)
        val targets = list.filter { it.id in ids }
        if (targets.isEmpty()) return@synchronized
        targets.forEach { cleanupStage(context, it) }
        val kept = list.filterNot { it.id in ids }
        write(context, kept, durable = true)
    }

    fun clearFinished(context: Context, ids: Set<Long>): Int = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized 0
        val list = read(context)
        val before = list.size
        if (list.removeAll {
            if (it.state != DownloadState.SUCCESS || it.id !in ids) return@removeAll false
            cleanupStage(context, it)
            true
        }) write(context, list)
        before - list.size
    }

    fun clearFailed(context: Context, ids: Set<Long>? = null): Int = synchronized(lock) {
        if (ids?.isEmpty() == true) return@synchronized 0
        val list = read(context)
        val before = list.size
        if (list.removeAll { task ->
            if (task.state != DownloadState.FAILED || (ids != null && task.id !in ids)) return@removeAll false
            if (task.publishing) {
                task.outputUri.takeIf(String::isNotBlank)?.toUri()?.let {
                    DownloadedFileStore.discardPending(context, it)
                }
                cleanupStage(context, task)
                true
            } else {
                val removed = task.downloadId < 0 || removeUnsuccessful(context, task.downloadId)
                if (removed) cleanupStage(context, task.copy(downloadId = -1))
                removed
            }
        }) write(context, list)
        before - list.size
    }

    private fun removeUnsuccessful(context: Context, downloadId: Long): Boolean {
        val uri = ContentUris.withAppendedId(downloadsUri, downloadId)
        val deleted = runCatching {
            context.contentResolver.delete(uri, "${DownloadManager.COLUMN_STATUS} != ?", arrayOf(PROVIDER_SUCCESS))
        }.getOrDefault(0)
        return deleted > 0 || query(context, listOf(downloadId))?.isEmpty() == true
    }

    fun open(context: Context, task: DownloadTask): Boolean {
        val uri = task.outputUri.takeIf(String::isNotBlank)?.toUri()
            ?: runCatching { manager(context)?.getUriForDownloadedFile(task.downloadId) }.getOrNull()
            ?: return false
        // TS 转码副本交给应用内播放器（A/V 同步可控）；其它文件仍按原样调起外部应用。
        val intent = if (DownloadLogic.shouldOpenInApp(task)) {
            LocalVideoActivity.intent(context, uri, task.name, task.mediaType)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, task.mediaType)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun manager(context: Context): DownloadManager? =
        context.getSystemService(DownloadManager::class.java)

    private fun route(task: DownloadTask): DownloadRoute = task.entry?.let(DownloadLogic::routeOf)
        ?: if (task.provider == SourceProvider.QUARK) DownloadRoute.QUARK else DownloadRoute.BAIDU_GROUP

    private fun DownloadTask.failed(message: String, now: Long, verify: Boolean = false) = copy(
        state = DownloadState.FAILED, finishedAt = now, error = message,
        url = if (entry == null && !verify) url else "", needsVerification = verify,
    )

    private fun usedSlots(list: List<DownloadTask>): Int = list.count {
        DownloadLogic.isActive(it) && (it.downloadId >= 0 || it.id in resolving || it.id in playlistJobs)
    }

    private fun pump(context: Context, tasks: List<DownloadTask>) {
        val list = tasks.toMutableList()
        val app = context.applicationContext
        for (index in list.indices) {
            val task = list[index]
            if (task.state == DownloadState.SUCCESS && task.stagingName.isNotBlank()) {
                cleanupCompletedStage(app, list, index)
            }
            if (!DownloadLogic.isActive(task)) continue
            if (task.publishing && publishing.add(task.id)) publish(app, task)
        }
        var slots = DownloadLogic.MAX_PARALLEL - usedSlots(list)
        var changed = false
        for (index in list.indices.sortedBy { list[it].id }) {
            if (slots <= 0) break
            val task = list[index]
            if (task.id in resolving || task.state != DownloadState.QUEUED || task.downloadId >= 0 || task.publishing) continue
            if (DownloadLogic.hasAccountBinding(task)) {
                val attempt = Any()
                resolving[task.id] = attempt
                slots--
                resolve(app, task, attempt)
            } else {
                list[index] = task.copy(url = "").failed(
                    "旧记录缺少来源或账号，请回到文件列表重新下载", System.currentTimeMillis(),
                )
                changed = true
            }
        }
        if (changed) write(app, list)
    }

    private fun resolve(app: Context, task: DownloadTask, attempt: Any) {
        AppIo.execute {
            val result = runCatching { DownloadLinkResolver.resolve(app, task) }
            synchronized(lock) {
                if (resolving[task.id] !== attempt) return@synchronized
                resolving.remove(task.id)
                val list = read(app)
                val index = list.indexOfFirst { DownloadLogic.acceptsResolution(it, task.id) }
                if (index >= 0) {
                    val current = list[index]
                    result.fold(
                        onSuccess = { submit(app, list, index, current, it) },
                        onFailure = { error ->
                            val verify = error is DownloadLinkResolver.VerificationRequired
                            val message = when (error) {
                                is DownloadLinkResolver.VerificationRequired,
                                is DownloadLinkResolver.Failure,
                                is TransferSession.Failure -> error.message.orEmpty()
                                else -> "无法获取下载地址，请检查网络后重试"
                            }
                            list[index] = current.failed(message, System.currentTimeMillis(), verify)
                            write(app, list, durable = true)
                        },
                    )
                }
                pump(app, list)
                completeDrainIfIdle()
            }
        }
    }

    private fun submit(
        context: Context, list: MutableList<DownloadTask>, index: Int,
        task: DownloadTask, resolution: DownloadLinkResolver.Resolution,
    ) {
        when (resolution) {
            is DownloadLinkResolver.Resolution.Single -> {
                val staged = task.copy(stagingName = "${task.id}-${UUID.randomUUID()}.part")
                list[index] = staged
                write(context, list, durable = true)
                val id = runCatching { enqueue(context, staged, resolution.link) }.getOrDefault(-1)
                list[index] = if (id >= 0) staged.copy(downloadId = id, url = "") else {
                    task.failed("无法加入系统下载队列，请检查存储空间后重试", System.currentTimeMillis())
                }
                write(context, list, durable = true)
            }
            is DownloadLinkResolver.Resolution.Playlist -> startPlaylist(context, list, index, task, resolution.plan)
        }
    }

    /**
     * 起一条 HLS 分片下载。
     *
     * 刻意不塞进 DownloadManager：系统下载器只认单条 URL，没法把 200 个分片拼成一个文件。
     * 状态机复用现有那条 —— 先以 RUNNING + stagingName 占位，下完把 publishing 置真，
     * 之后 `pump → publish` 走的是同一条 MediaStore 发布路径（含长度校验）。
     */
    private fun startPlaylist(
        context: Context, list: MutableList<DownloadTask>, index: Int,
        task: DownloadTask, plan: HlsPlan,
    ) {
        // 有 #EXT-X-MAP 初始化段说明分片是 fragmented MP4，拼接产物不能再叫 .ts。
        val fragmentedMp4 = plan.initSegment != null
        val staged = task.copy(
            name = DownloadLogic.playlistFileName(task.name, fragmentedMp4),
            mediaType = if (fragmentedMp4) "video/mp4" else "video/mp2t",
            playlistCopy = true,
            state = DownloadState.RUNNING,
            stagingName = "${task.id}-${UUID.randomUUID()}.part",
            bytesDone = 0,
            bytesTotal = -1,
            finishedAt = 0,
            error = "",
            url = "",
            publishing = false,
            outputUri = "",
        )
        val cancel = AtomicBoolean(false)
        playlistJobs[task.id] = cancel
        list[index] = staged
        write(context, list, durable = true)
        AppIo.execute { runPlaylistDownload(context.applicationContext, staged, plan, cancel) }
    }

    private fun runPlaylistDownload(
        app: Context, task: DownloadTask, plan: HlsPlan, cancel: AtomicBoolean,
    ) {
        var lastProgressWrite = 0L
        val result = runCatching {
            GroupVideoDownload.download(
                app, task.stagingName, plan,
                onProgress = { done ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressWrite >= PROGRESS_INTERVAL_MS) {
                        lastProgressWrite = now
                        synchronized(lock) {
                            val list = read(app)
                            val index = list.indexOfFirst {
                                it.id == task.id && it.stagingName == task.stagingName
                            }
                            if (index >= 0 && DownloadLogic.isActive(list[index])) {
                                list[index] = list[index].copy(
                                    state = DownloadState.RUNNING, bytesDone = done, bytesTotal = -1,
                                )
                                write(app, list)
                            }
                        }
                    }
                },
                isCancelled = { cancel.get() },
            )
        }
        synchronized(lock) {
            playlistJobs.remove(task.id)
            val list = read(app)
            val index = list.indexOfFirst { it.id == task.id && it.stagingName == task.stagingName }
            if (index >= 0) {
                val current = list[index]
                list[index] = result.fold(
                    onSuccess = { length ->
                        current.copy(
                            state = DownloadState.RUNNING,
                            bytesDone = length,
                            bytesTotal = length,
                            publishing = true,
                            error = "",
                        )
                    },
                    onFailure = { error ->
                        val message = if (error is GroupVideoDownload.Cancelled) {
                            DownloadLogic.CANCELLED_ERROR
                        } else {
                            "转码视频下载失败：" + (error.message ?: "分片拉取中断")
                        }
                        current.copy(
                            state = DownloadState.FAILED,
                            finishedAt = System.currentTimeMillis(),
                            bytesDone = 0,
                            bytesTotal = -1,
                            error = message,
                        )
                    },
                )
                write(app, list, durable = true)
                if (result.isFailure) {
                    runCatching { DownloadedFileStore.stagedFile(app, task.stagingName).delete() }
                }
            }
            pump(app, read(app))
            completeDrainIfIdle()
        }
    }

    private fun findStagedDownload(context: Context, task: DownloadTask): Long? {
        val service = manager(context) ?: throw IOException("系统下载队列不可用")
        val cursor = service.query(DownloadManager.Query()) ?: throw IOException("无法读取系统下载队列")
        return cursor.use {
            val id = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val description = it.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
            while (it.moveToNext()) {
                if (it.getString(description) == task.stagingName) return@use it.getLong(id)
            }
            null
        }
    }

    private fun publish(app: Context, task: DownloadTask) {
        AppIo.execute {
            val result = runCatching {
                val pending = task.outputUri.takeIf(String::isNotBlank)?.toUri()
                    ?.let { DownloadedFileStore.isPending(app, it) }
                // Publication can succeed just before the process stops recording its result.
                if (pending == false) return@runCatching
                val staged = DownloadedFileStore.requireCompleteStage(app, task)
                val reserved = if (pending == true) task else {
                    val output = DownloadedFileStore.reserve(app, task, fileName(task))
                    try {
                        synchronized(lock) {
                            val list = read(app)
                            val index = list.indexOfFirst { it.id == task.id && it.publishing }
                            check(index >= 0)
                            list[index] = list[index].copy(outputUri = output.toString())
                            write(app, list, durable = true)
                            list[index]
                        }
                    } catch (error: Exception) {
                        DownloadedFileStore.discardPending(app, output)
                        throw error
                    }
                }
                DownloadedFileStore.publish(app, reserved, staged)
            }
            synchronized(lock) {
                val list = read(app)
                val index = list.indexOfFirst { it.id == task.id && it.publishing }
                if (index >= 0) {
                    val current = list[index]
                    val stageLost = result.exceptionOrNull() is DownloadedFileStore.InvalidStage
                    list[index] = if (result.isSuccess) current.copy(
                        state = DownloadState.SUCCESS, finishedAt = System.currentTimeMillis(),
                        publishing = false, error = "",
                    ) else DownloadLogic.publicationFailed(current, stageLost, System.currentTimeMillis())
                    write(app, list, durable = true)
                    if (result.isSuccess) cleanupCompletedStage(app, list, index)
                    if (stageLost) {
                        current.outputUri.takeIf(String::isNotBlank)?.toUri()?.let {
                            DownloadedFileStore.discardPending(app, it)
                        }
                        cleanupStage(app, current)
                    }
                }
                publishing.remove(task.id)
                pump(app, list)
                completeDrainIfIdle()
            }
        }
    }

    private fun cleanupCompletedStage(context: Context, list: MutableList<DownloadTask>, index: Int) {
        val task = list[index]
        cleanupStage(context, task)
        list[index] = task.copy(downloadId = -1, stagingName = "")
        write(context, list)
    }

    private fun cleanupStage(context: Context, task: DownloadTask) {
        if (task.stagingName.isBlank()) return
        runCatching { if (task.downloadId >= 0) manager(context)?.remove(task.downloadId) }
        runCatching { DownloadedFileStore.stagedFile(context, task.stagingName).delete() }
    }

    private fun fileName(task: DownloadTask): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA).format(Date(task.createdAt)) +
            "-${task.id}"
        return DownloadLogic.relativePath(task.name, stamp).substringAfter('/')
    }

    private fun enqueue(context: Context, task: DownloadTask, link: DownloadLinkResolver.Link): Long {
        val service = manager(context) ?: return -1
        val uri = link.uri
        val request = DownloadManager.Request(uri)
        DownloadLogic.headers(route(task), task.userAgent).forEach { (key, value) ->
            request.addRequestHeader(key, value)
        }
        if (task.provider == SourceProvider.QUARK && QuarkPersonalDrive.isMediaUri(uri)) {
            link.cookie?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        }
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, task.stagingName)
        request.setTitle(task.name)
        request.setDescription(task.stagingName)
        request.setMimeType(task.mediaType)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
        request.setAllowedOverMetered(true)
        return runCatching { service.enqueue(request) }.getOrElse {
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            service.enqueue(request)
        }
    }

    private data class Status(val code: Int, val reason: Int, val done: Long, val total: Long)

    private fun query(context: Context, ids: List<Long>): Map<Long, Status>? {
        val service = manager(context) ?: return null
        return runCatching {
            val cursor = service.query(DownloadManager.Query().setFilterById(*ids.toLongArray())) ?: return null
            cursor.use {
                val idAt = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusAt = it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val reasonAt = it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val doneAt = it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalAt = it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                buildMap {
                    while (it.moveToNext()) put(
                        it.getLong(idAt), Status(it.getInt(statusAt), it.getInt(reasonAt), it.getLong(doneAt), it.getLong(totalAt)),
                    )
                }
            }
        }.getOrNull()
    }
}
