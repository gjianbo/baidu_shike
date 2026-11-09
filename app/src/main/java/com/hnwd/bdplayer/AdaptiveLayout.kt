package com.hnwd.bdplayer

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

internal const val WIDE_WINDOW_DP = 600
internal const val EXPANDED_WINDOW_DP = 840

internal val Context.hasWideWindow: Boolean
    get() = resources.configuration.screenWidthDp >= WIDE_WINDOW_DP

internal class AdaptiveContentFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            resources.getDimensionPixelSize(R.dimen.content_max_width),
        )
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

internal fun Activity.centeredContent(content: View): FrameLayout = FrameLayout(this).apply {
    setBackgroundColor(getColor(R.color.bg))
    val frame = AdaptiveContentFrame(context)
    frame.addView(content, FrameLayout.LayoutParams(-1, -1))
    addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
}

internal fun Window.sizeBottomSheet(host: Activity, height: (Int) -> Int) {
    val resources = host.resources
    val metrics = resources.displayMetrics
    val configuration = resources.configuration
    val width = if (host.hasWideWindow) {
        val available = (configuration.screenWidthDp * metrics.density).toInt()
        val gutter = (48 * metrics.density).toInt()
        minOf(available - gutter, resources.getDimensionPixelSize(R.dimen.sheet_max_width))
    } else {
        WindowManager.LayoutParams.MATCH_PARENT
    }
    val availableHeight = if (configuration.screenHeightDp > 0) {
        (configuration.screenHeightDp * metrics.density).toInt()
    } else {
        metrics.heightPixels
    }
    setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    setLayout(width, height(availableHeight))
}
