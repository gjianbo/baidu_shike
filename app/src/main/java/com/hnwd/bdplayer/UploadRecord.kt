package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject

/** QUEUED 是等待串行准备和上传的任务。 */
internal enum class UploadRecordState { QUEUED, RUNNING, SUCCESS, FAILED }

/**
 * 一条上传任务记录。与 [DownloadTask] 平行，供「传输」页统一展示。
 *
 * 持久化授权允许中断的任务重新读取源文件。
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
    val sourceUri: String = "",
    val folderId: String = "",
    val provider: SourceProvider = SourceProvider.BAIDU,
    val accountId: String = "",
    val commitState: UploadCommitState = UploadCommitState.TRANSFERRING,
    val remoteFileId: String = "",
)

/** 纯逻辑：编解码与列表收敛，可在 JVM 单测里钉死口径。 */
internal object UploadRecordLogic {
    const val MAX_RECORDS = 100
    const val SOURCE_BAIDU = "百度网盘 · 上传"
    const val SOURCE_QUARK = "夸克网盘 · 上传"
    const val INTERRUPTED = "上传中断（应用已关闭）"
    const val UNCONFIRMED = "网盘提交结果待确认，请先检查目标目录，避免重复上传"

    fun canRetry(record: UploadRecord): Boolean =
        record.state == UploadRecordState.FAILED && record.sourceUri.isNotBlank() &&
            record.accountId.isNotBlank() && record.commitState == UploadCommitState.TRANSFERRING

    fun finish(record: UploadRecord, success: Boolean, message: String, now: Long): UploadRecord {
        val accepted = success || record.commitState == UploadCommitState.ACCEPTED
        val detail = when {
            accepted && !success -> "已上传：${record.name}"
            !accepted && record.commitState == UploadCommitState.SUBMITTING -> UNCONFIRMED
            else -> message
        }
        return record.copy(
            state = if (accepted) UploadRecordState.SUCCESS else UploadRecordState.FAILED,
            finishedAt = now, percent = if (accepted) 100 else record.percent,
            message = detail, label = detail,
            sourceUri = if (accepted || detail == "已取消上传") "" else record.sourceUri,
        )
    }

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
            if (record.id in liveIds || !isActive(record) &&
                !(record.state == UploadRecordState.FAILED && record.commitState == UploadCommitState.ACCEPTED)
            ) return@map record
            changed = true
            finish(record, false, INTERRUPTED, now)
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
        .put("uri", record.sourceUri)
        .put("folderId", record.folderId)
        .put("provider", record.provider.name)
        .put("account", record.accountId)
        .put("commit", record.commitState.name)
        .put("remoteFileId", record.remoteFileId)

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
        sourceUri = item.optString("uri"),
        accountId = item.optString("account"),
        commitState = UploadCommitState.entries.firstOrNull { it.name == item.optString("commit") }
            ?: UploadCommitState.TRANSFERRING,
        remoteFileId = item.optString("remoteFileId"),
        folderId = item.optString("folderId"),
        provider = runCatching { SourceProvider.valueOf(item.optString("provider")) }
            .getOrDefault(SourceProvider.BAIDU),
    )
}
