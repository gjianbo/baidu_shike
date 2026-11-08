package com.hnwd.bdplayer

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal object ThemeStore {
    private const val PREFS = "app_settings"
    private const val KEY_THEME = "appearance"
    private const val KEY_SORT = "course_order"
    private const val KEY_PENDING_SCREEN = "pending_screen"

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, LIGHT) ?: LIGHT

    fun label(context: Context): String = label(mode(context))

    fun label(mode: String): String = when (mode) {
        DARK -> "深色"
        SYSTEM -> "跟随系统"
        else -> "浅色"
    }

    fun isDark(context: Context): Boolean {
        val night = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (mode(context)) {
            DARK -> true
            LIGHT -> false
            else -> night
        }
    }

    fun set(context: Context, mode: String, pendingScreen: String? = null) {
        val safe = mode.takeIf { it == SYSTEM || it == LIGHT || it == DARK } ?: LIGHT
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, safe)
            .apply {
                if (pendingScreen != null) putString(KEY_PENDING_SCREEN, pendingScreen)
            }
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            when (safe) {
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_NO
            },
        )
    }

    fun takePendingScreen(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_PENDING_SCREEN, null)
        if (value != null) prefs.edit().remove(KEY_PENDING_SCREEN).apply()
        return value
    }

    fun rememberScreen(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_SCREEN, value).apply()
    }

    fun courseOrder(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SORT, "ASCENDING") ?: "ASCENDING"

    fun setCourseOrder(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SORT, value).apply()
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_NO
            },
        )
    }
}
