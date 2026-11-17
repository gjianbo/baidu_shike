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

internal enum class MainNavigationLayout {
    BOTTOM, RAIL, SIDEBAR;

    companion object {
        // 为 200dp 侧栏、页边距和两列文件同时预留空间。
        private const val SIDEBAR_MIN_WINDOW_DP = 960

        fun forWidth(widthDp: Int): MainNavigationLayout = when {
            widthDp >= SIDEBAR_MIN_WINDOW_DP -> SIDEBAR
            widthDp >= WIDE_WINDOW_DP -> RAIL
            else -> BOTTOM
        }
    }
}

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

/** capWidth=false 用于紧贴侧栏的主工作区：限宽居中会在侧栏右侧留出一条空白带。 */
internal fun Activity.centeredContent(content: View, capWidth: Boolean = true): FrameLayout = FrameLayout(this).apply {
    setBackgroundColor(getColor(R.color.bg))
    if (capWidth) {
        val frame = AdaptiveContentFrame(context)
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
    } else {
        addView(content, FrameLayout.LayoutParams(-1, -1))
    }
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
