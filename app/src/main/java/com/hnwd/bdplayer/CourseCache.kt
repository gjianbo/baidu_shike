package com.hnwd.bdplayer

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class CourseCache(private val preferences: SharedPreferences) {
    fun readPage(key: String, page: Int): CachedPage? = runCatching {
        val raw = preferences.getString(pageKey(key, page), null) ?: return null
        val data = JSONObject(raw)
        val array = data.getJSONArray("entries")
        CachedPage(List(array.length()) { index ->
            val item = array.getJSONObject(index)
            PanEntry(
                item.getString("gid"), item.getString("message"), item.getString("owner"),
                item.getString("file"), item.getString("name"), item.optString("path"),
                item.optString("size"), item.optBoolean("directory"),
                runCatching { EntryOrigin.valueOf(item.optString("origin", "GROUP")) }
                    .getOrDefault(EntryOrigin.GROUP),
                item.optString("md5"),
            )
        }, data.optLong("syncedAt"), data.optString("nextCursor").takeIf { data.has("nextCursor") },
            data.optBoolean("latestEmpty"),
        )
    }.getOrNull()

    fun readAll(key: String): List<PanEntry> {
        val result = mutableListOf<PanEntry>()
        val seen = HashSet<String>()
        var page = 1
        while (true) {
            val cached = readPage(key, page++) ?: break
            cached.entries.forEach { entry ->
                val id = "${entry.gid}:${entry.messageId}:${entry.fileId}"
                if (seen.add(id)) result.add(entry)
            }
        }
        return result
    }

    fun directoryContaining(entry: PanEntry): String? {
        val prefix = AccountScope.prefix()
        val candidates = if (entry.isDisk) {
            preferences.all.keys.asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBeforeLast(':') }
                .filter { it == "disk:roots" || it.startsWith("disk:dir:") }
                .distinct()
                .toList()
        } else {
            val directoryPrefix = "${prefix}dir:${entry.gid}:${entry.messageId}:"
            val nested = preferences.all.keys.asSequence()
                .filter { it.startsWith(directoryPrefix) }
                .map { it.removePrefix(prefix).substringBeforeLast(':') }
                .distinct()
            (nested + sequenceOf("roots:${entry.gid}")).toList()
        }
        return candidates.firstOrNull { key ->
            readAll(key).any { idOf(it) == idOf(entry) }
        }
    }

    private fun write(
        key: String,
        page: Int,
        entries: List<PanEntry>,
        nextCursor: String?,
        latestEmpty: Boolean = false,
    ) {
        val data = JSONArray()
        entries.forEach {
            data.put(JSONObject()
                .put("gid", it.gid)
                .put("message", it.messageId)
                .put("owner", it.owner)
                .put("file", it.fileId)
                .put("name", it.name)
                .put("path", it.path)
                .put("size", it.size)
                .put("directory", it.directory)
                .put("origin", it.origin.name)
                .put("md5", it.md5))
        }
        preferences.edit().putString(
            pageKey(key, page),
            JSONObject().put("syncedAt", System.currentTimeMillis()).put("entries", data)
                .put("nextCursor", nextCursor).put("latestEmpty", latestEmpty).toString(),
        ).apply()
    }

    fun writeIfChanged(key: String, page: Int, fresh: List<PanEntry>, nextCursor: String? = null): Boolean {
        val existing = readPage(key, page)
        write(key, page, fresh, nextCursor)
        return existing?.entries != fresh || existing.nextCursor != nextCursor
    }

    /** 从指定目录缓存中移除若干条目（删除后立即从列表消失，避免异步任务落盘延迟）。 */
    fun removeEntries(key: String, entries: List<PanEntry>) {
        if (entries.isEmpty()) return
        val ids = entries.map(::idOf).toHashSet()
        var page = 1
        while (true) {
            val cached = readPage(key, page) ?: break
            val filtered = cached.entries.filterNot { idOf(it) in ids }
            if (filtered.size != cached.entries.size) {
                write(key, page, filtered, cached.nextCursor, cached.latestEmpty)
            }
            page++
        }
    }

    /** 已缓存网盘文件的内容指纹（md5:size），用于「已转存」匹配。 */
    fun diskIndex(): Set<String> {
        val prefix = AccountScope.prefix()
        val keys = preferences.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBeforeLast(':') }
            .filter { it == "disk:roots" || it.startsWith("disk:dir:") }
            .distinct()
        val result = HashSet<String>()
        keys.forEach { key ->
            readAll(key).forEach { entry ->
                if (!entry.directory && entry.md5.isNotEmpty()) {
                    result.add("${PanApi.md5Key(entry.md5)}:${entry.size}")
                }
            }
        }
        return result
    }

    fun lastPage(key: String): Pair<Int, CachedPage>? {
        var last: Pair<Int, CachedPage>? = null
        var page = 1
        while (true) {
            val cached = readPage(key, page) ?: return last
            last = page to cached
            page++
        }
    }

    fun writeNewFirst(key: String, page: Int, fresh: List<PanEntry>, limit: Int = 200): Boolean {
        if (page != 1) return writeIfChanged(key, page, fresh)
        val old = readPage(key, page)
        val ids = fresh.map(::idOf).toHashSet()
        val entries = (fresh + old?.entries.orEmpty().filterNot { idOf(it) in ids }).take(limit)
        val latestEmpty = fresh.isEmpty()
        write(key, page, entries, nextCursor = null, latestEmpty = latestEmpty)
        return old?.entries != entries || old.latestEmpty != latestEmpty
    }

    fun isFresh(key: String, ttlMillis: Long): Boolean {
        val first = readPage(key, 1) ?: return false
        return first.syncedAt > 0 && System.currentTimeMillis() - first.syncedAt < ttlMillis
    }

    fun syncLabel(key: String): String {
        val at = readPage(key, 1)?.syncedAt ?: return ""
        if (at <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    /** 清除当前账号下所有「我的网盘」目录缓存（写操作成功后调用）。 */
    fun clearDisk() {
        val prefix = AccountScope.prefix()
        val editor = preferences.edit()
        preferences.all.keys.filter { stored ->
            if (!stored.startsWith(prefix)) {
                false
            } else {
                val key = stored.removePrefix(prefix).substringBeforeLast(':')
                key == "disk:roots" || key.startsWith("disk:dir:")
            }
        }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun clear(key: String) = clearAfter(key, 0)

    fun clearAfter(key: String, page: Int) {
        val prefix = AccountScope.key("$key:")
        val editor = preferences.edit()
        preferences.all.keys.filter { storedKey ->
            storedKey.startsWith(prefix) && (storedKey.removePrefix(prefix).toIntOrNull() ?: 0) > page
        }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun pageKey(key: String, page: Int) = AccountScope.key("$key:$page")

    private fun idOf(entry: PanEntry) =
        "${entry.origin}:${entry.sourceId}:${entry.gid}:${entry.messageId}:${entry.fileId}"
}

internal data class CachedPage(
    val entries: List<PanEntry>,
    val syncedAt: Long,
    val nextCursor: String?,
    val latestEmpty: Boolean,
)
