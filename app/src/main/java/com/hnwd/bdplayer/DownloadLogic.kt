package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject

/** 下载取址链路：群组共享 / 我的网盘 / 夸克。 */
internal enum class DownloadRoute { BAIDU_GROUP, BAIDU_DISK, QUARK }

internal enum class DownloadState { QUEUED, RUNNING, SUCCESS, FAILED }

/** 下载记录保存来源标识，排到执行时才请求短期直链。 */
internal data class DownloadTask(
    val id: Long,
    val name: String,
    val sizeBytes: Long = 0,
    val provider: SourceProvider = SourceProvider.BAIDU,
    val sourceLabel: String = "",
    val createdAt: Long = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val downloadId: Long = -1,
    val url: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
    val finishedAt: Long = 0,
    val error: String = "",
    val mediaType: String = "application/octet-stream",
    val entry: PanEntry? = null,
    val viewer: String = "",
    val linkAttempts: Int = 0,
    val needsVerification: Boolean = false,
    val stagingName: String = "",
    val outputUri: String = "",
    val publishing: Boolean = false,
)

/**
 * 下载页的纯逻辑层：分派、文件名、请求头、进度与记录编解码。
 * 只依赖 org.json，可在 JVM 单测里跑；Android 的 DownloadManager 状态映射不在这里。
 */
internal object DownloadLogic {
    /** 系统「下载」目录下的子目录，与备份用的「拾课备份」分开。 */
    const val FOLDER = "拾课"
    const val MAX_PARALLEL = 3
    const val QUARK_LIMIT_BYTES = 50L * 1024 * 1024
    const val BAIDU_REFERER = "https://pan.baidu.com/disk/main"
    const val QUARK_REFERER = "https://pan.quark.cn/"

    fun canDownload(entry: PanEntry): Boolean =
        !entry.directory && entry.fileId.isNotBlank() && entry.name.isNotBlank()

    fun routeOf(entry: PanEntry): DownloadRoute = when {
        entry.provider == SourceProvider.QUARK -> DownloadRoute.QUARK
        entry.isDisk -> DownloadRoute.BAIDU_DISK
        else -> DownloadRoute.BAIDU_GROUP
    }

    fun sourceLabel(route: DownloadRoute): String =
        if (route == DownloadRoute.QUARK) "夸克网盘" else "百度网盘"

    /** 去掉路径分隔符、Windows 非法字符与控制字符，避免写文件时逃出目标目录。 */
    fun safeName(name: String): String {
        val cleaned = buildString {
            name.forEach { ch ->
                val illegal = ch in "/\\:*?\"<>|" || ch.isISOControl()
                append(if (illegal) '_' else ch)
            }
        }.trim().trimEnd('.')
        val safe = cleaned.takeIf { it.isNotEmpty() && it.any { ch -> ch != '_' } } ?: "下载文件"
        return if (safe.length <= 120) safe else {
            val dot = safe.lastIndexOf('.')
            val extLen = if (dot > 0) safe.length - dot else 0
            if (dot > 0 && extLen < 120) {
                // 按扩展名长度倒推前缀长度，保证结果恒 <= 120；
                // 旧写法固定 take(100) 会在「扩展名靠后 + 名字很长」时仍超出上限。
                val keep = 120 - extLen
                safe.take(keep) + safe.substring(dot)
            } else {
                // 扩展名本身已经超长，不可能既保留它又满足上限，直接整串截断。
                safe.take(120)
            }
        }
    }

    fun relativePath(name: String, stamp: String): String = "$FOLDER/${stamp}_${safeName(name)}"

