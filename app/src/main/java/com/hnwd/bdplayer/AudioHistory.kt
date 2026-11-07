package com.hnwd.bdplayer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class AudioHistoryItem(
    val entry: PanEntry,
    val seconds: Int,
    val completed: Boolean,
    val updatedAt: Long,
    val duration: Int = 0,
) {
    val positionText: String
        get() = if (completed) "已听完" else formatAudioTime(seconds)

    val progressPercent: Int
        get() = if (duration > 0) (seconds * 100 / duration).coerceIn(0, 100) else 0

    val remainingText: String
        get() = if (duration > seconds) formatAudioTime(duration - seconds) else ""
}

internal fun formatAudioTime(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

internal class AudioHistory(private val preferences: SharedPreferences, private val account: String? = null) {
    private fun storageKey(gid: String) = AccountScope.key(gid, account ?: AccountScope.current)

    fun recent(gid: String): List<AudioHistoryItem> = runCatching {
        val array = JSONArray(preferences.getString(storageKey(gid), "[]") ?: "[]")
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
                item.optInt("duration", 0),
            )
        }
    }.getOrDefault(emptyList())

    fun recentAcrossGroups(): List<AudioHistoryItem> {
        val prefix = AccountScope.prefix(account ?: AccountScope.current)
        return preferences.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .flatMap { recent(it).asSequence() }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    fun find(entry: PanEntry): AudioHistoryItem? = recent(entry.gid).firstOrNull {
        sameFile(it.entry, entry)
    }

    fun save(entry: PanEntry, seconds: Int, completed: Boolean, duration: Int = 0) {
        val newest = AudioHistoryItem(entry, seconds, completed, System.currentTimeMillis(), duration)
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
                .put("duration", item.duration)
                .put("updatedAt", item.updatedAt))
        }
        preferences.edit().putString(storageKey(entry.gid), data.toString()).apply()
    }

    fun clear() {
        val editor = preferences.edit()
        val prefix = AccountScope.prefix(account ?: AccountScope.current)
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun sameFile(first: PanEntry, second: PanEntry): Boolean =
        first.gid == second.gid && first.messageId == second.messageId && first.fileId == second.fileId
}
