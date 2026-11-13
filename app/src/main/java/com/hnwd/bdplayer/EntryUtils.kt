package com.hnwd.bdplayer

import java.text.Collator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 条目相关的纯函数：不依赖 Activity、不碰 UI、只吃 PanEntry 列表。
 * 全部从 MainActivity 原样搬出，函数体与语义未改。
 */

internal fun entryKey(entry: PanEntry): String =
    "${entry.origin}:${entry.sourceId}:${entry.gid}:${entry.messageId}:${entry.fileId}"

internal fun favoriteTag(entry: PanEntry) = "favorite:${entryKey(entry)}"

internal fun sameEntry(first: PanEntry, second: PanEntry): Boolean =
    first.origin == second.origin && first.provider == second.provider && first.fileId == second.fileId &&
        (first.isDisk || (first.gid == second.gid && first.messageId == second.messageId))

internal fun sameMediaKind(entry: PanEntry, entries: List<PanEntry>): List<PanEntry> {
    val kind = PanApi.kindOf(entry)
    return entries.filter { PanApi.kindOf(it) == kind }
}

/** 同目录、同类型的相邻条目；找不到时退化为只有自己，避免上一节/下一节越界。 */
internal fun courseSiblings(entry: PanEntry, entries: List<PanEntry>): List<PanEntry> {
    val path = PanApi.readablePath(entry.path)
    if (entry.provider == SourceProvider.QUARK && !entry.isDisk) {
        return sameMediaKind(entry, entries).filter { it.gid == entry.gid }
            .ifEmpty { listOf(entry) }
    }
    if (!path.startsWith('/')) return listOf(entry)
    val directory = path.substringBeforeLast('/')
    return sameMediaKind(entry, entries).filter {
        it.origin == entry.origin && it.gid == entry.gid && PanApi.readablePath(it.path).let { path ->
            path.startsWith('/') && path.substringBeforeLast('/') == directory
        }
    }.takeIf { list -> list.any { sameEntry(it, entry) } } ?: listOf(entry)
}

internal fun orderCourses(entries: List<PanEntry>, order: CourseOrder): List<PanEntry> = when (order) {
    CourseOrder.ASCENDING -> entries.sortedWith { first, second -> compareCourseNames(first.name, second.name) }
    CourseOrder.DESCENDING -> entries.sortedWith { first, second -> compareCourseNames(second.name, first.name) }
    CourseOrder.NETWORK -> entries
    CourseOrder.NEWEST -> entries.sortedByDescending { it.modifiedAt }
}

private val courseNameParts = Regex("[0-9]+|[^0-9]+")
private val courseCollator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)

/** 数字段按数值比较（第2讲 < 第10讲），其余按中文排序规则比较。 */
private fun compareCourseNames(first: String, second: String): Int {
    val left = courseNameParts.findAll(first).iterator()
    val right = courseNameParts.findAll(second).iterator()
    while (left.hasNext() && right.hasNext()) {
        val a = left.next().value
        val b = right.next().value
        val aNumber = a[0] in '0'..'9'
        val bNumber = b[0] in '0'..'9'
        val order = when {
            aNumber && bNumber -> {
                val aDigits = a.trimStart('0')
                val bDigits = b.trimStart('0')
                val lengthOrder = aDigits.length.compareTo(bDigits.length)
                if (lengthOrder == 0) aDigits.compareTo(bDigits) else lengthOrder
            }
            aNumber != bNumber -> if (aNumber) -1 else 1
            else -> courseCollator.compare(a, b)
        }
        if (order != 0) return order
    }
    return left.hasNext().compareTo(right.hasNext())
}

/** 目标目录是否落在被操作条目自身（或它的子目录）里——移动/复制到自身会失败，提前拦住。 */
internal fun isSelfOrDescendant(dest: String, sources: List<PanEntry>): Boolean {
    val target = dest.trimEnd('/').ifEmpty { "/" }
    return sources.any { source ->
        source.directory && (target == source.path.trimEnd('/') || target.startsWith(source.path.trimEnd('/') + "/"))
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatOf(entry: PanEntry): String =
    if (entry.directory) "目录" else entry.name.substringAfterLast('.', "文件").uppercase()

internal fun mediaKindLabel(kind: MediaKind, directory: Boolean, format: String): String = when (kind) {
    MediaKind.AUDIO -> "音频"
    MediaKind.VIDEO -> "视频"
    MediaKind.IMAGE -> "图片"
    MediaKind.PDF, MediaKind.DOCUMENT -> "文档"
    else -> if (directory) "目录" else format.lowercase()
}

// ===================== 状态保存：条目列表 <-> JSON =====================

internal fun encodeEntries(entries: List<PanEntry>): String = JSONArray().apply {
    entries.forEach { entry ->
        put(JSONObject()
            .put("gid", entry.gid)
            .put("message", entry.messageId)
            .put("owner", entry.owner)
            .put("file", entry.fileId)
            .put("name", entry.name)
            .put("path", entry.path)
            .put("size", entry.size)
            .put("directory", entry.directory)
            .put("origin", entry.origin.name)
            .put("md5", entry.md5)
            .put("modifiedAt", entry.modifiedAt))
    }
}.toString()

/**
 * 解码条目列表。
 *
 * 容错分两层：外层挡住「根本不是 JSON」，内层逐条挡「这一条缺字段」。
 * 内层这层是必要的：收藏/进度是整份覆盖写的，若一条脏数据就让整表返回空，
 * 用户会看到「收藏全没了」——丢一条远好过丢一整个列表。
 */
internal fun decodeEntries(raw: String?): List<PanEntry> = runCatching {
    val array = JSONArray(raw ?: return emptyList())
    (0 until array.length()).mapNotNull { index ->
        runCatching { decodeEntry(array.getJSONObject(index)) }.getOrNull()
    }
}.getOrDefault(emptyList())

private fun decodeEntry(item: JSONObject): PanEntry = PanEntry(
    item.getString("gid"), item.getString("message"), item.getString("owner"),
    item.getString("file"), item.getString("name"), item.optString("path"),
    item.optString("size"), item.optBoolean("directory"),
    runCatching { EntryOrigin.valueOf(item.optString("origin", "GROUP")) }
        .getOrDefault(EntryOrigin.GROUP),
    item.optString("md5"),
    modifiedAt = item.optLong("modifiedAt", 0),
)
