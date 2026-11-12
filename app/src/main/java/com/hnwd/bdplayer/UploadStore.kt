package com.hnwd.bdplayer

import android.content.Context
import androidx.core.content.edit

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

    private fun write(context: Context, list: List<UploadRecord>) {
        prefs(context).edit { putString(KEY, UploadRecordLogic.encode(UploadRecordLogic.trim(list))) }
    }

    fun records(context: Context): List<UploadRecord> = synchronized(lock) {
        val reconciled = UploadRecordLogic.reconcileInterrupted(
            readRaw(context), UploadCenter.state.running, System.currentTimeMillis(),
        )
        if (reconciled.changed) write(context, reconciled.records)
        reconciled.records
    }

    fun activeCount(context: Context): Int = synchronized(lock) {
        records(context).count(UploadRecordLogic::isActive)
    }

    fun begin(
        context: Context, id: Long, name: String, dir: String, size: Long, queued: Boolean = false,
    ): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        list.add(
            UploadRecord(
                id = id,
                name = name,
                dir = dir,
                sizeBytes = size,
                createdAt = System.currentTimeMillis(),
                state = if (queued) UploadRecordState.QUEUED else UploadRecordState.RUNNING,
                label = if (queued) "排队中" else "准备中…",
            ),
        )
        write(context, list)
    }

    /** 排队任务开始执行时转成 RUNNING。 */
    fun markRunning(context: Context, id: Long): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(state = UploadRecordState.RUNNING, label = "准备中…")
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
        list[index] = list[index].copy(
            state = if (success) UploadRecordState.SUCCESS else UploadRecordState.FAILED,
            finishedAt = System.currentTimeMillis(),
            percent = if (success) 100 else list[index].percent,
            label = message,
            message = message,
        )
        write(context, list)
    }

    /** 排队任务被移出队列时整条删掉，不进历史。 */
    fun remove(context: Context, id: Long): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        if (list.removeAll { it.id == id }) write(context, list)
    }

    /** 只清成功记录，失败项留着看原因；不删除网盘上已上传的文件。 */
    fun clearFinished(context: Context): Unit = synchronized(lock) {
        val list = records(context).toMutableList()
        if (list.removeAll { it.state == UploadRecordState.SUCCESS }) write(context, list)
    }
}
