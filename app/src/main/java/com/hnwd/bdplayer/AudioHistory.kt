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
    private fun bucket(entry: PanEntry) =
        if (entry.origin == EntryOrigin.DISK) DISK_SOURCE_ID else entry.gid

    private fun storageKey(bucket: String) = AccountScope.key(bucket, account ?: AccountScope.current)

    private fun recentBucket(bucket: String, originHint: EntryOrigin? = null): List<AudioHistoryItem> = runCatching {
        val array = JSONArray(preferences.getString(storageKey(bucket), "[]") ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val origin = runCatching { EntryOrigin.valueOf(item.optString("origin", "")) }.getOrNull()
                ?: originHint ?: if (bucket == DISK_SOURCE_ID) EntryOrigin.DISK else EntryOrigin.GROUP
            AudioHistoryItem(
                PanEntry(
                    if (origin == EntryOrigin.DISK) "" else bucket,
                    item.getString("message"), item.getString("owner"), item.getString("file"),
                    item.getString("name"), item.optString("path"), item.optString("size"), false, origin,
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
            .flatMap { recentBucket(it).asSequence() }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    fun find(entry: PanEntry): AudioHistoryItem? =
        recentBucket(bucket(entry)).firstOrNull { sameFile(it.entry, entry) }

    /**
     * v0.2 未听/进行中/已完成筛选：一次性把所有账号分桶的播放记录压成一个索引。
     * key 与 MainActivity.entryKey 完全一致：origin:sourceId:gid:messageId:fileId。
     */
    fun progressIndex(): Map<String, AudioHistoryItem> = runCatching {
        val prefix = AccountScope.prefix(account ?: AccountScope.current)
        preferences.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .flatMap { bucket -> recentBucket(bucket).asSequence() }
            .associateBy { item ->
                val entry = item.entry
                "${entry.origin}:${entry.sourceId}:${entry.gid}:${entry.messageId}:${entry.fileId}"
            }
    }.getOrDefault(emptyMap())

    fun save(entry: PanEntry, seconds: Int, completed: Boolean, duration: Int = 0) {
        val newest = AudioHistoryItem(entry, seconds, completed, System.currentTimeMillis(), duration)
        val bucket = bucket(entry)
        val items = (listOf(newest) + recentBucket(bucket).filterNot { sameFile(it.entry, entry) })
            .take(MAX_ITEMS)
        val data = JSONArray()
        items.forEach { item ->
            data.put(JSONObject()
                .put("origin", item.entry.origin.name)
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
        preferences.edit().putString(storageKey(bucket), data.toString()).apply()
    }

    fun clear() {
        val editor = preferences.edit()
        val prefix = AccountScope.prefix(account ?: AccountScope.current)
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun sameFile(first: PanEntry, second: PanEntry): Boolean =
        first.origin == second.origin && first.gid == second.gid &&
            first.messageId == second.messageId && first.fileId == second.fileId

    private companion object {
        const val MAX_ITEMS = 300
    }
}
