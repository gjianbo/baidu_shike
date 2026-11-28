package com.hnwd.bdplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 分享下载在「交给上传队列」之前的状态；上传阶段由 UploadRecord 继续跟踪。 */
internal enum class CollectorTaskState { RUNNING, SUCCESS, FAILED }

/**
 * 一条分享下载任务记录。
 *
 * 分享下载先把远端视频下载到本机缓存，再交给 UploadCenter。这个模型只覆盖
 * 第一段（解析、下载、加入上传队列），让「传输」页在任务进行中就能看到它；上传阶段
 * 会以普通的 UploadRecord 出现在「上传」页签里。
 */
internal data class CollectorTask(
    val id: Long,
    val name: String,
    val url: String,
    val platform: String,
    val provider: SourceProvider,
    val accountId: String,
    val dir: String,
    val folderId: String,
    val keepLocal: Boolean,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val finishedAt: Long = 0,
    val state: CollectorTaskState = CollectorTaskState.RUNNING,
    val percent: Int = 0,
    val label: String = "准备收集视频…",
    val message: String = "",
    // null 表示旧版记录；空字符串表示用户明确未指定文件名。
    val customName: String? = null,
    val localExtension: Boolean = false,
) {
    val retryName: String
        get() = customName ?: name.takeUnless { it == "分享视频" }
            ?.replace(Regex("\\.(mp4|mov|m4v|webm)$", RegexOption.IGNORE_CASE), "").orEmpty()
    val isActive: Boolean get() = state == CollectorTaskState.RUNNING
    val platformLabel: String
        get() = when (platform) {
            "BILIBILI" -> "B站"
            "X" -> "X"
            "DOUYIN" -> "抖音"
            "XIAOHONGSHU" -> "小红书"
            "WEIBO" -> "微博"
            "TIKTOK" -> "TikTok"
            "YOUTUBE" -> "YouTube"
            else -> "视频"
        }
    val providerLabel: String
        get() = if (provider == SourceProvider.QUARK) "夸克网盘" else "百度网盘"
}

/**
 * 进程内存活标记：服务只把仍在运行的 id 登记在这里。历史读取时，不在集合里的
 * RUNNING 记录就是上次进程被杀留下的孤儿，直接收敛成失败，避免永远转圈。
 */
internal object CollectorTaskRuntime {
    private val lock = Any()
    private var live = emptySet<Long>()

    fun markLive(id: Long) = synchronized(lock) {
        live = live + id
    }

    fun markStopped(id: Long) = synchronized(lock) {
        live = live - id
    }

    fun clear() = synchronized(lock) {
        live = emptySet<Long>()
    }

    fun liveIds(): Set<Long> = synchronized(lock) { live }
}

/** 分享下载任务的本地持久化。与下载/上传记录分开存，互不影响。 */
internal object CollectorTaskStore {
    private const val PREF = "collector_tasks"
    private const val KEY = "records"
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readRaw(context: Context): List<CollectorTask> =
        CollectorTaskLogic.decode(prefs(context).getString(KEY, "[]"))

    private fun write(context: Context, list: List<CollectorTask>) {
        val trimmed = CollectorTaskLogic.trim(list)
        prefs(context).edit().putString(KEY, CollectorTaskLogic.encode(trimmed)).apply()
    }

    fun begin(context: Context, task: CollectorTask, retry: Boolean): Boolean = synchronized(lock) {
        val list = readRaw(context).toMutableList()
        val index = list.indexOfFirst { it.id == task.id }
        val queued = CollectorTaskLogic.prepareAttempt(task, list.getOrNull(index), retry)
            ?: return@synchronized false
        CollectorTaskRuntime.markLive(task.id)
        if (index >= 0) list[index] = queued else list.add(queued)
        write(context, list)
        true
    }

