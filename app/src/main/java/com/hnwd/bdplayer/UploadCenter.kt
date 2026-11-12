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
        /** 夸克上传要的是目录 fid，不是路径；入队时快照，避免排队期间切换网盘来源。 */
        val folderId: String,
        val provider: SourceProvider,
    )

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Queued>()
    private val seq = AtomicLong()

    @Volatile private var current: State = State()
    val state: State get() = current

    @Volatile private var section: UploadWorker? = null

    @Volatile private var currentTask: Queued? = null

    @Volatile private var lastProgressPercent = -1

    @Volatile private var lastProgressLabel = ""

    private fun nextId(): Long = System.currentTimeMillis() * 1000 + seq.incrementAndGet() % 1000

    /** 真实存活的任务 id（排队中 + 正在传），供 [UploadStore] 精确收敛孤儿记录。 */
    fun activeIds(): Set<Long> = synchronized(lock) {
        (queue.map { it.id } + listOfNotNull(currentTask?.id)).toSet()
    }

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

    /**
     * 追加一批文件到队列；已有任务在跑时只排队，不打断。
     * provider / folderId 在入队时快照：用户随后切换网盘来源也不影响排队中的任务。
     */
    fun enqueue(
        context: Context, sources: List<Uri>, dir: String,
        folderId: String, provider: SourceProvider,
    ) {
        if (sources.isEmpty()) return
        val app = context.applicationContext
        // 一次加锁完成「判断 + 改状态」，避免与 pump() 之间插入导致批次统计错乱。
        synchronized(lock) {
            current = if (current.running) {
                current.copy(total = current.total + sources.size)
            } else {
                State(running = true, label = "准备中…", total = sources.size)
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
                sources.forEach { uri ->
                    // 用任务 id 作缓存文件名前缀：同一毫秒、同名、多个批次都不会互相覆盖。
                    val id = nextId()
                    val (file, name) = copyToCache(app, uri, id)
                    staged.add(
                        Queued(id, file, name, dir, file.length(), folderId, provider),
                    )
                }
                synchronized(lock) {
                    staged.forEach {
                        UploadStore.begin(
                            app, it.id, it.name, it.dir, it.size,
                            source = UploadRecordLogic.sourceLabel(provider), queued = true,
                        )
                        queue.addLast(it)
                    }
                    current = current.copy(queued = queue.size, label = "排队中")
                }
                publish { this }
                pump(app)
            } catch (error: Throwable) {
                // 只回滚本次 staging 的临时文件：队列里可能还有别的任务、甚至正在传的当前任务，
                // 绝不能连它们的缓存一起删掉（那会让正在进行的上传读到已删文件）。
                android.util.Log.w("ShikeUpload", "stage upload batch failed", error)
                staged.forEach { it.file.delete() }
                synchronized(lock) {
                    val stillBusy = currentTask != null || queue.isNotEmpty()
                    current = if (stillBusy) {
                        // 已有任务继续跑，只把本批失败的数量从 total 里扣掉。
                        current.copy(total = (current.total - sources.size).coerceAtLeast(0))
                    } else {
                        current.copy(
                            running = false, finished = true, success = false, queued = 0,
                            message = "上传失败：${error.message ?: "无法读取所选文件"}",
                        )
                    }
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
        val onProgress: (Int, String) -> Unit =
            { percent, label -> onTaskProgress(app, task.id, percent, label) }
        val onResult: (Boolean, String) -> Unit = { ok, message ->
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
        }
        // 队列只依赖 UploadWorker 契约；百度传目录路径，夸克传目录 fid。
        val worker: UploadWorker = when (task.provider) {
            SourceProvider.BAIDU -> UploadSection(
                app, task.file, task.name, task.dir, onProgress, onResult,
            )
            SourceProvider.QUARK -> QuarkUploadSection(
                app, task.file, task.name, task.folderId, onProgress, onResult,
            )
        }
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
            // 只取消当前 worker；不再设全局标志，否则会污染之后追加的批次。
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

    /**
     * 上次异常退出可能留下临时文件；只清「不在队列里且足够旧」的文件。
     * 并发 enqueue 时另一个批次可能正在写缓存，无条件全删会删掉它还没入队的文件；
     * 失败批次自己的临时文件在 catch 里已显式删除，不需要靠这里兜底。
     */
    private fun clearStaleStaging(app: Context, keep: Set<File>) {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MS
        runCatching {
            File(app.cacheDir, "uploads").listFiles()
                ?.filter { it !in keep && it.lastModified() < cutoff }
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

    private fun copyToCache(app: Context, source: Uri, unique: Long): Pair<File, String> {
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
        val file = File(dir, "$unique-${UploadLogic.cacheFileName(name)}")
        val input = app.contentResolver.openInputStream(source) ?: throw IOException("无法读取所选文件")
        input.use { src ->
            file.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = src.read(buffer)
                    if (count < 0) break
                    dst.write(buffer, 0, count)
                }
            }
        }
        return file to name
    }
}
