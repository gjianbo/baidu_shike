package com.hnwd.bdplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** 串行准备和上传文件。队列变更在主线程，文件复制和网络请求在后台。 */
internal object UploadCenter {
    data class State(
        val name: String = "",
        val percent: Int = 0,
        val label: String = "准备中…",
        val running: Boolean = false,
        val finished: Boolean = false,
        val success: Boolean = false,
        val message: String = "",
        val queued: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val total: Int = 0,
    )

    private class Task(
        val id: Long, val source: Uri, val dir: String, val folderId: String,
        val provider: SourceProvider, val accountId: String, val attempt: Long = System.nanoTime(),
    ) {
        @Volatile var cancelled = false
        var worker: UploadWorker? = null
        var commit: UploadCommit? = null
        var percent = -1
        var label = ""
    }

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Task>()
    private val seq = AtomicLong()
    private var currentTask: Task? = null
    private var serviceReady = false

    var state = State()
        private set

    @Volatile private var liveIds = emptySet<Long>()

    fun activeIds(): Set<Long> = liveIds

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        if (state.running) listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish(next: State) {
        state = next
        listeners.forEach { runCatching { it(next) } }
    }

    fun enqueue(
        context: Context, sources: List<Uri>, dir: String,
        folderId: String, provider: SourceProvider, accountId: String,
    ) {
        if (sources.isEmpty()) return
        require(accountId.isNotBlank())
        val app = context.applicationContext
        val tasks = sources.map { uri ->
            val id = seq.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis() * 1000) }
            Task(id, uri, dir, folderId, provider, accountId)
        }
        val now = System.currentTimeMillis()
        liveIds = liveIds + tasks.map { it.id }
        queue.addAll(tasks)
        UploadStore.begin(app, tasks.map { task ->
            UploadRecord(
                id = task.id,
                name = sourceName(task.source),
                dir = dir,
                sizeBytes = -1,
                createdAt = now,
                source = UploadRecordLogic.sourceLabel(provider),
                sourceUri = retainSource(app, task.source),
                folderId = folderId,
                provider = provider,
                accountId = accountId,
            )
        })
        queued(app, tasks.size)
    }

    private fun retainSource(app: Context, uri: Uri): String = runCatching {
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri.toString()
    }.getOrDefault("")

    fun retry(context: Context, id: Long): Boolean {
        val app = context.applicationContext
        val record = UploadStore.records(app).firstOrNull {
            it.id == id && UploadRecordLogic.canRetry(it)
        } ?: return false
        val uri = record.sourceUri.toUri()
        if (app.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
            return false
        }
        // 重新执行该条任务，继续使用原网盘和目录。
        val task = Task(record.id, uri, record.dir, record.folderId, record.provider, record.accountId)
        liveIds = liveIds + task.id
        queue.addLast(task)
        UploadStore.retry(app, record)
        queued(app, 1)
        return true
    }

    private fun queued(app: Context, count: Int) {
        val batch = if (state.running) state else State(running = true)
        publish(batch.copy(total = batch.total + count, queued = queue.size))
        // 已在运行的服务可立即补位。新服务从 onStartCommand 启动队列。
        if (serviceReady) pump(app)
    }

    fun onServiceReady(context: Context) {
        serviceReady = true
        pump(context.applicationContext)
    }

    private fun pump(app: Context) {
        if (!serviceReady || currentTask != null || !state.running) return
        val task = queue.removeFirstOrNull()
        if (task == null) {
            val done = state.completed
            val failed = state.failed
            val message = when {
                done == 0 && failed == 0 -> "没有需要上传的文件"
                failed == 0 -> "已完成 $done 个文件的上传"
                else -> "上传结束：$done 个成功 · $failed 个失败"
            }
            serviceReady = false
            publish(state.copy(
                running = false, finished = true, success = failed == 0 && done > 0,
                queued = 0, message = message, label = message,
            ))
            return
        }

        currentTask = task
        val name = sourceName(task.source)
        UploadStore.markRunning(app, task.id)
        publish(state.copy(name = name, percent = 0, label = "正在准备文件…", queued = queue.size))
        AppIo.execute {
            clearStaleStaging(app)
            val staged = runCatching { copyToCache(app, task) }
            main.post {
                val file = staged.getOrNull()
                if (currentTask !== task || task.cancelled) {
                    file?.file?.delete()
                    if (currentTask === task) finish(app, task, false, "已取消上传")
                } else if (file == null) {
                    val message = staged.exceptionOrNull()?.message ?: "无法读取所选文件"
                    finish(app, task, false, "上传失败：$message")
                } else {
                    startTask(app, task, file)
                }
            }
        }
    }

    private fun startTask(app: Context, task: Task, staged: StagedFile) {
        val file = staged.file
        val name = staged.name
        UploadStore.setFileInfo(app, task.id, name, file.length())
        publish(state.copy(name = name))
        val onProgress: (Int, String) -> Unit = { percent, label ->
            if (currentTask === task && !task.cancelled && (percent != task.percent || label != task.label)) {
                task.percent = percent
                task.label = label
                UploadStore.progress(app, task.id, percent, label)
                publish(state.copy(percent = percent, label = label))
            }
        }
        val onResult: (Boolean, String) -> Unit = { ok, message ->
            file.delete()
            finish(app, task, ok, message)
        }
        runCatching {
            val session = TransferSession.capture(task.provider)
            val commit = UploadCommit { state, remoteId -> UploadStore.checkpoint(app, task.id, state, remoteId) }
            task.commit = commit
            val worker = when (task.provider) {
                SourceProvider.BAIDU -> UploadSection(app, session, task.accountId, file, name, task.dir, commit, onProgress, onResult)
                SourceProvider.QUARK -> QuarkUploadSection(app, session, task.accountId, file, name, task.folderId, commit, onProgress, onResult)
            }
            task.worker = worker
            worker.start()
        }.onFailure {
            onResult(false, "上传失败：${it.message ?: "无法启动上传"}")
        }
    }

    private fun finish(app: Context, task: Task, ok: Boolean, message: String) {
        if (currentTask !== task) return
        UploadStore.finish(app, task.id, ok, message)
        UploadStore.releaseUnusedGrants(app)
        liveIds = liveIds - task.id
        currentTask = null
        state = state.copy(
            completed = state.completed + if (ok) 1 else 0,
            failed = state.failed + if (ok) 0 else 1,
        )
        pump(app)
    }

    fun cancel(context: Context, id: Long): Boolean {
        val task = currentTask
        if (task?.id == id) {
            if (task.cancelled || task.commit?.cancel() == false) return false
            task.cancelled = true
            task.worker?.cancel()
            UploadStore.progress(context, id, state.percent, "正在取消…")
            publish(state.copy(label = "正在取消…"))
            return true
        }
        val waiting = queue.firstOrNull { it.id == id } ?: return false
        queue.remove(waiting)
        UploadStore.remove(context, id)
        liveIds = liveIds - id
        publish(state.copy(queued = queue.size, total = state.total - 1))
        return true
    }

    fun cancelCurrent(context: Context) {
        currentTask?.let { cancel(context, it.id) }
    }

    /** 停止接续任务。旧 worker 的回调只清理它自己的缓存。 */
    fun stop(context: Context, message: String) {
        serviceReady = false
        if (!state.running) return
        val tasks = queue.toList() + listOfNotNull(currentTask)
        queue.clear()
        currentTask = null
        tasks.forEach { task ->
            if (task.commit?.cancel() != false) {
                task.cancelled = true
                task.worker?.cancel()
            }
            UploadStore.finish(context, task.id, false, message)
        }
        liveIds = emptySet()
        publish(state.copy(
            running = false, finished = true, success = false, queued = 0,
            failed = state.failed + tasks.size, message = message, label = message,
        ))
    }

    private fun clearStaleStaging(app: Context) {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MS
        runCatching {
            File(app.cacheDir, "uploads").listFiles()
                ?.filter { it.lastModified() < cutoff && TransferCleanup.fileId(it.name) !in activeIds() }
                ?.forEach { it.delete() }
        }
    }

    /**
     * 「清理失败的记录」时顺带删掉失败任务留在私有缓存里的源文件副本。
     * 活跃任务（排队中 / 正在传）的缓存必须保留，否则队列后续任务会读不到文件；
     * 判定交给 [TransferCleanup.staleFailedFiles] 纯逻辑，可单测。
     */
    fun clearFailedArtifacts(context: Context, failedIds: Set<Long>): Int {
        if (failedIds.isEmpty()) return 0
        val app = context.applicationContext
        val dir = File(app.cacheDir, "uploads")
        val names = runCatching { dir.listFiles()?.map { it.name } ?: emptyList() }
            .getOrDefault(emptyList())
        val stale = TransferCleanup.staleFailedFiles(names, failedIds, activeIds())
        var deleted = 0
        stale.forEach { name -> if (File(dir, name).delete()) deleted++ }
        return deleted
    }

    private const val STALE_STAGING_MS = 60L * 60 * 1000

    private data class StagedFile(val file: File, val name: String)

    private fun sourceName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: "上传文件"

    private fun copyToCache(app: Context, task: Task): StagedFile {
        fun checkActive() {
            if (task.cancelled) throw IOException("已取消上传")
        }

        checkActive()
        val resolver = app.contentResolver
        val name = runCatching {
            resolver.query(task.source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: sourceName(task.source)
        val dir = File(app.cacheDir, "uploads").apply { mkdirs() }
        // 同一条记录可立即重试。旧回调只能清理旧尝试的文件。
        val file = File(dir, "${task.id}-${task.attempt}-${UploadLogic.cacheFileName(name)}")
        main.post {
            if (currentTask === task && !task.cancelled) {
                UploadStore.setFileInfo(app, task.id, name, -1)
                publish(state.copy(name = name))
            }
        }
        try {
            val input = resolver.openInputStream(task.source) ?: throw IOException("无法读取所选文件")
            input.use { src ->
                file.outputStream().use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = src.read(buffer)
                        if (count < 0) break
                        dst.write(buffer, 0, count)
                    }
                }
            }
            checkActive()
            return StagedFile(file, name)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
