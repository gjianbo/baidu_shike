package com.hnwd.bdplayer

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal data class ActionGridItem(
    val label: String,
    val icon: Int,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

internal fun <T> actionGridRows(items: List<T>): List<List<T>> =
    items.chunked(if (items.size == 5 || items.size > 8) 5 else 4)

internal fun Activity.renderActionGrid(container: LinearLayout, items: List<ActionGridItem>) {
    container.removeAllViews()
    val rows = actionGridRows(items)
    val columns = rows.firstOrNull()?.size?.coerceAtLeast(4) ?: return
    rows.forEach { line ->
        val actionRow = row()
        val emptySlots = columns - line.size
        val alignDelete = rows.size > 1 && line.last().destructive
        fun addEmptySlots() {
            repeat(emptySlots) {
                actionRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }
        }
        line.forEachIndexed { index, item ->
            if (alignDelete && index == line.lastIndex) addEmptySlots()
            val tint = color(if (item.destructive) R.color.danger else R.color.ink)
            val cell = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
                isFocusable = true
                setBackgroundResource(R.drawable.bg_icon_ripple)
                addView(ImageView(context).apply {
                    setImageResource(item.icon)
                    setColorFilter(tint)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(context).apply {
                    text = item.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(tint)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { item.onClick() }
            }
            actionRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (!alignDelete) addEmptySlots()
        container.addView(actionRow, LinearLayout.LayoutParams(-1, -2))
    }
}