    fun updateName(context: Context, id: Long, name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        val list = readRaw(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        list[index] = list[index].copy(name = name, updatedAt = System.currentTimeMillis())
        write(context, list)
    }

    fun progress(context: Context, id: Long, percent: Int, label: String) = synchronized(lock) {
        val list = readRaw(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val record = list[index]
        if (!record.isActive) return@synchronized
        list[index] = record.copy(
            percent = percent.coerceIn(0, 100),
            label = label,
            updatedAt = System.currentTimeMillis(),
        )
        write(context, list)
    }

    fun finish(context: Context, id: Long, success: Boolean, message: String) = synchronized(lock) {
        CollectorTaskRuntime.markStopped(id)
        val list = readRaw(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val now = System.currentTimeMillis()
        list[index] = list[index].copy(
            state = if (success) CollectorTaskState.SUCCESS else CollectorTaskState.FAILED,
            percent = if (success) 100 else list[index].percent,
            label = message,
            message = message,
            updatedAt = now,
            finishedAt = now,
        )
        write(context, list)
    }

    fun records(context: Context): List<CollectorTask> = synchronized(lock) {
        val raw = readRaw(context)
        val live = CollectorTaskRuntime.liveIds()
        var changed = false
        val now = System.currentTimeMillis()
        val reconciled = raw.map { task ->
            if (task.isActive && task.id !in live) {
                changed = true
                task.copy(
                    state = CollectorTaskState.FAILED,
                    label = CollectorTaskLogic.INTERRUPTED,
                    message = CollectorTaskLogic.INTERRUPTED,
                    updatedAt = now,
                    finishedAt = now,
                )
            } else task
        }
        if (changed) CollectorTaskLogic.trim(reconciled).also { write(context, it) }
        else raw
    }

    fun activeCount(context: Context): Int = synchronized(lock) {
        records(context).count(CollectorTask::isActive)
    }

    /** 清理当前页签里已完成或失败的历史；返回实际移除条数。 */
    fun clearFinished(context: Context, failed: Boolean): Int = synchronized(lock) {
        val target = if (failed) CollectorTaskState.FAILED else CollectorTaskState.SUCCESS
        val list = readRaw(context)
        val kept = list.filterNot { it.state == target }
        if (kept.size != list.size) write(context, kept)
        list.size - kept.size
    }
}

/** 纯逻辑：分享任务编解码、裁剪和中断文案，可在 JVM 单测里钉死。 */
internal object CollectorTaskLogic {
    const val INTERRUPTED = "收集中断（应用已关闭）"

    fun prepareAttempt(task: CollectorTask, previous: CollectorTask?, retry: Boolean): CollectorTask? {
        if (!retry) return task.takeIf { previous == null }
        if (previous?.state != CollectorTaskState.FAILED) return null
        return task.copy(createdAt = previous.createdAt)
    }

    fun trim(records: List<CollectorTask>): List<CollectorTask> {
        val active = records.filter(CollectorTask::isActive)
        val finished = records.filterNot(CollectorTask::isActive).sortedByDescending { it.finishedAt }
        return (active + finished.take((60 - active.size).coerceAtLeast(0)))
            .sortedByDescending { it.createdAt }
    }

    fun encode(records: List<CollectorTask>): String =
        JSONArray().apply { records.forEach { put(toJson(it)) } }.toString()

    fun decode(raw: String?): List<CollectorTask> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun toJson(task: CollectorTask) = JSONObject()
        .put("id", task.id)
        .put("name", task.name)
        .put("url", task.url)
        .put("platform", task.platform)
        .put("provider", task.provider.name)
        .put("account", task.accountId)
        .put("dir", task.dir)
        .put("folderId", task.folderId)
        .put("keep", task.keepLocal)
        .put("created", task.createdAt)
        .put("updated", task.updatedAt)
        .put("finished", task.finishedAt)
        .put("state", task.state.name)
        .put("percent", task.percent)
        .put("label", task.label)
        .put("message", task.message)
        .put("customName", task.customName)
        .put("localExtension", task.localExtension)

    private fun fromJson(item: JSONObject) = CollectorTask(
        id = item.getLong("id"),
        name = item.optString("name").ifBlank { "分享视频" },
        url = item.optString("url"),
        platform = item.optString("platform"),
        provider = runCatching { SourceProvider.valueOf(item.optString("provider")) }
            .getOrDefault(SourceProvider.BAIDU),
        accountId = item.optString("account"),
        dir = item.optString("dir").ifBlank { "/" },
        folderId = item.optString("folderId"),
        keepLocal = item.optBoolean("keep", false),
        createdAt = item.optLong("created", 0),
        updatedAt = item.optLong("updated", item.optLong("created", 0)),
        finishedAt = item.optLong("finished", 0),
        state = runCatching { CollectorTaskState.valueOf(item.optString("state", "RUNNING")) }
            .getOrDefault(CollectorTaskState.RUNNING),
        percent = item.optInt("percent", 0),
        label = item.optString("label").ifBlank { "准备收集视频…" },
        message = item.optString("message"),
        customName = item.opt("customName") as? String,
        localExtension = item.optBoolean("localExtension"),
    )
}
