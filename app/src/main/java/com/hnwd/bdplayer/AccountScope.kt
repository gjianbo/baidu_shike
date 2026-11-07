package com.hnwd.bdplayer

import android.content.Context
import android.content.SharedPreferences

internal object AccountScope {
    private const val PREFS = "account_scope"
    private const val KEY_UK = "viewer_uk"
    private const val KEY_MIGRATED = "migrated_v1"
    private const val GUEST = "guest"
    private val NAMESPACED = listOf(
        "audio_history", "video_history", "favorites", "video_progress", "course_cache",
    )

    @Volatile
    private var uk: String = ""

    val current: String get() = uk

    fun prefix(account: String = current): String = "acct_${account.ifEmpty { GUEST }}__"

    fun key(raw: String, account: String = current): String = prefix(account) + raw

    fun init(context: Context) {
        val scope = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        uk = scope.getString(KEY_UK, "").orEmpty()
        if (!scope.getBoolean(KEY_MIGRATED, false)) {
            migrateLegacy(context)
            scope.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }

    fun set(context: Context, value: String): Boolean {
        val next = value.trim()
        if (next.isEmpty() || next == uk) return false
        uk = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_UK, next).apply()
        return true
    }

    private fun migrateLegacy(context: Context) {
        NAMESPACED.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filterNot { it.startsWith("acct_") }.forEach { oldKey ->
                val target = key(oldKey)
                if (!prefs.contains(target)) copyValue(editor, prefs, oldKey, target)
            }
            editor.apply()
        }
    }

    private fun copyValue(
        editor: SharedPreferences.Editor,
        prefs: SharedPreferences,
        from: String,
        to: String,
    ) {
        when (val value = prefs.all[from]) {
            is String -> editor.putString(to, value)
            is Long -> editor.putLong(to, value)
            is Int -> editor.putInt(to, value)
            is Boolean -> editor.putBoolean(to, value)
            is Float -> editor.putFloat(to, value)
        }
    }
}
