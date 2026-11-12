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
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 传输中心：进行中 / 历史两个 Tab，同时展示下载与上传。
 *
 * - 下载：传输与后台保活由系统 DownloadManager 负责，本页按 1 秒轮询 Query；
 * - 上传：真相源是进程级的 UploadCenter，历史落在 UploadStore，本页只读记录。
 *
 * 两条链路都不挂在本 Activity 上，退出页面或退到后台都不会中断任务。
 */
class DownloadActivity : AppCompatActivity() {
    private lateinit var taskList: LinearLayout
    private lateinit var storageHint: TextView
    private lateinit var tabActive: TextView
    private lateinit var tabHistory: TextView

    private var showingHistory = false
    private var historyFilter = HistoryFilter.ALL
    private var tasks: List<DownloadTask> = emptyList()
    private var uploads: List<UploadRecord> = emptyList()
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
            uploads = UploadStore.records(this@DownloadActivity)
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
        findViewById<View>(R.id.btnClear).setOnClickListener { showClearMenu(it) }
        tabActive.setOnClickListener { switchTab(false) }
        tabHistory.setOnClickListener { switchTab(true) }
        switchTab(false)
    }

    /**
     * 右上「清空」的两个选项：清空已完成 / 清理失败的记录（带数量，为 0 时禁用）。
     * 清失败项时还会删掉下载器里的半成品文件与上传私有缓存残留；成功与进行中记录、
     * 已完成文件、网盘上的文件一律不动。
     */
    private fun showClearMenu(anchor: View) {
        val counts = TransferFilter.counts(
            tasks.filter { it.state == DownloadState.SUCCESS || it.state == DownloadState.FAILED }
                .map { it.state },
            uploads.filterNot(UploadRecordLogic::isActive).map { it.state },
        )
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_CLEAR_SUCCESS, 0, TransferCleanup.menuSuccessLabel(counts.success))
                .isEnabled = counts.success > 0
            menu.add(0, MENU_CLEAR_FAILED, 1, TransferCleanup.menuFailedLabel(counts.failed))
                .isEnabled = counts.failed > 0
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CLEAR_SUCCESS -> {
                        DownloadCenter.clearFinished(this@DownloadActivity)
                        UploadStore.clearFinished(this@DownloadActivity)
                        refreshNow()
                        toast(TransferCleanup.clearSuccessMessage(counts.success))
                        true
                    }
                    MENU_CLEAR_FAILED -> {
                        clearFailedRecords()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun clearFailedRecords() {
        // 先取失败任务的 id（用于清私有缓存），再删记录；活跃任务的缓存由纯逻辑保护。
        val failedUploadIds = UploadStore.records(this)
            .filter { it.state == UploadRecordState.FAILED }
            .map { it.id }.toSet()
        val downloadCleared = DownloadCenter.clearFailed(this)
        val uploadCleared = UploadStore.clearFailed(this)
        val files = UploadCenter.clearFailedArtifacts(this, failedUploadIds)
        refreshNow()
        toast(TransferCleanup.clearFailedMessage(downloadCleared + uploadCleared, files))
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
        uploads = UploadStore.records(this)
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
        // speeds 每秒都在变，放进 key 会让视图每秒被 removeAllViews() 重建，
        // 把「总长未知」的 indeterminate 进度条动画反复重置。只在任务/记录本身变化时重绘；
        // 速度文本仍从 speeds 读最新值，且 bytesDone 变化时 key 自然会变。
        val key = "$showingHistory|$historyFilter|$tasks|$uploads"
        if (!force && key == lastRenderKey) return
        lastRenderKey = key
        val activeDownloads = tasks.filter {
            it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
        }.sortedByDescending { it.createdAt }
        val finishedDownloads = tasks.filter {
            it.state == DownloadState.SUCCESS || it.state == DownloadState.FAILED
        }.sortedByDescending { it.finishedAt }
        val activeUploads = uploads.filter(UploadRecordLogic::isActive).sortedByDescending { it.createdAt }
        val runningUploads = activeUploads.filter { it.state == UploadRecordState.RUNNING }
        val queuedUploads = activeUploads.filter(UploadRecordLogic::isQueued)
        val finishedUploads = uploads.filterNot(UploadRecordLogic::isActive).sortedByDescending { it.finishedAt }
        val counts = TransferFilter.counts(
            finishedDownloads.map { it.state }, finishedUploads.map { it.state },
        )
        tabActive.text = "进行中 ${activeDownloads.size + activeUploads.size}"
        tabHistory.text = "历史 ${counts.all}"
        taskList.removeAllViews()
        if (showingHistory) {
            addFilterChips(counts)
            val history = (
                finishedDownloads
                    .filter { TransferFilter.keepDownload(it.state, historyFilter) }
                    .map { HistoryEntry.Down(it) } +
                    finishedUploads
                        .filter { TransferFilter.keepUpload(it.state, historyFilter) }
                        .map { HistoryEntry.Up(it) }
                ).sortedByDescending { it.time }
            if (history.isEmpty()) {
                addEmpty(TransferFilter.emptyText(historyFilter))
            } else {
                history.groupBy { dayLabel(it.time) }.forEach { (label, items) ->
                    addSection(label)
                    items.forEach { entry ->
                        when (entry) {
                            is HistoryEntry.Down -> addHistoryRow(entry.task)
                            is HistoryEntry.Up -> addUploadHistoryRow(entry.record)
                        }
                    }
                }
            }
        } else {
            addSummary(activeDownloads, runningUploads.size, queuedUploads.size)
            if (activeDownloads.isEmpty() && activeUploads.isEmpty()) {
                addEmpty("当前没有传输任务")
            }
            if (runningUploads.isNotEmpty()) {
                addSection("正在上传")
                runningUploads.forEach { addUploadActiveRow(it) }
            }
            if (queuedUploads.isNotEmpty()) {
                addSection("排队中 ${queuedUploads.size}")
                queuedUploads.forEach { addQueuedUploadRow(it) }
            }
            if (activeDownloads.isNotEmpty()) {
                addSection("正在下载")
                activeDownloads.forEach { addActiveRow(it) }
            }
            val failed = finishedDownloads.filter { it.state == DownloadState.FAILED }
            if (failed.isNotEmpty()) {
                addSection("需要重试")
                failed.forEach { addActiveRow(it) }
            }
        }
        storageHint.text =
            "下载保存到 下载 / ${DownloadLogic.FOLDER}/ · 上传保存到 百度/夸克网盘当前目录\n" +
                "共 ${tasks.size + uploads.size} 条记录 · 清空记录不会删除文件"
    }

    /** 历史页把两类终态记录按完成时间混排，再按天分组。 */
    private sealed class HistoryEntry {
        abstract val time: Long

        data class Down(val task: DownloadTask) : HistoryEntry() {
            override val time: Long get() = task.finishedAt
        }

        data class Up(val record: UploadRecord) : HistoryEntry() {
            override val time: Long get() = record.finishedAt
        }
    }

    /** 历史页顶部的二级状态筛选；chip 上的计数基于全量，不随当前筛选缩水。 */
    private fun addFilterChips(counts: TransferFilter.Counts) {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
        }
        HistoryFilter.entries.forEach { filter ->
            val selected = filter == historyFilter
            val chip = TextView(this).apply {
                text = TransferFilter.label(filter, counts)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(color(if (selected) R.color.accentInk else R.color.ink3))
                setBackgroundResource(if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
                setPadding(dp(6), dp(7), dp(6), dp(7))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (historyFilter == filter) return@setOnClickListener
                    historyFilter = filter
                    render(force = true)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
            }
            row.addView(chip)
        }
        taskList.addView(row)
    }

    private fun addSummary(active: List<DownloadTask>, runningUploads: Int, queuedUploads: Int) {
        val running = active.count { it.state == DownloadState.RUNNING }
        val queuedDownloads = active.size - running
        val headline = buildString {
            append("$running 个下载中")
            if (queuedDownloads > 0) append(" · $queuedDownloads 个排队")
            if (runningUploads > 0) append(" · $runningUploads 个上传中")
            if (queuedUploads > 0) append(" · $queuedUploads 个待上传")
        }
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
            addView(summaryText(headline, true))
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

    /** 进行中的上传：只有「取消」，没有暂停（前台服务不提供 App 主动暂停）。 */
    private fun addUploadActiveRow(record: UploadRecord) {
        val row = card()
        row.addView(typeIcon(record.name))
        val main = vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(11) }
        }
        main.addView(title(record.name))
        main.addView(
            meta(
                listOfNotNull(
                    record.source,
                    record.dir.takeIf { it.isNotBlank() },
                    DownloadLogic.formatBytes(record.sizeBytes),
                ).joinToString(" · "),
            ),
        )
        main.addView(progressBar(record.percent.coerceIn(0, 100), false))
        main.addView(meta("${record.percent.coerceIn(0, 100)}% · ${record.label}"))
        row.addView(main)
        row.addView(action("取消") {
            UploadCenter.cancel(this, record.id)
            toast("已取消上传")
            refreshNow()
        })
        taskList.addView(row)
    }

    /** 排队中的上传：不画进度条，可单独移出队列。 */
    private fun addQueuedUploadRow(record: UploadRecord) {
        val row = card()
        row.addView(typeIcon(record.name))
        val main = vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(11) }
        }
        main.addView(title(record.name))
        main.addView(
            meta(
                listOfNotNull(
                    record.source,
                    record.dir.takeIf { it.isNotBlank() },
                    DownloadLogic.formatBytes(record.sizeBytes),
                    "等待中",
                ).joinToString(" · "),
            ),
        )
        row.addView(main)
        row.addView(action("移除") {
            UploadCenter.cancel(this, record.id)
            toast("已移出队列")
            refreshNow()
        })
        taskList.addView(row)
    }

    /** 上传历史：文件在网盘而不是本机，所以没有「打开」；失败也不能页内重试（源文件已删）。 */
    private fun addUploadHistoryRow(record: UploadRecord) {
        val row = card()
        row.addView(typeIcon(record.name))
        val main = vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(11) }
        }
        main.addView(title(record.name))
        // 成功时 message 是「已上传：文件名」，与标题重复；只有失败原因才值得占这一行。
        val detail = listOfNotNull(
            record.source,
            DownloadLogic.formatBytes(record.sizeBytes),
            timeLabel(record.finishedAt),
            record.message.takeIf { it.isNotBlank() && record.state == UploadRecordState.FAILED },
        ).joinToString(" · ")
        main.addView(
            meta(detail, if (record.state == UploadRecordState.FAILED) R.color.danger else R.color.ink3),
        )
        row.addView(main)
        row.addView(tag(if (record.state == UploadRecordState.SUCCESS) "已上传" else "失败"))
        taskList.addView(row)
    }

    private fun tag(label: String) = TextView(this).apply {
        text = label
        textSize = 10.5f
        setTextColor(color(R.color.ink3))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(10) }
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

    private companion object {
        const val MENU_CLEAR_SUCCESS = 1
        const val MENU_CLEAR_FAILED = 2
    }
}
