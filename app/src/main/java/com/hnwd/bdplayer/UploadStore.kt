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

    private fun write(context: Context, list: List<UploadRecord>, durable: Boolean = false) {
        val editor = prefs(context).edit().putString(KEY, UploadRecordLogic.encode(UploadRecordLogic.trim(list)))
        if (durable) check(editor.commit()) { "无法保存网盘提交状态" } else editor.apply()
    }

    fun checkpoint(context: Context, id: Long, state: UploadCommitState, remoteFileId: String) = synchronized(lock) {
        val list = readRaw(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        check(index >= 0)
        list[index] = list[index].copy(commitState = state, remoteFileId = remoteFileId)
        write(context, list, durable = true)
    }

    fun records(context: Context): List<UploadRecord> = synchronized(lock) {
        val reconciled = UploadRecordLogic.reconcileInterrupted(
            readRaw(context), UploadCenter.activeIds(), System.currentTimeMillis(),
        )
        if (reconciled.changed) write(context, reconciled.records)
        reconciled.records
    }

    fun activeCount(context: Context): Int = synchronized(lock) {
        records(context).count(UploadRecordLogic::isActive)
    }

    fun begin(context: Context, queued: List<UploadRecord>) = synchronized(lock) {
        write(context, records(context) + queued)
    }

    fun retry(context: Context, record: UploadRecord) = synchronized(lock) {
        val queued = record.copy(
            state = UploadRecordState.QUEUED, finishedAt = 0,
            percent = 0, message = "", label = "排队中",
        )
        write(context, records(context).map { if (it.id == record.id) queued else it })
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
        list[index] = UploadRecordLogic.finish(list[index], success, message, System.currentTimeMillis())
        write(context, list)
    }

    /** 排队任务被移出队列时整条删掉，不进历史。 */
    fun remove(context: Context, id: Long): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        if (list.removeAll { it.id == id }) {
            write(context, list)
            releaseUnusedGrants(context)
        }
    }

    /** 只清成功记录，失败项留着看原因；不删除网盘上已上传的文件。 */
    fun clearFinished(context: Context): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        if (list.removeAll { it.state == UploadRecordState.SUCCESS }) write(context, list)
    }

    /** 「清理失败的记录」：只删 FAILED，成功记录与活跃任务不动；返回清掉的条数。 */
    fun clearFailed(context: Context): Int = synchronized(lock) {
        val list = records(context).toMutableList()
        val before = list.size
        if (list.removeAll { it.state == UploadRecordState.FAILED }) {
            write(context, list)
            releaseUnusedGrants(context)
        }
        before - list.size
    }
}
