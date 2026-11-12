package com.hnwd.bdplayer

import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

internal class HomeRefreshFeedback(activity: MainActivity, private val notice: View) {
    private val spinner = notice.findViewById<ProgressBar>(R.id.homeRefreshSpinner)
    private val label = notice.findViewById<TextView>(R.id.homeRefreshLabel)
    private val hide = Runnable { notice.isVisible = false }
    private var generation = 0
    var refreshing = false
        private set

    init {
        val scroll = activity.contentScroll
        val container = scroll.parent as FrameLayout
        val index = container.indexOfChild(scroll)
        val params = scroll.layoutParams
        container.removeViewAt(index)
        val swipe = SwipeRefreshLayout(activity).apply {
            addView(scroll, -1, -1)
            setColorSchemeColors(activity.color(R.color.accentInk))
            setOnChildScrollUpCallback { _, _ ->
                !activity.isHomeRoot() || refreshing || scroll.canScrollVertically(-1)
            }
            setOnRefreshListener {
                isRefreshing = false
                activity.requestBrowseRefresh()
            }
        }
        container.addView(swipe, index, params)
    }

    fun begin(): (String?) -> Unit {
        clear()
        refreshing = true
        spinner.isVisible = true
        notice.isVisible = true
        label.text = "正在刷新…"
        val request = generation
        return { message ->
            if (refreshing && request == generation) {
                if (message == null) {
                    // 百度请求超时时会先回调取消，再回调失败。
                    notice.post { if (refreshing && request == generation) clear() }
                } else {
                    showResult(message)
                }
            }
        }
    }

    fun showResult(message: String) {
        refreshing = false
        spinner.isVisible = false
        notice.isVisible = true
        label.text = message
        notice.removeCallbacks(hide)
        notice.postDelayed(hide, 2_000)
    }

    fun clear() {
        generation++
        refreshing = false
        notice.removeCallbacks(hide)
        notice.isVisible = false
    }
}
