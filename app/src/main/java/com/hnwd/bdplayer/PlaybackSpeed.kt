package com.hnwd.bdplayer

import android.content.Context
import kotlin.math.abs

internal object PlaybackSpeed {
    val values = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private const val PREFS = "app_settings"
    private const val VIDEO_KEY = "video_speed"
    private const val AUDIO_KEY = "audio_speed"

    fun video(context: Context): Float = read(context, VIDEO_KEY)

    fun audio(context: Context): Float = read(context, AUDIO_KEY)

    fun setVideo(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(VIDEO_KEY, speed).apply()
    }

    fun setAudio(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(AUDIO_KEY, speed).apply()
    }

    fun label(speed: Float): String = when {
        abs(speed - 0.5f) < 0.01f -> "0.5x"
        abs(speed - 0.75f) < 0.01f -> "0.75x"
        abs(speed - 1.25f) < 0.01f -> "1.25x"
        abs(speed - 1.5f) < 0.01f -> "1.5x"
        abs(speed - 2f) < 0.01f -> "2.0x"
        else -> "1.0x"
    }

    fun spoken(speed: Float): String = "${label(speed).removeSuffix("x")} 倍"

    private fun read(context: Context, key: String): Float {
        val speed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(key, 1f)
        return if (values.any { abs(it - speed) < 0.01f }) speed else 1f
    }
}