    /** 百度 dlink 的签名绑定生成它的 WebView UA；夸克直链同样带 UA 与 Referer。 */
    fun headers(route: DownloadRoute, userAgent: String): List<Pair<String, String>> {
        val referer = if (route == DownloadRoute.QUARK) QUARK_REFERER else BAIDU_REFERER
        return listOfNotNull(
            "User-Agent".takeIf { userAgent.isNotBlank() }?.let { it to userAgent },
            "Referer" to referer,
        )
    }

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v", "mov", "mkv", "avi" -> "video/*"
        "mp3", "m4a", "aac", "wav", "flac" -> "audio/*"
        "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
        "pdf" -> "application/pdf"
        "zip", "rar", "7z" -> "application/zip"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** 总长未知返回 -1，交给 UI 走不确定进度。 */
    fun progress(bytesDone: Long, bytesTotal: Long): Int = when {
        bytesTotal <= 0 -> -1
        bytesDone >= bytesTotal -> 100
        else -> ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 99)
    }

    /** 汇总卡 / 任务行的进度条状态，UI 只负责画，不做判断。 */
    data class ProgressDisplay(val visible: Boolean, val indeterminate: Boolean, val percent: Int)

    /**
     * 0 个任务时进度条完全不出现——「0 个下载中」配一条会转的进度条是误导。
     * 只有真正在下载（running > 0）且总长未知时，才允许不确定动画。
     */
    fun summaryProgress(active: Int, running: Int, done: Long, total: Long): ProgressDisplay {
        if (active == 0) return ProgressDisplay(visible = false, indeterminate = false, percent = 0)
        val percent = progress(done, total)
        return ProgressDisplay(
            visible = true,
            indeterminate = running > 0 && percent < 0,
            percent = percent.coerceAtLeast(0),
        )
    }

    /** 排队任务还没传字节，用静止空轨道；失败项不画进度条。 */
    fun taskProgress(state: DownloadState, done: Long, total: Long): ProgressDisplay = when (state) {
        DownloadState.FAILED -> ProgressDisplay(visible = false, indeterminate = false, percent = 0)
        DownloadState.QUEUED -> ProgressDisplay(visible = true, indeterminate = false, percent = 0)
        else -> {
            val percent = progress(done, total)
            ProgressDisplay(
                visible = true,
                indeterminate = percent < 0,
                percent = percent.coerceAtLeast(0),
            )
        }
    }

    fun hasAccountBinding(task: DownloadTask): Boolean =
        task.viewer.isNotBlank() && task.entry?.provider == task.provider

    fun isActive(task: DownloadTask): Boolean =
        task.state == DownloadState.QUEUED || task.state == DownloadState.RUNNING

    fun canRefreshLink(reason: Int): Boolean = when (reason) {
        401, 403, 404, 410, 412, 1008 -> true // 1008: DownloadManager.ERROR_CANNOT_RESUME
        else -> false
    }

    fun requeue(task: DownloadTask): DownloadTask = task.copy(
        state = DownloadState.QUEUED, downloadId = -1, url = "", bytesDone = 0,
        bytesTotal = -1, finishedAt = 0, error = "", needsVerification = false,
        stagingName = "", outputUri = "", publishing = false,
    )

    fun acceptsResolution(task: DownloadTask, id: Long): Boolean =
        task.id == id && task.state == DownloadState.QUEUED && task.downloadId < 0 &&
            !task.publishing && hasAccountBinding(task)

    fun publicationFailed(task: DownloadTask, stageLost: Boolean, now: Long): DownloadTask {
        val retryTask = if (stageLost) requeue(task) else task
        return retryTask.copy(
            state = DownloadState.FAILED,
            finishedAt = now,
            error = if (stageLost) "下载暂存文件已丢失或不完整，请重试下载" else "文件已下载，保存失败，请重试",
        )
    }

    const val CANCELLED_ERROR = "已取消下载"
    const val LOST_RECORD_ERROR = "下载记录已丢失，请回到文件列表重新下载"

    /** 取消：保留一条可重试的 FAILED 记录，而不是把任务整条删掉。 */
    fun cancelledTask(task: DownloadTask, now: Long): DownloadTask = task.copy(
        state = DownloadState.FAILED,
        downloadId = -1,
        finishedAt = now,
        error = CANCELLED_ERROR,
    )

    /** 系统下载记录被外部删除：收敛成 FAILED，避免永远卡在 RUNNING 占并发槽与角标。 */
    fun lostRecordTask(task: DownloadTask, now: Long): DownloadTask = task.copy(
        state = DownloadState.FAILED,
        downloadId = -1,
        finishedAt = now,
        error = LOST_RECORD_ERROR,
    )

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(java.util.Locale.US, value, units[unit])
    }

    fun speedText(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 0) "" else formatBytes(bytesPerSecond.toLong()) + "/s"

    fun etaText(remainingBytes: Long, bytesPerSecond: Double): String {
        if (remainingBytes <= 0 || bytesPerSecond <= 0) return ""
        val seconds = (remainingBytes / bytesPerSecond).toLong().coerceAtLeast(1)
        return when {
            seconds < 60 -> "约 $seconds 秒"
            seconds < 3600 -> "约 ${seconds / 60} 分钟"
            else -> "约 ${seconds / 3600} 小时"
        }
    }

    /** 系统下载器失败原因 → 能动手重试的文案；夸克非会员大文件单独提示。 */
    fun failureText(reason: Int, route: DownloadRoute): String = when (reason) {
        403, 412 -> "网盘拒绝了下载（HTTP $reason），链接可能已过期"
        404, 410 -> "文件地址已失效，请重试"
        in 400..499 -> "下载失败（HTTP $reason）"
        else -> if (route == DownloadRoute.QUARK)
            "夸克下载失败，非会员单个文件上限约 50MB，或链接已过期"
        else "下载失败，请检查网络后重试"
    }

    fun routeOfLabel(route: DownloadRoute): String = when (route) {
        DownloadRoute.QUARK -> "夸克网盘"
        DownloadRoute.BAIDU_DISK -> "我的网盘"
        DownloadRoute.BAIDU_GROUP -> "共享文件库"
    }

    // ===================== 本地记录编解码 =====================

    fun encodeTasks(tasks: List<DownloadTask>): String =
        JSONArray().apply { tasks.forEach { put(toJson(it)) } }.toString()

    /** 单条脏数据只丢一条，不能让整份记录变空（与收藏/进度的解码口径一致）。 */
    fun decodeTasks(raw: String?): List<DownloadTask> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun toJson(task: DownloadTask) = JSONObject()
        .put("id", task.id)
        .put("name", task.name)
        .put("size", task.sizeBytes)
        .put("provider", task.provider.name)
        .put("label", task.sourceLabel)
        .put("created", task.createdAt)
        .put("state", task.state.name)
        .put("downloadId", task.downloadId)
        .put("url", task.url)
        .put("ua", task.userAgent)
        .put("referer", task.referer)
        .put("done", task.bytesDone)
        .put("total", task.bytesTotal)
        .put("finished", task.finishedAt)
        .put("error", task.error)
        .put("mime", task.mediaType)
        .put("entry", task.entry?.let { encodeEntries(listOf(it)) })
        .put("viewer", task.viewer)
        .put("linkAttempts", task.linkAttempts)
        .put("verification", task.needsVerification)
        .put("staging", task.stagingName)
        .put("output", task.outputUri)
        .put("publishing", task.publishing)

    private fun fromJson(item: JSONObject) = DownloadTask(
        id = item.getLong("id"),
        name = item.getString("name"),
        sizeBytes = item.optLong("size", 0),
        provider = runCatching { SourceProvider.valueOf(item.optString("provider", "BAIDU")) }
            .getOrDefault(SourceProvider.BAIDU),
        sourceLabel = item.optString("label"),
        createdAt = item.optLong("created", 0),
        state = runCatching { DownloadState.valueOf(item.optString("state", "QUEUED")) }
            .getOrDefault(DownloadState.QUEUED),
        downloadId = item.optLong("downloadId", -1),
        url = item.optString("url"),
        userAgent = item.optString("ua"),
        referer = item.optString("referer"),
        bytesDone = item.optLong("done", 0),
        bytesTotal = item.optLong("total", -1),
        finishedAt = item.optLong("finished", 0),
        error = item.optString("error"),
        mediaType = item.optString("mime").ifBlank { "application/octet-stream" },
        entry = decodeEntries(item.optString("entry")).firstOrNull(),
        viewer = item.optString("viewer"),
        linkAttempts = item.optInt("linkAttempts"),
        needsVerification = item.optBoolean("verification"),
        stagingName = item.optString("staging"),
        outputUri = item.optString("output"),
        publishing = item.optBoolean("publishing"),
    )
}
