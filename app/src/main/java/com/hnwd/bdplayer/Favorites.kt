package com.hnwd.bdplayer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal class Favorites(private val preferences: SharedPreferences) {
    fun all(): List<PanEntry> = runCatching {
        val array = JSONArray(preferences.getString(storageKey(), "[]") ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            PanEntry(
                item.getString("gid"), item.getString("message"), item.getString("owner"),
                item.getString("file"), item.getString("name"), item.optString("path"),
                item.optString("size"), item.optBoolean("directory"),
                runCatching { EntryOrigin.valueOf(item.optString("origin", "GROUP")) }
                    .getOrDefault(EntryOrigin.GROUP),
                item.optString("md5"),
            )
        }
    }.getOrDefault(emptyList())

    fun has(entry: PanEntry): Boolean = all().any { same(it, entry) }

    fun toggle(entry: PanEntry): Boolean {
        val items = all().toMutableList()
        val index = items.indexOfFirst { same(it, entry) }
        if (index >= 0) {
            items.removeAt(index)
        } else {
            items.add(0, entry)
        }
        save(items)
        return index < 0
    }

    /** v0.2 批量收藏：返回新增数量（已存在的不重复添加）。 */
    fun addAll(entries: List<PanEntry>): Int {
        val items = all().toMutableList()
        var added = 0
        entries.forEach { entry ->
            if (items.none { same(it, entry) }) {
                items.add(0, entry)
                added++
            }
        }
        if (added > 0) save(items)
        return added
    }

    /** v0.2 批量取消收藏：返回移除数量。 */
    fun removeAll(entries: List<PanEntry>): Int {
        val items = all().toMutableList()
        var removed = 0
        entries.forEach { entry ->
            if (items.removeAll { same(it, entry) }) removed++
        }
        if (removed > 0) save(items)
        return removed
    }

    private fun save(items: List<PanEntry>) {
        val data = JSONArray()
        items.forEach {
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
        preferences.edit().putString(storageKey(), data.toString()).apply()
    }

    private fun storageKey() = AccountScope.key("favorites")

    private fun same(first: PanEntry, second: PanEntry): Boolean =
        first.origin == second.origin && first.gid == second.gid &&
            first.messageId == second.messageId && first.fileId == second.fileId
}
