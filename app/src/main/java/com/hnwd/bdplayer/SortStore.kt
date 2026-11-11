package com.hnwd.bdplayer

import android.content.Context

/**
 * 排序偏好存储：列表页与播放页各记一份，同一目录互不覆盖。
 *
 * 键 = 作用域前缀（list: / play:）+ 目录身份。目录身份沿用课程缓存的目录键
 * （dir:gid:msg:file / roots:gid / disk:dir:<id>），与播放页历史上的键一致，
 * 所以老版本写在 playback_order 里的偏好不用迁移也能继续读到。
 *
 * 读取顺序由调用方决定：播放页「本作用域 -> 列表侧 -> 全局默认」，
 * 这样没单独设过的地方跟随另一侧，单独改过的地方各自独立。
 */
internal object SortStore {
    private const val PREFS = "sort_order"
    private const val LEGACY_PREFS = "playback_order"
    private const val LIST = "list:"
    private const val PLAY = "play:"

    fun list(context: Context, directory: String?): CourseOrder? =
        read(context, LIST, directory, legacy = false)

    fun setList(context: Context, directory: String?, order: CourseOrder) =
        write(context, LIST, directory, order)

    fun playback(context: Context, directory: String?): CourseOrder? =
        read(context, PLAY, directory, legacy = true)

    fun setPlayback(context: Context, directory: String?, order: CourseOrder) =
        write(context, PLAY, directory, order)

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(scope + dir, order.name).apply()
    }
}
