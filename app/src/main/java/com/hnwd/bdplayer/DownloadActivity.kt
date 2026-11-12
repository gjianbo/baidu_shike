package com.hnwd.bdplayer

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载管理页：进行中（多任务 + 实时进度）与历史两个 Tab。
 *
 * 传输与后台保活由系统 DownloadManager 负责，本页只按 1 秒轮询查询进度；
 * 因此不持有下载线程，退出页面或退出 App 都不会中断任务。
 */
class DownloadActivity : AppCompatActivity() {
    private lateinit var taskList: LinearLayout
    private lateinit var storageHint: TextView
    private lateinit var tabActive: TextView
    private lateinit var tabHistory: TextView

    private var showingHistory = false
    private var tasks: List<DownloadTask> = emptyList()
    private var speeds: Map<Long, Double> = emptyMap()
    private var lastBytes: Map<Long, Long> = emptyMap()
    private var lastSampleAt = 0L
    private var tick = 0
    /** 轮询结果与上次一致时不重建视图，避免进度条动画被每秒重置、空页面反复重绘。 */
    private var lastRenderKey = ""

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tasks = DownloadCenter.refresh(this@DownloadActivity)
            sampleSpeeds()
            tick++
            // 历史页不需要每秒重绘，仍保持轮询以便任务完成时状态能落到历史里。
            if (!showingHistory || tick % 5 == 0) render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.bg)
        window.navigationBarColor = color(R.color.bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeStore.isDark(this@DownloadActivity)
            isAppearanceLightNavigationBars = !ThemeStore.isDark(this@DownloadActivity)
        }
        setContentView(R.layout.activity_download)
        taskList = findViewById(R.id.taskList)
        storageHint = findViewById(R.id.storageHint)
        tabActive = findViewById(R.id.tabActive)
        tabHistory = findViewById(R.id.tabHistory)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            DownloadCenter.clearFinished(this)
            refreshNow()
            toast("已清空已完成记录")
        }
        tabActive.setOnClickListener { switchTab(false) }
        tabHistory.setOnClickListener { switchTab(true) }
        switchTab(false)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun switchTab(history: Boolean) {
        showingHistory = history
        paintTabs()
        refreshNow()
    }

    private fun refreshNow() {
        tasks = DownloadCenter.refresh(this)
        sampleSpeeds()
        render(force = true)
    }

    private fun sampleSpeeds() {
        val now = System.currentTimeMillis()
        val current = tasks.associate { it.id to it.bytesDone }
        if (lastSampleAt > 0 && now > lastSampleAt) {
            val seconds = (now - lastSampleAt) / 1000.0
            speeds = current.mapValues { (id, bytes) ->
                val previous = lastBytes[id] ?: bytes
                ((bytes - previous).coerceAtLeast(0) / seconds)
            }
        }
        lastBytes = current
        lastSampleAt = now
    }

    private fun paintTabs() {
        val activeOn = !showingHistory
        tabActive.setBackgroundResource(if (activeOn) R.drawable.bg_chip_on else R.drawable.bg_chip)
        tabActive.setTextColor(color(if (activeOn) R.color.accentInk else R.color.ink3))
        tabHistory.setBackgroundResource(if (activeOn) R.drawable.bg_chip else R.drawable.bg_chip_on)
        tabHistory.setTextColor(color(if (activeOn) R.color.ink3 else R.color.accentInk))
    }

    private fun render(force: Boolean = false) {
        val key = "$showingHistory|$tasks|$speeds"
        if (!force && key == lastRenderKey) return
        lastRenderKey = key
        val active = tasks.filter {
            it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
        }.sortedByDescending { it.createdAt }
        val finished = tasks.filter {
            it.state == DownloadState.SUCCESS || it.state == DownloadState.FAILED
        }.sortedByDescending { it.finishedAt }
        tabActive.text = "进行中 ${active.size}"
        tabHistory.text = "历史 ${finished.size}"
        taskList.removeAllViews()
        if (showingHistory) {
            if (finished.isEmpty()) {
                addEmpty("还没有下载记录")
            } else {
                finished.groupBy { dayLabel(it.finishedAt) }.forEach { (label, items) ->
                    addSection(label)
                    items.forEach { addHistoryRow(it) }
                }
            }
        } else {
            addSummary(active)
            if (active.isEmpty()) {
                addEmpty("当前没有下载任务")
            } else {
                addSection("正在下载")
                active.forEach { addActiveRow(it) }
            }
            val failed = finished.filter { it.state == DownloadState.FAILED }
            if (failed.isNotEmpty()) {
                addSection("需要重试")
                failed.forEach { addActiveRow(it) }
            }
        }
        storageHint.text = "保存到 下载 / ${DownloadLogic.FOLDER}/ · 共 ${tasks.size} 条记录 · 删除记录不会删除本机文件"
    }

    private fun addSummary(active: List<DownloadTask>) {
        val running = active.count { it.state == DownloadState.RUNNING }
        val queued = active.size - running
        val total = active.sumOf { if (it.bytesTotal > 0) it.bytesTotal else it.sizeBytes }
        val done = active.sumOf { it.bytesDone }
        val speed = active.sumOf { speeds[it.id] ?: 0.0 }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(13), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9) }
        }
        card.addView(horizontal().apply {
            addView(summaryText("${running} 个下载中${if (queued > 0) " · $queued 个排队" else ""}", true))
            addView(summaryText(DownloadLogic.speedText(speed), false, R.color.accentInk))
        })
        val display = DownloadLogic.summaryProgress(active.size, running, done, total)
        if (display.visible) card.addView(progressBar(display.percent, display.indeterminate))
        card.addView(horizontal().apply {
            addView(summaryText("已下载 ${DownloadLogic.formatBytes(done)} / 共 ${DownloadLogic.formatBytes(total)}", false, R.color.ink3))
            addView(summaryText("最多同时 ${DownloadLogic.MAX_PARALLEL} 个", false, R.color.ink3))
        })
        taskList.addView(card)
    }

    private fun addActiveRow(task: DownloadTask) {
        val row = card()
        row.addView(typeIcon(task.name))
        val main = vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(11) }
        }
        main.addView(title(task.name))
        val speed = speeds[task.id] ?: 0.0
        val meta = listOfNotNull(
            task.sourceLabel,
            DownloadLogic.formatBytes(if (task.bytesTotal > 0) task.bytesTotal else task.sizeBytes),
            DownloadLogic.speedText(speed).takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        main.addView(meta(meta))
        val percent = DownloadLogic.progress(task.bytesDone, task.bytesTotal)
        val display = DownloadLogic.taskProgress(task.state, task.bytesDone, task.bytesTotal)
        if (display.visible) main.addView(progressBar(display.percent, display.indeterminate))
        val foot = when {
            task.state == DownloadState.FAILED -> task.error.ifBlank { "下载失败" }
            task.state == DownloadState.QUEUED -> "等待中 · 前面还有 ${queuedAhead(task)} 个"
            percent < 0 -> "正在连接…"
            else -> "$percent% · ${DownloadLogic.etaText(
                ((if (task.bytesTotal > 0) task.bytesTotal else task.sizeBytes) - task.bytesDone).coerceAtLeast(0),
                speed,
            )}".trimEnd(' ', '·')
        }
        main.addView(meta(foot, if (task.state == DownloadState.FAILED) R.color.danger else R.color.ink3))
        row.addView(main)
        row.addView(action(if (task.state == DownloadState.FAILED) "重试" else "取消") {
            if (task.state == DownloadState.FAILED) {
                if (!DownloadCenter.retry(this, task.id)) toast("链接已失效，请回到文件列表重新下载")
                refreshNow()
            } else {
                DownloadCenter.cancel(this, task.id)
                toast("已取消下载")
                refreshNow()
            }
        })
        taskList.addView(row)
    }

    private fun addHistoryRow(task: DownloadTask) {
        val row = card()
        row.addView(typeIcon(task.name))
        val main = vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(11) }
        }
        main.addView(title(task.name))
        val detail = listOfNotNull(
            task.sourceLabel,
            DownloadLogic.formatBytes(if (task.bytesTotal > 0) task.bytesTotal else task.sizeBytes),
            timeLabel(task.finishedAt),
            task.error.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        main.addView(meta(detail, if (task.state == DownloadState.FAILED) R.color.danger else R.color.ink3))
        row.addView(main)
        row.addView(action(if (task.state == DownloadState.FAILED) "重试" else "打开") {
            if (task.state == DownloadState.FAILED) {
                if (!DownloadCenter.retry(this, task.id)) toast("链接已失效，请回到文件列表重新下载")
                switchTab(false)
            } else if (!DownloadCenter.open(this, task)) {
                toast("没有可打开此文件的应用")
            }
        })
        taskList.addView(row)
    }

    private fun queuedAhead(task: DownloadTask): Int =
        tasks.count {
            it.state == DownloadState.QUEUED && it.downloadId < 0 && it.createdAt < task.createdAt
        }

    // ===================== 视图小工具 =====================

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(9) }
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun summaryText(value: String, bold: Boolean, tint: Int = R.color.ink) = TextView(this).apply {
        text = value
        textSize = 11.5f
        setTextColor(color(tint))
        if (bold) setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (bold) gravity = Gravity.START else gravity = Gravity.END
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 12.5f
        setTextColor(color(R.color.ink))
        setTypeface(null, Typeface.BOLD)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun meta(value: String, tint: Int = R.color.ink3) = TextView(this).apply {
        text = value
        textSize = 10.5f
        setTextColor(color(tint))
        setPadding(0, dp(3), 0, 0)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun progressBar(percent: Int, indeterminate: Boolean) =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = indeterminate
            progress = percent.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(color(R.color.accent))
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
                .apply { topMargin = dp(9); bottomMargin = dp(6) }
        }

    private fun typeIcon(name: String) = TextView(this).apply {
        val ext = name.substringAfterLast('.', "").uppercase(Locale.US).take(4).ifEmpty { "文件" }
        val video = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mov", "mkv", "avi")
        val audio = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "aac", "wav", "flac")
        val pdf = name.substringAfterLast('.', "").equals("pdf", true)
        setBackgroundResource(
            when {
                video -> R.drawable.bg_fmt_video
                audio -> R.drawable.bg_fmt_audio
                pdf -> R.drawable.bg_fmt_pdf
                else -> R.drawable.bg_fmt_folder
            },
        )
        setTextColor(
            color(
                when {
                    video -> R.color.accentInk
                    audio -> R.color.ok
                    pdf -> R.color.pdfInk
                    else -> R.color.folderInk
                },
            ),
        )
        text = ext
        textSize = 9f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
    }

    private fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(color(R.color.accentInk))
        setBackgroundResource(R.drawable.bg_chip)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(10) }
    }

    private fun addSection(label: String) {
        taskList.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.ink3))
            setPadding(dp(2), dp(6), 0, dp(8))
        })
    }

    private fun addEmpty(label: String) {
        taskList.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(color(R.color.ink3))
            gravity = Gravity.CENTER
            setPadding(0, dp(56), 0, dp(56))
        })
    }

    private fun dayLabel(time: Long): String {
        if (time <= 0) return "更早"
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(time))
        return when (day) {
            today -> "今天"
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(System.currentTimeMillis() - 86_400_000L)) -> "昨天"
            else -> day
        }
    }

    private fun timeLabel(time: Long): String =
        if (time <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))
}
