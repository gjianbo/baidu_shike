package com.hnwd.bdplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纯本机 JSON 快照。只备份当前账号的群组来源、收藏、播放进度和偏好；
 * 不含 Cookie、播放直链和媒体文件。
 */
internal object BackupStore {
    private const val SCHEMA = 1
    private const val APP = "com.hnwd.bdplayer"
    private const val DIR = "backups"
    private const val MAX = 3
    const val PREFS_SETTINGS = "app_settings"

    data class Snapshot(
        val file: File,
        val time: Long,
        val kind: String,
        val payload: JSONObject,
        val summary: String,
    )

    private fun prefs(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    fun ensureInitial(context: Context) {
        if (list(context).isEmpty()) capture(context, "auto")
    }

    fun capture(context: Context, kind: String): File? = runCatching {
        val payload = build(context).put("kind", kind)
        val file = File(dir(context), "snap-${System.currentTimeMillis()}-$kind.json")
        file.writeText(payload.toString(2))
        trim(context)
        file
    }.getOrNull()

    fun list(context: Context): List<Snapshot> {
        val files = dir(context).listFiles { file -> file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val payload = JSONObject(file.readText())
                val time = payload.optLong("createdMillis", file.lastModified())
                Snapshot(
                    file = file,
                    time = time,
                    kind = payload.optString("kind", if (file.name.contains("-manual")) "manual" else "auto"),
                    payload = payload,
                    summary = summary(payload),
                )
            }.getOrNull()
        }.sortedByDescending { it.time }.take(MAX)
    }

    fun count(context: Context): Int = list(context).size

    private fun trim(context: Context) {
        val files = dir(context).listFiles { file -> file.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX).forEach { it.delete() }
    }

    fun summary(payload: JSONObject): String {
        val data = payload.optJSONObject("data") ?: JSONObject()
        val favorites = runCatching { JSONArray(data.optString("favorites", "[]")).length() }.getOrDefault(0)
        val history = historyCount(data.optJSONObject("audioHistory")) + historyCount(data.optJSONObject("videoHistory"))
        val groups = data.optJSONArray("sources")?.length() ?: 0
        val appearance = data.optJSONObject("prefs")?.optString("appearance", ThemeStore.LIGHT) ?: ThemeStore.LIGHT
        return "$groups 个群组 · $favorites 个收藏 · $history 条进度 · ${ThemeStore.label(appearance)}"
    }

    fun timeLabel(time: Long): String {
        val date = Date(time)
        val now = Date()
        val format = SimpleDateFormat("HH:mm", Locale.CHINA)
        val hm = format.format(date)
        val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
        if (day.format(date) == day.format(now)) return hm
        val yesterday = Date(now.time - 24 * 60 * 60 * 1000L)
        if (day.format(date) == day.format(yesterday)) return "昨天 $hm"
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(date)
    }

    private fun historyCount(history: JSONObject?): Int {
        if (history == null) return 0
        var total = 0
        val keys = history.keys()
        while (keys.hasNext()) {
            val raw = history.optString(keys.next(), "[]")
            total += runCatching { JSONArray(raw).length() }.getOrDefault(0)
        }
        return total
    }

