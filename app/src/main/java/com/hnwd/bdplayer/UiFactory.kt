package com.hnwd.bdplayer

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 纯视图工厂与尺寸/取色工具，作为 Activity 扩展函数。
 *
 * 从 MainActivity 原样搬出：只依赖 Activity 的 resources/theme，不读写任何业务状态，
 * 因此任何页面类都能直接用，不需要传 Activity 实例。
 */

internal fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Activity.color(id: Int) = resources.getColor(id, theme)

internal fun Activity.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

internal fun Activity.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

internal fun Activity.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

internal fun Activity.label(value: String, size: Float) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(Color.rgb(52, 38, 30))
    setPadding(0, dp(10), 0, dp(10))
}

internal fun Activity.button(value: String, action: () -> Unit) = Button(this).apply {
    text = value
    isAllCaps = false
    setOnClickListener { action() }
}

internal fun Activity.padded(view: android.view.View): android.view.View = FrameLayout(this).apply {
    setPadding(dp(24), dp(8), dp(24), 0)
    addView(view)
}

internal fun Activity.headAction(value: String, action: () -> Unit): TextView = TextView(this).apply {
    text = value
    gravity = Gravity.CENTER
    textSize = 11.5f
    setTextColor(color(R.color.ink2))
    setBackgroundResource(R.drawable.bg_chip)
    setPadding(dp(10), dp(10), dp(10), dp(10))
    layoutParams = LinearLayout.LayoutParams(-1, -2)
    isClickable = true
    setOnClickListener { action() }
}

internal fun Activity.audioIconButton(res: Int, description: String, tint: Int, action: () -> Unit) =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        setBackgroundResource(R.drawable.bg_icon_ripple)
        isClickable = true
        setOnClickListener { action() }
    }

internal fun Activity.paintFavorite(icon: ImageView, starred: Boolean) {
    icon.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
    icon.contentDescription = if (starred) "取消收藏" else "收藏"
}

/** 列表行的格式图标：按媒体类别换图标、底色与着色。 */
internal fun Activity.paintMediaIcon(icon: ImageView, kind: MediaKind, directory: Boolean) {
    when (kind) {
        MediaKind.AUDIO -> {
            icon.setImageResource(R.drawable.ic_music)
            icon.setBackgroundResource(R.drawable.bg_fmt_audio)
            icon.setColorFilter(color(R.color.accentInk))
        }
        MediaKind.VIDEO -> {
            icon.setImageResource(R.drawable.ic_video)
            icon.setBackgroundResource(R.drawable.bg_fmt_video)
            icon.setColorFilter(color(R.color.accentInk))
        }
        MediaKind.IMAGE -> {
            icon.setImageResource(R.drawable.ic_image)
            icon.setBackgroundResource(R.drawable.bg_fmt_image)
            icon.setColorFilter(color(R.color.ok))
        }
        MediaKind.PDF, MediaKind.DOCUMENT -> {
            icon.setImageResource(R.drawable.ic_file)
            icon.setBackgroundResource(R.drawable.bg_fmt_pdf)
            icon.setColorFilter(color(R.color.pdfInk))
        }
        else -> {
            icon.setImageResource(if (directory) R.drawable.ic_folder else R.drawable.ic_file)
            icon.setBackgroundResource(R.drawable.bg_fmt_folder)
            icon.setColorFilter(color(R.color.folderInk))
        }
    }
}
