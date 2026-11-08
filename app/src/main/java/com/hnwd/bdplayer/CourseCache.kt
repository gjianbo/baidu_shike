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
        val directoryPrefix = "${prefix}dir:${entry.gid}:${entry.messageId}:"
        val nested = preferences.all.keys.asSequence()
            .filter { it.startsWith(directoryPrefix) }
            .map { it.removePrefix(prefix).substringBeforeLast(':') }
            .distinct()
        return (nested + "roots:${entry.gid}").firstOrNull { key ->
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
                .put("directory", it.directory))
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

    private fun idOf(entry: PanEntry) = "${entry.gid}:${entry.messageId}:${entry.fileId}"
}

internal data class CachedPage(
    val entries: List<PanEntry>,
    val syncedAt: Long,
    val nextCursor: String?,
    val latestEmpty: Boolean,
)
