package com.hnwd.bdplayer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class AudioHistoryItem(
    val entry: PanEntry,
    val seconds: Int,
    val completed: Boolean,
    val updatedAt: Long,
) {
    val positionText: String
        get() = if (completed) "已听完" else formatAudioTime(seconds)
}

internal fun formatAudioTime(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal class AudioHistory(private val preferences: SharedPreferences) {
    fun recent(gid: String): List<AudioHistoryItem> = runCatching {
        val array = JSONArray(preferences.getString(gid, "[]") ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            AudioHistoryItem(
                PanEntry(
                    gid, item.getString("message"), item.getString("owner"), item.getString("file"),
                    item.getString("name"), item.optString("path"), item.optString("size"), false,
                ),
                item.getInt("seconds"),
                item.getBoolean("completed"),
                item.optLong("updatedAt"),
            )
        }
    }.getOrDefault(emptyList())

    fun find(entry: PanEntry): AudioHistoryItem? = recent(entry.gid).firstOrNull {
        sameFile(it.entry, entry)
    }

    fun save(entry: PanEntry, seconds: Int, completed: Boolean) {
        val newest = AudioHistoryItem(entry, seconds, completed, System.currentTimeMillis())
        val items = listOf(newest) + recent(entry.gid).filterNot { sameFile(it.entry, entry) }
        val data = JSONArray()
        items.forEach { item ->
            data.put(JSONObject()
                .put("message", item.entry.messageId)
                .put("owner", item.entry.owner)
                .put("file", item.entry.fileId)
                .put("name", item.entry.name)
                .put("path", item.entry.path)
                .put("size", item.entry.size)
                .put("seconds", item.seconds)
                .put("completed", item.completed)
                .put("updatedAt", item.updatedAt))
        }
        preferences.edit().putString(entry.gid, data.toString()).apply()
    }

    private fun sameFile(first: PanEntry, second: PanEntry): Boolean =
        first.gid == second.gid && first.messageId == second.messageId && first.fileId == second.fileId
}
