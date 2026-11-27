package com.hnwd.bdplayer

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 上传记录的本地持久化，供「传输」页在任务结束后仍能展示历史、在进程重启后收敛孤儿记录。
 *
 * 与 [DownloadCenter] 的下载记录各存一份 SharedPreferences，互不干扰：
 * 下载的真相源是系统 DownloadManager 的 Query，上传的真相源是 [UploadCenter] 的进程级状态。
 */
internal object UploadStore {
    private const val PREF = "uploads"
    private const val KEY = "records"
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readRaw(context: Context): List<UploadRecord> =
        UploadRecordLogic.decode(prefs(context).getString(KEY, "[]"))

    private fun write(
        context: Context, list: List<UploadRecord>, durable: Boolean = false,
        previousSources: List<UploadRecord> = emptyList(),
    ) {
        val trimmed = UploadRecordLogic.trim(list)
        val candidates = if (trimmed.size == list.size) previousSources else previousSources + list
        val retained = trimmed.mapNotNull { it.collectorSource.takeIf(String::isNotBlank) }.toSet()
        val removed = candidates.filter {
            it.collectorSource.isNotBlank() && it.collectorSource !in retained
        }.distinctBy { it.collectorSource }
        val editor = prefs(context).edit().putString(KEY, UploadRecordLogic.encode(trimmed))
        if (durable || removed.isNotEmpty()) {
            val saved = editor.commit()
            if (durable) check(saved) { "无法保存上传记录" }
            if (saved) removed.forEach { record -> UploadCenter.discardCollectorSource(context, record) }
        } else {
            editor.apply()
        }
    }

    fun checkpoint(context: Context, id: Long, state: UploadCommitState, remoteFileId: String) = synchronized(lock) {
        val list = readRaw(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        check(index >= 0)
        list[index] = list[index].copy(commitState = state, remoteFileId = remoteFileId)
        write(context, list, durable = true)
    }

    fun records(context: Context): List<UploadRecord> = synchronized(lock) {
        val before = readRaw(context)
        val reconciled = UploadRecordLogic.reconcileInterrupted(
            before, UploadCenter.activeIds(), System.currentTimeMillis(),
        )
        if (reconciled.changed) write(context, reconciled.records, previousSources = before)
        reconciled.records
    }

    fun activeCount(context: Context): Int = synchronized(lock) {
        records(context).count(UploadRecordLogic::isActive)
    }

    fun begin(context: Context, queued: List<UploadRecord>) = synchronized(lock) {
        write(context, records(context) + queued, durable = queued.any { it.collectorSource.isNotBlank() })
    }

    fun retry(context: Context, id: Long, onQueued: (UploadRecord, CollectorUploadSource?) -> Boolean): Boolean = synchronized(lock) {
        val list = records(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val record = list[index]
        if (!UploadRecordLogic.canRetry(record)) return@synchronized false
        val owned = if (record.collectorSource.isNotBlank()) {
            CollectorUploadSource.restore(context, record) ?: return@synchronized false
        } else {
            val source = Uri.parse(record.sourceUri)
            if (context.contentResolver.persistedUriPermissions.none { it.uri == source && it.isReadPermission }) {
                return@synchronized false
            }
            null
        }
        val queued = record.copy(
            state = UploadRecordState.QUEUED, finishedAt = 0,
            percent = 0, message = "", label = "排队中",
        )
        // 锁内登记队列，避免并发读取把尚未登记的排队记录当作中断任务。
        if (!onQueued(queued, owned)) return@synchronized false
        list[index] = queued
        write(context, list)
        true
    }

    /** 上传文件选择器是应用唯一持有持久读取授权的入口。 */
    fun releaseUnusedGrants(context: Context) = synchronized(lock) {
        val inUse = readRaw(context).mapNotNull {
            it.sourceUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        }.toSet()
        val resolver = context.contentResolver
        resolver.persistedUriPermissions.filter { it.isReadPermission && it.uri !in inUse }.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun setFileInfo(context: Context, id: Long, name: String, size: Long) = synchronized(lock) {
        val list = records(context)
        write(context, list.map { if (it.id == id) it.copy(name = name, sizeBytes = size) else it })
    }

    /** 排队任务开始执行时转成 RUNNING。 */
    fun markRunning(context: Context, id: Long): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(state = UploadRecordState.RUNNING, label = "正在准备文件…")
        write(context, list)
    }

    fun progress(context: Context, id: Long, percent: Int, label: String): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(percent = percent, label = label)
        write(context, list)
    }

    fun finish(context: Context, id: Long, success: Boolean, message: String): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = list[index]
        list[index] = UploadRecordLogic.finish(before, success, message, System.currentTimeMillis())
        write(context, list, previousSources = listOf(before))
    }

    /** 排队任务被移出队列时整条删掉，不进历史。 */
    fun remove(context: Context, id: Long): Unit = synchronized(lock) {
        val (removed, kept) = records(context).partition { it.id == id }
        if (removed.isNotEmpty()) {
            write(context, kept, previousSources = removed)
            releaseUnusedGrants(context)
        }
    }

    /** 比较确认时的终态快照，保留已经重试过的任务。 */
    fun removeHistory(context: Context, selected: List<UploadRecord>): List<UploadRecord> = synchronized(lock) {
        val selectedById = selected.associateBy { it.id }
        val (removed, kept) = records(context).partition {
            !UploadRecordLogic.isActive(it) && selectedById[it.id] == it
        }
        if (removed.isNotEmpty()) {
            write(context, kept, previousSources = removed)
            releaseUnusedGrants(context)
        }
        removed
    }
}
