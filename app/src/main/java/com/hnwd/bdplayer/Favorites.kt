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
                .put("directory", it.directory))
        }
        preferences.edit().putString(storageKey(), data.toString()).apply()
        return index < 0
    }

    private fun storageKey() = AccountScope.key("favorites")

    private fun same(first: PanEntry, second: PanEntry): Boolean =
        first.gid == second.gid && first.messageId == second.messageId && first.fileId == second.fileId
}
