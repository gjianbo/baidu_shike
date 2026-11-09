package com.hnwd.bdplayer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
    private const val SCHEMA = 2
    private const val APP = "com.hnwd.bdplayer"
    private const val DIR = "backups"
    private const val MAX = 3
    const val PREFS_SETTINGS = "app_settings"
    private const val DOWNLOAD_FOLDER = "拾课备份"
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER"
    val restoreFolder: Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:$relativePath",
    )

    fun saveToDownloads(context: Context, payload: JSONObject): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA).format(Date())
        val name = "拾课备份-$stamp.json"
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )) { "无法创建备份文件" }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")) { "无法写入备份文件" }
                .bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString(2)) }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "无法完成备份" }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        val savedName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: name
        return "下载/$DOWNLOAD_FOLDER/$savedName"
    }

    data class Snapshot(
        val time: Long,
        val kind: String,
        val payload: JSONObject,
        val summary: String,
    )

    private fun prefs(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun list(context: Context): List<Snapshot> {
        val files = File(context.filesDir, DIR).listFiles { file -> file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val payload = JSONObject(file.readText())
                val time = payload.optLong("createdMillis", file.lastModified())
                Snapshot(
                    time = time,
                    kind = payload.optString("kind", if (file.name.contains("-manual")) "manual" else "auto"),
                    payload = payload,
                    summary = summary(payload),
                )
            }.getOrNull()
        }.sortedByDescending { it.time }.take(MAX)
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
            .put("playbackOrder", stringMap(prefs(context, "playback_order")))
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

    fun valid(payload: JSONObject?): Boolean {
        if (payload == null || payload.optInt("schemaVersion", -1) !in 1..SCHEMA) return false
        if (payload.optString("app", APP).let { it.isNotEmpty() && it != APP }) return false
        val data = payload.optJSONObject("data") ?: return false
        val sources = data.optJSONArray("sources") ?: return false
        val groupId = data.opt("groupId") as? String ?: return false
        val favoritesJson = data.opt("favorites") as? String ?: return false
        val favorites = runCatching { JSONArray(favoritesJson) }.getOrNull() ?: return false

        val sourceIds = mutableSetOf<String>()
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: return false
            val id = source.opt("id") as? String ?: return false
            if (!isShareSourceId(id) || source.opt("name") !is String) return false
            if (!sourceIds.add(id)) return false
        }
        // Older exports can have a current group without a saved source entry.
        if (groupId.isNotEmpty() && !isShareSourceId(groupId)) return false
        val favoriteFields = listOf("gid", "message", "owner", "file", "name")
        return (0 until favorites.length()).all { index ->
            val favorite = favorites.optJSONObject(index)
            favorite != null && favoriteFields.all { favorite.opt(it) is String }
        }
    }

    fun backupAccount(payload: JSONObject): String? =
        payload.optJSONObject("data")?.optString("account")?.takeIf { it.matches(Regex("[0-9]{1,24}")) }

    fun restoreAccount(payload: JSONObject): String? {
        val backup = backupAccount(payload)
        val current = AccountScope.current.ifBlank { null }
        if (backup != null && current != null && backup != current) return null
        return backup ?: current
    }

    fun restore(context: Context, payload: JSONObject): Boolean {
        if (!valid(payload)) return false
        val data = payload.optJSONObject("data") ?: return false
        val account = restoreAccount(payload) ?: return false
        val scope = prefs(context, "account_scope")
        val stored = data.getJSONArray("sources")
        val sources = JSONArray()
        for (index in 0 until stored.length()) {
            val item = stored.getJSONObject(index)
            sources.put(
                JSONObject()
                    .put("id", item.getString("id"))
                    .put("name", item.getString("name"))
                    .put("session", ""),
            )
        }
        scope.edit()
            .putString(AccountScope.key("share_sources", account), sources.toString())
            .putString(AccountScope.key("group_id", account), data.getString("groupId"))
            .apply()

        prefs(context, "favorites").edit()
            .putString(AccountScope.key("favorites", account), data.getString("favorites"))
            .apply()

        mergeHistory(prefs(context, "audio_history"), data.optJSONObject("audioHistory"), account)
        mergeHistory(prefs(context, "video_history"), data.optJSONObject("videoHistory"), account)
        mergeProgress(prefs(context, "video_progress"), data.optJSONObject("videoProgress"), account)

        data.optJSONObject("playbackOrder")?.let { orders ->
            val editor = prefs(context, "playback_order").edit()
            orders.keys().forEach { key ->
                val order = orders.optString(key)
                if (order == "ASCENDING" || order == "DESCENDING") {
                    editor.putString(AccountScope.key(key, account), order)
                }
            }
            editor.apply()
        }

        data.optJSONObject("prefs")?.let { settings ->
            val editor = prefs(context, PREFS_SETTINGS).edit()
            (settings.opt("appearance") as? String)?.let { editor.putString("appearance", it) }
            (settings.opt("course_order") as? String)?.let { editor.putString("course_order", it) }
            editor.apply()
        }
        return true
    }

    private fun mergeHistory(prefs: android.content.SharedPreferences, backup: JSONObject?, account: String) {
        if (backup == null) return
        val editor = prefs.edit()
        val keys = backup.keys()
        while (keys.hasNext()) {
            val suffix = keys.next()
            val target = AccountScope.key(suffix, account)
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

    private fun mergeProgress(prefs: android.content.SharedPreferences, backup: JSONObject?, account: String) {
        if (backup == null) return
        val editor = prefs.edit()
        val keys = backup.keys()
        while (keys.hasNext()) {
            val suffix = keys.next()
            val target = AccountScope.key(suffix, account)
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
