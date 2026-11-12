package com.hnwd.bdplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** 账号命名空间前缀：迁移时用它区分「已是新键」与「历史旧键」。 */
internal const val ACCOUNT_KEY_PREFIX = "acct_"

/**
 * 迁移计划：(旧键, 目标新键)。已带 [ACCOUNT_KEY_PREFIX] 的键跳过；
 * 其余键全部列出——调用方按「新键不存在才复制」写值，并**无条件删旧键**。
 *
 * 只做纯计算，便于 JVM 单测覆盖历史脏键（含冒号、含中文、超长键）。
 */
internal fun legacyKeyMoves(allKeys: Collection<String>, prefix: String): List<Pair<String, String>> =
    allKeys.filterNot { it.startsWith(ACCOUNT_KEY_PREFIX) }.map { it to prefix + it }

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

    fun prefix(account: String = current): String = "$ACCOUNT_KEY_PREFIX${account.ifEmpty { GUEST }}__"

    fun key(raw: String, account: String = current): String = prefix(account) + raw

    fun init(context: Context) {
        val scope = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        uk = scope.getString(KEY_UK, "").orEmpty()
        if (!scope.getBoolean(KEY_MIGRATED, false)) {
            migrateLegacy(context)
            scope.edit { putBoolean(KEY_MIGRATED, true) }
        }
    }

    fun set(context: Context, value: String): Boolean {
        val next = value.trim()
        if (next.isEmpty() || next == uk) return false
        uk = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_UK, next)
        }
        return true
    }

    private fun migrateLegacy(context: Context) {
        NAMESPACED.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.edit {
                legacyKeyMoves(PrefIndex.keys(prefs), prefix()).forEach { (oldKey, target) ->
                    if (!prefs.contains(target)) copyValue(this, prefs, oldKey, target)
                    // 旧键在新代码里永远读不到，留着只会让 preferences.all 越滚越大
                    // （prune/diskIndex 都是整表遍历）。复制成功后必须删掉。
                    remove(oldKey)
                }
            }
        }
    }

    private fun copyValue(
        editor: SharedPreferences.Editor,
        prefs: SharedPreferences,
        from: String,
        to: String,
    ) {
        when (val value = PrefIndex.value(prefs, from)) {
            is String -> editor.putString(to, value)
            is Long -> editor.putLong(to, value)
            is Int -> editor.putInt(to, value)
            is Boolean -> editor.putBoolean(to, value)
            is Float -> editor.putFloat(to, value)
        }
    }
}
