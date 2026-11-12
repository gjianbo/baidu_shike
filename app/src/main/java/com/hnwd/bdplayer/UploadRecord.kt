package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject

/** QUEUED 是已复制到私有缓存、等待前台服务串行上传的任务。 */
internal enum class UploadRecordState { QUEUED, RUNNING, SUCCESS, FAILED }

/**
 * 一条上传任务记录。与 [DownloadTask] 平行，供「传输」页统一展示。
 *
 * 失败/取消的临时文件已被删除，不提供页内重试，只能回文件列表重新选一次。
 */
internal data class UploadRecord(
    val id: Long,
    val name: String,
    val dir: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val finishedAt: Long = 0,
    val state: UploadRecordState = UploadRecordState.QUEUED,
    val percent: Int = 0,
    val label: String = "排队中",
    val message: String = "",
    /** 上传目标网盘；旧记录没有该字段时按百度兼容。 */
    val source: String = UploadRecordLogic.SOURCE_BAIDU,
)

/** 纯逻辑：编解码与列表收敛，可在 JVM 单测里钉死口径。 */
internal object UploadRecordLogic {
    const val MAX_RECORDS = 100
    const val SOURCE_BAIDU = "百度网盘 · 上传"
    const val SOURCE_QUARK = "夸克网盘 · 上传"
    const val INTERRUPTED = "上传中断（应用已关闭）"

    fun sourceLabel(provider: SourceProvider): String =
        if (provider == SourceProvider.QUARK) SOURCE_QUARK else SOURCE_BAIDU

    fun isActive(record: UploadRecord): Boolean =
        record.state == UploadRecordState.QUEUED || record.state == UploadRecordState.RUNNING

    fun isQueued(record: UploadRecord): Boolean = record.state == UploadRecordState.QUEUED

    /** 进行中记录一律保留，历史按完成时间裁剪到 [MAX_RECORDS] 条。 */
    fun trim(records: List<UploadRecord>): List<UploadRecord> {
        val active = records.filter(::isActive)
        val finished = records.filterNot(::isActive).sortedByDescending { it.finishedAt }
        return (active + finished.take((MAX_RECORDS - active.size).coerceAtLeast(0)))
            .sortedByDescending { it.createdAt }
    }

    data class Reconciled(val records: List<UploadRecord>, val changed: Boolean)

    /**
     * 进程被杀后不会有人给 QUEUED / RUNNING 收尾；下次读历史时按 [UploadCenter.activeIds()]
     * 的**真实存活 id** 把孤儿记录标成失败。用全局 running 判断不够精确：新批次正在跑时，
     * 上一次遗留的孤儿会一直显示成「排队中」，角标也消不掉。
     */
    fun reconcileInterrupted(
        records: List<UploadRecord>, liveIds: Set<Long>, now: Long,
    ): Reconciled {
        var changed = false
        val out = records.map { record ->
            if (!isActive(record) || record.id in liveIds) return@map record
            changed = true
            record.copy(
                state = UploadRecordState.FAILED,
                finishedAt = now,
                message = INTERRUPTED,
                label = INTERRUPTED,
            )
        }
        return Reconciled(out, changed)
    }

    fun encode(records: List<UploadRecord>): String =
        JSONArray().apply { records.forEach { put(toJson(it)) } }.toString()

    /** 单条脏数据只丢一条，不能让整份历史变空（与下载记录同一口径）。 */
    fun decode(raw: String?): List<UploadRecord> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun toJson(record: UploadRecord) = JSONObject()
        .put("id", record.id)
        .put("name", record.name)
        .put("dir", record.dir)
        .put("size", record.sizeBytes)
        .put("created", record.createdAt)
        .put("finished", record.finishedAt)
        .put("state", record.state.name)
        .put("percent", record.percent)
        .put("label", record.label)
        .put("message", record.message)
        .put("source", record.source)

    private fun fromJson(item: JSONObject) = UploadRecord(
        id = item.getLong("id"),
        name = item.getString("name"),
        dir = item.optString("dir"),
        sizeBytes = item.optLong("size", 0),
        createdAt = item.optLong("created", 0),
        finishedAt = item.optLong("finished", 0),
        state = runCatching { UploadRecordState.valueOf(item.optString("state", "QUEUED")) }
            .getOrDefault(UploadRecordState.QUEUED),
        percent = item.optInt("percent", 0),
        label = item.optString("label").ifBlank { "排队中" },
        message = item.optString("message"),
        source = item.optString("source").ifBlank { SOURCE_BAIDU },
    )
}