    fun build(context: Context): JSONObject {
        val scope = prefs(context, "account_scope")
        val favorites = prefs(context, "favorites")
        val audio = prefs(context, "audio_history")
        val video = prefs(context, "video_history")
        val progress = prefs(context, "video_progress")
        val settings = prefs(context, PREFS_SETTINGS)
        val sources = JSONArray()
        runCatching { JSONArray(scope.getString(AccountScope.key("share_sources"), "[]") ?: "[]") }.getOrDefault(JSONArray()).let { stored ->
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                sources.put(
                    JSONObject()
                        .put("id", item.optString("id"))
                        .put("name", item.optString("name")),
                )
            }
        }
        val data = JSONObject()
            .put("account", AccountScope.current)
            .put("sources", sources)
            .put("groupId", scope.getString(AccountScope.key("group_id"), "").orEmpty())
            .put("favorites", favorites.getString(AccountScope.key("favorites"), "[]") ?: "[]")
            .put("audioHistory", stringMap(audio))
            .put("videoHistory", stringMap(video))
            .put("videoProgress", longMap(progress))
            .put("prefs", JSONObject()
                .put("appearance", settings.getString("appearance", ThemeStore.LIGHT))
                .put("course_order", settings.getString("course_order", "ASCENDING")))
        return JSONObject()
            .put("schemaVersion", SCHEMA)
            .put("app", APP)
            .put("createdAt", System.currentTimeMillis())
            .put("createdMillis", System.currentTimeMillis())
            .put("data", data)
    }

    private fun stringMap(prefs: android.content.SharedPreferences): JSONObject {
        val result = JSONObject()
        val prefix = AccountScope.prefix()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is String) {
                result.put(key.removePrefix(prefix), value)
            }
        }
        return result
    }

    private fun longMap(prefs: android.content.SharedPreferences): JSONObject {
        val result = JSONObject()
        val prefix = AccountScope.prefix()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is Long) {
                result.put(key.removePrefix(prefix), value)
            }
        }
        return result
    }

    fun valid(payload: JSONObject?): Boolean =
        payload != null &&
            payload.optInt("schemaVersion", -1) == SCHEMA &&
            payload.optString("app", APP).let { it.isEmpty() || it == APP } &&
            payload.optJSONObject("data") != null

    fun restore(context: Context, payload: JSONObject): Boolean {
        if (!valid(payload)) return false
        val data = payload.optJSONObject("data") ?: return false
        val scope = prefs(context, "account_scope")
        val sources = JSONArray()
        data.optJSONArray("sources")?.let { stored ->
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                if (item.optString("id").isEmpty()) continue
                sources.put(
                    JSONObject()
                        .put("id", item.optString("id"))
                        .put("name", item.optString("name"))
                        .put("session", ""),
                )
            }
        }
        scope.edit()
            .putString(AccountScope.key("share_sources"), sources.toString())
            .putString(AccountScope.key("group_id"), data.optString("groupId"))
            .apply()

        prefs(context, "favorites").edit()
            .putString(AccountScope.key("favorites"), data.optString("favorites", "[]"))
            .apply()

        mergeHistory(prefs(context, "audio_history"), data.optJSONObject("audioHistory"))
        mergeHistory(prefs(context, "video_history"), data.optJSONObject("videoHistory"))
        mergeProgress(prefs(context, "video_progress"), data.optJSONObject("videoProgress"))

        val settings = data.optJSONObject("prefs") ?: JSONObject()
        prefs(context, PREFS_SETTINGS).edit()
            .putString("appearance", settings.optString("appearance", ThemeStore.LIGHT))
            .putString("course_order", settings.optString("course_order", "ASCENDING"))
            .apply()
        return true
    }

    private fun mergeHistory(prefs: android.content.SharedPreferences, backup: JSONObject?) {
        if (backup == null) return
        val editor = prefs.edit()
        val keys = backup.keys()
        while (keys.hasNext()) {
            val suffix = keys.next()
            val target = AccountScope.key(suffix)
            val current = runCatching { JSONArray(prefs.getString(target, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val incoming = runCatching { JSONArray(backup.optString(suffix, "[]")) }.getOrDefault(JSONArray())
            val merged = LinkedHashMap<String, JSONObject>()
            for (index in 0 until current.length()) {
                val item = current.optJSONObject(index) ?: continue
                merged[item.optString("file", "$index")] = item
            }
            for (index in 0 until incoming.length()) {
                val item = incoming.optJSONObject(index) ?: continue
                val key = item.optString("file", "backup-$index")
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = item
                } else {
                    val seconds = maxOf(existing.optInt("seconds"), item.optInt("seconds"))
                    val duration = maxOf(existing.optInt("duration"), item.optInt("duration"))
                    val completed = existing.optBoolean("completed") || item.optBoolean("completed")
                    val updatedAt = maxOf(existing.optLong("updatedAt"), item.optLong("updatedAt"))
                    merged[key] = (if (updatedAt == item.optLong("updatedAt")) item else existing).apply {
                        put("seconds", seconds)
                        put("completed", completed)
                        put("duration", duration)
                        put("updatedAt", updatedAt)
                    }
                }
            }
            val array = JSONArray()
            merged.values.sortedByDescending { it.optLong("updatedAt") }.forEach { array.put(it) }
            editor.putString(target, array.toString())
        }
        editor.apply()
    }

    private fun mergeProgress(prefs: android.content.SharedPreferences, backup: JSONObject?) {
        if (backup == null) return
        val editor = prefs.edit()
        val keys = backup.keys()
        while (keys.hasNext()) {
            val suffix = keys.next()
            val target = AccountScope.key(suffix)
            val current = prefs.getLong(target, 0L)
            val incoming = backup.optLong(suffix, 0L)
            editor.putLong(target, maxOf(current, incoming))
        }
        editor.apply()
    }

    fun read(context: Context, uri: Uri): JSONObject? = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return null
        JSONObject(text)
    }.getOrNull()
}
