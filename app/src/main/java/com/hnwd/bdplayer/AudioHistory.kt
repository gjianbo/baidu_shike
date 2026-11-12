package com.hnwd.bdplayer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit
import java.util.UUID
import java.util.WeakHashMap

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
    fun scoped(account: String) = AudioHistory(preferences, account)

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
                    if (origin == EntryOrigin.DISK) item.optString("gid") else bucket,
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
        return PrefIndex.keys(preferences).asSequence()
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
        PrefIndex.keys(preferences).asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .flatMap { bucket -> recentBucket(bucket).asSequence() }
            .associateBy { item ->
                val entry = item.entry
                "${entry.origin}:${entry.sourceId}:${entry.gid}:${entry.messageId}:${entry.fileId}"
            }
    }.getOrDefault(emptyMap())

    fun begin(entry: PanEntry): Recording = synchronized(recordings) {
        val scope = account ?: AccountScope.current
        Recording(scoped(scope), entry, scope).also { recordings[it] = Unit }
    }

    private fun save(entry: PanEntry, seconds: Int, completed: Boolean, duration: Int) {
        val newest = AudioHistoryItem(entry, seconds, completed, System.currentTimeMillis(), duration)
        val bucket = bucket(entry)
        val items = (listOf(newest) + recentBucket(bucket).filterNot { sameFile(it.entry, entry) })
            .take(MAX_ITEMS)
        writeBucket(bucket, items)
    }

    private fun writeBucket(bucket: String, items: List<AudioHistoryItem>) {
        if (items.isEmpty()) {
            preferences.edit { remove(storageKey(bucket)) }
            return
        }
        val data = JSONArray()
        items.forEach { item ->
            data.put(JSONObject()
                .put("origin", item.entry.origin.name)
                .put("gid", item.entry.gid)
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
        preferences.edit { putString(storageKey(bucket), data.toString()) }
    }

    fun remove(entry: PanEntry, videoProgress: SharedPreferences? = null) = synchronized(recordings) {
        val scope = account ?: AccountScope.current
        val history = scoped(scope)
        recordings.keys.removeAll { it.matches(preferences, scope) && sameFile(it.entry, entry) }
        val bucket = bucket(entry)
        val items = history.recentBucket(bucket)
        val remaining = items.filterNot { sameFile(it.entry, entry) }
        if (remaining.size != items.size) history.writeBucket(bucket, remaining)
        videoProgress?.edit {
            remove(AccountScope.key(entryKey(entry), scope))
            if (!entry.isDisk) remove(legacyVideoKey(entry, scope))
        }
        Unit
    }

    fun clear(videoProgress: SharedPreferences? = null) = synchronized(recordings) {
        val scope = account ?: AccountScope.current
        recordings.keys.removeAll { it.matches(preferences, scope) }
        val prefix = AccountScope.prefix(scope)
        preferences.edit {
            PrefIndex.keys(preferences).filter { it.startsWith(prefix) }.forEach { remove(it) }
        }
        videoProgress?.edit {
            PrefIndex.keys(videoProgress).filter { it.startsWith(prefix) }.forEach { remove(it) }
        }
        Unit
    }

    private fun sameFile(first: PanEntry, second: PanEntry): Boolean =
        first.origin == second.origin && first.gid == second.gid &&
            first.messageId == second.messageId && first.fileId == second.fileId

    internal class Recording internal constructor(
        private val history: AudioHistory,
        val entry: PanEntry,
        private val account: String,
    ) {
        val id: String = UUID.randomUUID().toString()
        private val progressKey = AccountScope.key(entryKey(entry), account)
        private val legacyProgressKey = legacyVideoKey(entry, account)

        fun matches(preferences: SharedPreferences, account: String): Boolean =
            history.preferences === preferences && this.account == account

        fun save(seconds: Int, completed: Boolean, duration: Int): Unit = synchronized(recordings) {
            if (this in recordings) history.save(entry, seconds, completed, duration)
        }

        fun saveVideo(progress: SharedPreferences, positionMs: Long, completed: Boolean, duration: Int) =
            synchronized(recordings) {
                if (this !in recordings) return@synchronized
                progress.edit {
                    if (completed) {
                        remove(progressKey)
                        if (!entry.isDisk) remove(legacyProgressKey)
                    } else putLong(progressKey, positionMs)
                }
                history.save(entry, (positionMs / 1000).toInt(), completed, duration)
            }

        fun videoPosition(progress: SharedPreferences): Long = synchronized(recordings) {
            if (this !in recordings) return@synchronized 0L
            val saved = progress.getLong(progressKey, 0L)
            if (saved > 0 || entry.isDisk) return@synchronized saved
            val legacy = progress.getLong(legacyProgressKey, 0L)
            if (legacy > 0) {
                progress.edit { putLong(progressKey, legacy).remove(legacyProgressKey) }
            }
            legacy
        }
    }

    companion object {
        private const val MAX_ITEMS = 300
        // 已删除的播放会话不再写入，主动重新打开文件会创建新的会话。
        private val recordings = WeakHashMap<Recording, Unit>()

        fun canResumeRecording(id: String?): Boolean = synchronized(recordings) {
            recordings.keys.any { it.id == id }
        }

        private fun legacyVideoKey(entry: PanEntry, account: String) =
            AccountScope.key("${entry.gid}:${entry.messageId}:${entry.fileId}", account)
    }
}
