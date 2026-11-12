package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 上传任务的进程级队列：一次可选多个文件，全部先复制进私有缓存，再**串行**上传。
 *
 * - 先复制：系统文件选择器给的是临时 Uri 授权，Activity 结束或长时间后台后可能失效；
 *   私有 File 不受影响，也让「排队中」的任务有稳定的数据源。
 * - 串行而非并发：避免多个 4MB 分片同时抢带宽、耗光 bdstoken / 直链的时效；
 *   传输页把其余任务显示为「排队中」，用户可单独移出队列。
 *
 * [UploadForegroundService] 只负责前台保活与通知，队列本体在这个 object 里，
 * 因此 Activity 销毁、传输页开关都不影响上传。
 */
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

    private data class Queued(
        val id: Long, val file: File, val name: String, val dir: String, val size: Long,
    )

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Queued>()
    private val seq = AtomicLong()

    @Volatile private var current: State = State()
    val state: State get() = current

    @Volatile private var section: UploadSection? = null

    @Volatile private var currentTask: Queued? = null

    @Volatile private var cancelled = false

    @Volatile private var lastProgressPercent = -1

    @Volatile private var lastProgressLabel = ""

    private fun nextId(): Long = System.currentTimeMillis() * 1000 + seq.incrementAndGet() % 1000

    /** 只在任务进行中补发状态，避免界面重建时把上一次的「完成」再弹一遍。 */
    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        val snapshot = current
        if (snapshot.running) main.post { runCatching { listener(snapshot) } }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish(change: State.() -> State) {
        val next = synchronized(lock) {
            current = current.change()
            current
        }
        main.post { listeners.forEach { runCatching { it(next) } } }
    }

    /** 追加一批文件到队列；已有任务在跑时只排队，不打断。 */
    fun enqueue(context: Context, sources: List<Uri>, dir: String) {
        if (sources.isEmpty()) return
        val app = context.applicationContext
        val alreadyRunning = synchronized(lock) { current.running }
        synchronized(lock) {
            if (alreadyRunning) {
                current = current.copy(total = current.total + sources.size)
            } else {
                cancelled = false
                current = State(running = true, label = "准备中…", total = sources.size)
            }
        }
        publish { this }
        val staged = ArrayList<Queued>(sources.size)
        AppIo.execute {
            try {
                val keep = synchronized(lock) {
                    (queue.map { it.file } + listOfNotNull(currentTask?.file)).toSet()
                }
                clearStaleStaging(app, keep)
                sources.forEachIndexed { index, uri ->
                    if (cancelled) return@forEachIndexed
                    val (file, name) = copyToCache(app, uri, index)
                    staged.add(Queued(nextId(), file, name, dir, file.length()))
                }
                synchronized(lock) {
                    staged.forEach {
                        UploadStore.begin(app, it.id, it.name, it.dir, it.size, queued = true)
                        queue.addLast(it)
                    }
                    current = current.copy(queued = queue.size, label = "排队中")
                }
                publish { this }
                pump(app)
            } catch (error: Throwable) {
                android.util.Log.w("ShikeUpload", "stage upload batch failed", error)
                staged.forEach { it.file.delete() }
                synchronized(lock) {
                    queue.forEach { it.file.delete() }
                    queue.clear()
                    currentTask = null
                    current = current.copy(
                        running = false, finished = true, success = false, queued = 0,
                        message = "上传失败：${error.message ?: "无法读取所选文件"}",
                    )
                }
                publish { this }
            }
        }
    }

    /** 取下一个排队任务；队列空则收尾整批。 */
    private fun pump(app: Context) {
        val next = synchronized(lock) {
            if (currentTask != null) {
                null
            } else {
                val q = queue.removeFirstOrNull()
                if (q == null) {
                    val summary = current
                    val message = batchMessage(summary.completed, summary.failed)
                    current = summary.copy(
                        running = false,
                        finished = true,
                        queued = 0,
                        success = summary.failed == 0 && summary.completed > 0,
                        message = message,
                        label = message,
                    )
                    null
                } else {
                    currentTask = q
                    current = current.copy(
                        name = q.name, percent = 0, label = "准备中…", queued = queue.size,
                    )
                    q
                }
            }
        }
        publish { this }
        if (next == null) return
        UploadStore.markRunning(app, next.id)
        startTask(app, next)
    }

    private fun batchMessage(done: Int, failed: Int): String = when {
        done == 0 && failed == 0 -> "没有需要上传的文件"
        failed == 0 -> "已完成 $done 个文件的上传"
        else -> "上传结束：$done 个成功 · $failed 个失败"
    }

    private fun startTask(app: Context, task: Queued) {
        lastProgressPercent = -1
        lastProgressLabel = ""
        val worker = UploadSection(
            app, task.file, task.name, task.dir,
            onProgress = { percent, label -> onTaskProgress(app, task.id, percent, label) },
            onResult = { ok, message ->
                UploadStore.finish(app, task.id, ok, message)
                task.file.delete()
                synchronized(lock) {
                    section = null
                    currentTask = null
                    current = current.copy(
                        completed = current.completed + if (ok) 1 else 0,
                        failed = current.failed + if (ok) 0 else 1,
                        percent = if (ok) 100 else current.percent,
                    )
                }
                pump(app)
            },
        )
        section = worker
        worker.start()
    }

    /** 每 64KB 都会回调一次进度；只在百分比或阶段变化时写盘并通知，避免刷爆主线程。 */
    private fun onTaskProgress(app: Context, id: Long, percent: Int, label: String) {
        if (percent == lastProgressPercent && label == lastProgressLabel) return
        lastProgressPercent = percent
        lastProgressLabel = label
        UploadStore.progress(app, id, percent, label)
        publish { copy(percent = percent, label = label) }
    }

    /** 取消单条：排队中的直接移出并删缓存，正在传的走 UploadSection.cancel()。 */
    fun cancel(context: Context, id: Long) {
        val app = context.applicationContext
        val isCurrent = synchronized(lock) { id == currentTask?.id }
        if (isCurrent) {
            cancelled = true
            section?.cancel()
            return
        }
        val removed = synchronized(lock) {
            val item = queue.firstOrNull { it.id == id }
            if (item != null) queue.remove(item)
            item
        } ?: return
        removed.file.delete()
        UploadStore.remove(app, id)
        val remaining = synchronized(lock) { queue.size }
        publish { copy(queued = remaining, total = (total - 1).coerceAtLeast(0)) }
    }

    /** 通知栏「取消」作用于当前正在上传的任务。 */
    fun cancelCurrent(context: Context) {
        val id = synchronized(lock) { currentTask?.id } ?: return
        cancel(context, id)
    }

    /** 上次异常退出可能留下临时文件；新批次开始时只清不在队列里的旧文件。 */
    private fun clearStaleStaging(app: Context, keep: Set<File>) {
        runCatching {
            File(app.cacheDir, "uploads").listFiles()
                ?.filter { it !in keep }
                ?.forEach { it.delete() }
        }
    }

    private fun copyToCache(app: Context, source: Uri, index: Int): Pair<File, String> {
        var name = "上传文件"
        app.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameAt >= 0) {
                        cursor.getString(nameAt)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                }
            }
        val dir = File(app.cacheDir, "uploads").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}-$index-${UploadLogic.cacheFileName(name)}")
        val input = app.contentResolver.openInputStream(source) ?: throw IOException("无法读取所选文件")
        input.use { src ->
            file.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) throw IOException("已取消")
                    val count = src.read(buffer)
                    if (count < 0) break
                    dst.write(buffer, 0, count)
                }
            }
        }
        return file to name
    }
}
