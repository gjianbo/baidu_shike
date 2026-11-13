package com.hnwd.bdplayer

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** 目录、音频和视频各自记忆排序。旧版共用的播放排序用作音视频各自的初始偏好。 */
internal object SortStore {
    private const val PREFS = "sort_order"
    private const val LEGACY_PREFS = "playback_order"
    private const val LIST = "list:"
    private const val PLAY = "play:"
    val playbackOrders = listOf(CourseOrder.ASCENDING, CourseOrder.DESCENDING, CourseOrder.NETWORK, CourseOrder.NEWEST)
    private val playbackKinds = listOf(MediaKind.AUDIO, MediaKind.VIDEO)

    fun list(context: Context, directory: String?): CourseOrder? =
        read(context, LIST, directory, legacy = false)

    fun setList(context: Context, directory: String?, order: CourseOrder) =
        write(context, LIST, directory, order)

    fun playback(context: Context, kind: MediaKind, directory: String?, path: String?): CourseOrder =
        (read(context, playbackScope(kind), directory, legacy = false)
            ?: read(context, playbackScope(kind), path, legacy = false)
            ?: read(context, PLAY, directory, legacy = true)
            ?: read(context, PLAY, path, legacy = true))
            ?.takeIf { it in playbackOrders } ?: CourseOrder.ASCENDING

    fun setPlayback(context: Context, kind: MediaKind, directory: String, order: CourseOrder) =
        write(context, playbackScope(kind), directory, order)

    fun backupPlayback(context: Context, account: String): JSONObject =
        encodePlaybackBackup(
            PrefIndex.snapshot(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), account,
        )

    fun restorePlayback(context: Context, backup: JSONObject, account: String) {
        val entries = playbackBackupEntries(backup, account)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            entries.forEach { (key, value) -> putString(key, value) }
        }
    }

    internal fun encodePlaybackBackup(prefs: Map<String, Any?>, account: String): JSONObject = JSONObject().apply {
        for (kind in playbackKinds) {
            val prefix = playbackScope(kind) + AccountScope.prefix(account)
            val orders = JSONObject()
            prefs.forEach { (key, raw) ->
                if (key.startsWith(prefix) && key.length > prefix.length && isPlaybackOrder(raw)) {
                    orders.put(key.removePrefix(prefix), raw)
                }
            }
            if (orders.length() > 0) put(kind.name, orders)
        }
    }

    internal fun playbackBackupEntries(backup: JSONObject, account: String): Map<String, String> = buildMap {
        for (kind in playbackKinds) {
            val orders = backup.optJSONObject(kind.name) ?: continue
            orders.keys().forEach { directory ->
                val order = orders.opt(directory) as? String
                if (directory.isNotBlank() && order != null && isPlaybackOrder(order)) {
                    put(playbackScope(kind) + AccountScope.key(directory, account), order)
                }
            }
        }
    }

    fun isPlaybackOrder(value: Any?): Boolean = playbackOrders.any { it.name == value }

    private fun playbackScope(kind: MediaKind): String = "$PLAY${kind.name}:"

    private fun read(context: Context, scope: String, directory: String?, legacy: Boolean): CourseOrder? {
        val dir = directory?.takeIf { it.isNotEmpty() } ?: return null
        var raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(scope + dir, null)
        if (raw == null && legacy) {
            raw = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(dir, null)
        }
        if (raw == null) return null
        return CourseOrder.entries.firstOrNull { it.name == raw }
    }

    private fun write(context: Context, scope: String, directory: String?, order: CourseOrder) {
        val dir = directory?.takeIf { it.isNotEmpty() } ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(scope + dir, order.name)
        }
    }
}
