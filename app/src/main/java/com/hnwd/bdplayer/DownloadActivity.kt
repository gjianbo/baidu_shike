package com.hnwd.bdplayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
    private lateinit var btnSelect: TextView
    private lateinit var btnClear: TextView
    private lateinit var selectBar: LinearLayout
    private lateinit var selectInfo: TextView
    private lateinit var btnShareSelected: TextView
    private lateinit var btnDeleteSelected: TextView

    private var showingHistory = false
    private var historyFilter = HistoryFilter.ALL
    /** 多选态：只在历史页可用，选中集合按记录 id 记（每次渲染都会重建视图）。 */
    private var selecting = false
    private val selected = linkedSetOf<Long>()
    /** 多选态下每行的勾选框：勾选时只改这一个 View，不重建整个列表（否则长列表会跳）。 */
    private val rowChecks = mutableMapOf<Long, CheckBox>()
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
        btnSelect = findViewById(R.id.btnSelect)
        btnClear = findViewById(R.id.btnClear)
        selectBar = findViewById(R.id.selectBar)
        selectInfo = findViewById(R.id.selectInfo)
        btnShareSelected = findViewById(R.id.btnShareSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnClear.setOnClickListener { showClearMenu(it) }
        btnSelect.setOnClickListener { if (selecting) exitSelection() else enterSelection() }
        btnShareSelected.setOnClickListener { shareSelected() }
        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        tabActive.setOnClickListener { switchTab(false) }
        tabHistory.setOnClickListener { switchTab(true) }
        // 多选态下返回键先退出多选，而不是直接关掉传输中心。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) {
                    exitSelection()
                    return
                }
                // 交给系统默认行为（finish）。临时关掉自己避免递归，随后恢复，
                // 否则第一次关闭页面后这个回调永久失效，下次进多选就退不出来了。
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
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
        DownloadCenter.resume(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun switchTab(history: Boolean) {
        showingHistory = history
        // 进行中页没有可多选的终态记录，切过去就退出多选。
        if (!history) {
            selecting = false
            selected.clear()
        }
        paintTabs()
        paintSelection()
        refreshNow()
    }

    /** 多选态的界面对齐：顶部入口文案、底部操作栏显隐与可用性。 */
    private fun paintSelection() {
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        btnSelect.text = if (selecting) "取消" else "选择"
        btnSelect.visibility = if (showingHistory || selecting) View.VISIBLE else View.GONE
        btnClear.visibility = if (selecting) View.GONE else View.VISIBLE
        selectInfo.text = DownloadFileLogic.selectionHint(selected.size)
        val enabled = selected.isNotEmpty()
        listOf(btnShareSelected, btnDeleteSelected).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun enterSelection() {
        selecting = true
        selected.clear()
        paintSelection()
        render(force = true)
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        rowChecks.clear()
        paintSelection()
        // 记录可能刚被删掉，退出时顺手对齐列表。
        refreshNow()
    }

    private fun toggleSelected(id: Long) {
        if (!selected.add(id)) selected.remove(id)
        // 只翻这一个勾选框：整表重建会让 ScrollView 的滚动位置跳一下。
        rowChecks[id]?.isChecked = selected.contains(id)
        paintSelection()
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
        val successfulIds = tasks.filter { it.state == DownloadState.SUCCESS }.map { it.id }.toSet()
        // 记录被删/被清空后，多选集合里不该留下已经不存在的 id；真清掉了就重建一次对齐。
        val pruned = selected.retainAll(successfulIds)
        // key 里刻意不放 selected：勾选由行内 CheckBox 自己维护，勾一下不该重建整张表。
        val key = "$showingHistory|$historyFilter|$selecting|$tasks|$uploads"
        if (!force && !pruned && key == lastRenderKey) return
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
        rowChecks.clear()
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
                "长按记录可分享 / 重命名 / 删除 · 共 ${tasks.size + uploads.size} 条记录 · 清空记录不会删除文件"
        paintSelection()
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
            task.publishing -> "正在保存到下载目录…"
            task.state == DownloadState.QUEUED && task.downloadId >= 0 -> "等待系统下载"
            task.state == DownloadState.QUEUED -> "等待中 · 前面还有 ${queuedAhead(task)} 个"
            percent < 0 -> "正在连接…"
            else -> "$percent% · ${DownloadLogic.etaText(
                ((if (task.bytesTotal > 0) task.bytesTotal else task.sizeBytes) - task.bytesDone).coerceAtLeast(0),
                speed,
            )}".trimEnd(' ', '·')
        }
        main.addView(meta(foot, if (task.state == DownloadState.FAILED) R.color.danger else R.color.ink3))
        row.addView(main)
        if (task.state == DownloadState.FAILED || !task.publishing &&
            (task.downloadId < 0 || task.stagingName.isNotBlank())
        ) row.addView(action(if (task.state == DownloadState.FAILED) retryLabel(task) else "取消") {
            if (task.state == DownloadState.FAILED) {
                retryDownload(task)
            } else {
                val cancelled = DownloadCenter.cancel(this, task.id)
                refreshNow()
                toast(when {
                    cancelled -> "已取消下载"
                    tasks.any { it.id == task.id && (it.state == DownloadState.SUCCESS || it.publishing) } ->
                        "文件已下载完成，正在保存到下载目录"
                    else -> "无法取消下载，请稍后重试"
                })
            }
        })
        // 「需要重试」分区里的失败记录也可以长按移除，不必绕到历史页。
        if (task.state == DownloadState.FAILED) attachMoreMenu(row, task)
        taskList.addView(row)
    }

    private fun retryLabel(task: DownloadTask): String =
        if (task.needsVerification) "验证" else "重试"

    private fun retryDownload(task: DownloadTask) {
        if (!task.publishing && !DownloadLogic.hasAccountBinding(task)) {
            toast("旧记录缺少来源或账号，请回到文件列表重新下载")
            return
        }
        if (task.needsVerification) {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_VERIFY_DOWNLOAD, task.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            return
        }
        if (DownloadCenter.retry(this, task.id)) {
            switchTab(false)
        } else {
            toast("无法重新加入下载队列，请稍后重试")
            refreshNow()
        }
    }

    private fun addHistoryRow(task: DownloadTask) {
        val row = card()
        // 多选态只允许勾成功的记录：失败记录没有文件，分享与删文件都无从谈起。
        val selectable = selecting && task.state == DownloadState.SUCCESS
        if (selecting) {
            val check = CheckBox(this).apply {
                isChecked = selected.contains(task.id)
                isClickable = false
                isFocusable = false
                isEnabled = selectable
                contentDescription = "选择 ${task.name}"
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
            // 只登记可勾的行：失败行没有文件，勾了也没意义。
            if (selectable) rowChecks[task.id] = check
            row.addView(check)
        }
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
        // 多选态下隐藏行尾按钮：整行点击就是「勾选/取消」，避免误触打开文件。
        if (!selecting) {
            row.addView(action(if (task.state == DownloadState.FAILED) retryLabel(task) else "打开") {
                if (task.state == DownloadState.FAILED) {
                    retryDownload(task)
                } else if (!DownloadCenter.open(this, task)) {
                    toast("没有可打开此文件的应用")
                }
            })
        }
        if (selecting) {
            if (selectable) row.setOnClickListener { toggleSelected(task.id) }
        } else if (task.state == DownloadState.SUCCESS || task.state == DownloadState.FAILED) {
            attachMoreMenu(row, task)
        }
        taskList.addView(row)
    }

    /**
     * 长按终态记录 → 更多操作菜单。分享直接把 uri 交给系统面板；重命名/删除作用于
     * MediaStore 里的真实文件；「删除」默认只移除记录，勾选后才动文件。
     */
    private fun attachMoreMenu(row: View, task: DownloadTask) {
        if (DownloadFileLogic.actions(task.state).isEmpty()) return
        row.setOnLongClickListener {
            showMoreMenu(row, task)
            true
        }
    }

    private fun showMoreMenu(anchor: View, task: DownloadTask) {
        val actions = DownloadFileLogic.actions(task.state)
        PopupMenu(this, anchor).apply {
            // itemId 用「当前展示的下标」而不是 ordinal：失败记录只有一项，ordinal 会越界。
            actions.forEachIndexed { index, action ->
                menu.add(0, index, index, DownloadFileLogic.label(action, task.state))
            }
            setOnMenuItemClickListener { item ->
                when (actions.getOrNull(item.itemId)) {
                    MoreAction.SHARE -> shareFile(task)
                    MoreAction.RENAME -> promptRename(task)
                    MoreAction.COPY_NAME ->
                        copyText(task.name, DownloadFileLogic.copiedNameMessage())
                    MoreAction.COPY_PATH -> {
                        val path = DownloadFileLogic.locationOf(task.name)
                        copyText(path, DownloadFileLogic.copiedPathMessage(path))
                    }
                    MoreAction.INFO -> showFileInfo(task)
                    MoreAction.REVEAL -> revealFolder(task)
                    MoreAction.DELETE -> confirmDelete(task)
                    null -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun shareFile(task: DownloadTask) {
        when (DownloadShare.share(this, task)) {
            DownloadShare.Outcome.LAUNCHED -> Unit
            DownloadShare.Outcome.NOT_READY ->
                toast(ShareLogic.unavailableMessage(task.state, task.publishing))
            DownloadShare.Outcome.NO_TARGET -> toast(ShareLogic.NO_TARGET_MESSAGE)
        }
    }

    private fun copyText(value: String, message: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast("复制失败，请稍后重试")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("拾课", value))
        toast(message)
    }

    /** 「打开所在文件夹」打不开时退回复制路径——至少用户能自己贴到文件管理器里。 */
    private fun revealFolder(task: DownloadTask) {
        if (DownloadFileOps.reveal(this)) return
        val path = DownloadFileLogic.locationOf(task.name)
        copyText(path, DownloadFileLogic.revealFallbackMessage(path))
    }

    /** 多选批量分享：一次 ACTION_SEND_MULTIPLE 把选中的文件交给系统面板。 */
    private fun shareSelected() {
        val picked = selectedTasks()
        if (picked.isEmpty()) {
            toast("选中的记录里没有可分享的文件")
            return
        }
        AppIo.execute {
            val result = DownloadShare.shareMultiple(this, picked)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result.outcome) {
                    DownloadShare.Outcome.LAUNCHED -> {
                        exitSelection()
                        // 有文件已被清理时要说出来，不然用户不知道少发了几个。
                        if (result.skipped > 0) toast(DownloadFileLogic.multiShareMessage(result.shared, result.skipped))
                    }
                    DownloadShare.Outcome.NOT_READY -> toast("选中的文件都已不在下载目录，请重新下载")
                    DownloadShare.Outcome.NO_TARGET -> toast(ShareLogic.NO_TARGET_MESSAGE)
                }
            }
        }
    }

    /** 多选批量删除：与单条一样，默认只移除记录，勾选后才连文件一起删。 */
    private fun confirmDeleteSelected() {
        val picked = selectedTasks()
        if (picked.isEmpty()) {
            toast("选中的记录里没有可删除的文件")
            return
        }
        val count = picked.size
        val option = CheckBox(this).apply {
            text = DownloadFileLogic.DELETE_FILE_OPTION
            isChecked = false
        }
        AlertDialog.Builder(this)
            .setTitle(DownloadFileLogic.batchDeleteTitle(count))
            .setMessage(DownloadFileLogic.batchDeleteMessage(count))
            .setView(padded(option))
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val withFile = option.isChecked
                AppIo.execute {
                    // 记录一次写盘，删文件逐条做。
                    val summary = DownloadFileOps.deleteMany(this, picked, withFile)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        exitSelection()
                        toast(
                            DownloadFileLogic.batchDeleteResultMessage(
                                count = count,
                                withFile = withFile,
                                failed = summary.failed,
                                missingFiles = summary.missingFiles,
                            ),
                        )
                    }
                }
            }
            .show()
    }

    private fun selectedTasks(): List<DownloadTask> =
        tasks.filter { it.id in selected && it.state == DownloadState.SUCCESS }

    /** 改名默认填记录里的原名（不含下载时的 `时间戳-id_` 前缀），顺手就能改成干净名字。 */
    private fun promptRename(task: DownloadTask) {
        val input = EditText(this).apply {
            hint = "文件名"
            isSingleLine = true
            setText(task.name)
            setSelection(task.name.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(padded(input))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            val error = DownloadFileLogic.nameError(name, task.name)
            if (error != null) {
                input.error = error
                return@setOnClickListener
            }
            dialog.dismiss()
            // 不因为「和记录里的名字一样」就跳过：磁盘上的真实文件名可能还带着
            // `时间戳-id_` 前缀，用户点一次保存正好把前缀去掉。
            AppIo.execute {
                val result = DownloadFileOps.rename(this, task, name)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (result) {
                        DownloadFileOps.Result.OK -> {
                            refreshNow()
                            toast("已重命名")
                        }
                        DownloadFileOps.Result.UNAVAILABLE,
                        DownloadFileOps.Result.NO_FILE,
                        -> toast("文件已不存在，只能移除记录")
                        DownloadFileOps.Result.FAILED -> toast("重命名失败，请稍后重试")
                    }
                }
            }
        }
    }

    private fun showFileInfo(task: DownloadTask) {
        AppIo.execute {
            val info = DownloadFileOps.info(this, task)
            val text = DownloadFileLogic.infoText(
                name = info.name,
                size = DownloadLogic.formatBytes(info.sizeBytes),
                location = info.location,
                finished = timeLabel(task.finishedAt).ifBlank { "—" },
                source = task.sourceLabel.ifBlank { "—" },
                mime = info.mime,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("文件信息")
                    .setMessage(text)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    /**
     * 删除弹窗：一个入口，用勾选项区分「只清列表」和「连文件一起删」。
     *
     * 勾选项默认不勾——文件在公共的「下载 / 拾课」里，先删文件是收不回来的那种错。
     * 失败记录本来就没有文件，不显示勾选项。
     */
    private fun confirmDelete(task: DownloadTask) {
        val hasFile = task.state == DownloadState.SUCCESS
        val option = CheckBox(this).apply {
            text = DownloadFileLogic.DELETE_FILE_OPTION
            isChecked = false
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(DownloadFileLogic.deleteTitle(task.state))
            .setMessage(DownloadFileLogic.deleteMessage(task.state, task.name))
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val withFile = hasFile && option.isChecked
                AppIo.execute {
                    val result = DownloadFileOps.delete(this, task, withFile)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        refreshNow()
                        toast(
                            if (result == DownloadFileOps.Result.FAILED) {
                                "无法删除文件，记录已保留"
                            } else {
                                DownloadFileLogic.deleteResultMessage(
                                    state = task.state,
                                    withFile = withFile,
                                    fileDeleted = result == DownloadFileOps.Result.OK,
                                )
                            },
                        )
                    }
                }
            }
        if (hasFile) builder.setView(padded(option))
        builder.show()
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
            if (UploadCenter.cancel(this, record.id)) toast("正在取消上传")
            else toast("文件正在提交或已结束，请查看传输结果")
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
            if (UploadCenter.cancel(this, record.id)) toast("已处理取消请求")
            refreshNow()
        })
        taskList.addView(row)
    }

    /** 有持久读取授权的失败任务可以重新上传。 */
    private fun addUploadHistoryRow(record: UploadRecord) {
        val row = card()
        // 多选态只针对下载记录：上传行补一个禁用的勾选框占位，并把行尾按钮收掉，
        // 否则用户会以为「重试」也是批量操作的一部分。
        if (selecting) {
            row.addView(CheckBox(this).apply {
                isEnabled = false
                isClickable = false
                isFocusable = false
                contentDescription = "上传记录不支持多选"
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            })
        }
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
            "旧任务缺少账号，请重新选择文件".takeIf {
                record.state == UploadRecordState.FAILED && record.sourceUri.isNotBlank() && record.accountId.isBlank()
            },
        ).joinToString(" · ")
        main.addView(
            meta(detail, if (record.state == UploadRecordState.FAILED) R.color.danger else R.color.ink3),
        )
        row.addView(main)
        if (!selecting) {
            if (UploadRecordLogic.canRetry(record)) {
                row.addView(action("重试") {
                    if (!UploadCenter.retry(this, record.id)) {
                        toast("原文件授权已失效，请重新选择文件")
                    } else {
                        try {
                            UploadForegroundService.start(this)
                            switchTab(false)
                        } catch (error: IllegalStateException) {
                            UploadCenter.stop(this, "无法启动后台上传，请返回应用后重试")
                            refreshNow()
                        }
                    }
                })
            } else {
                row.addView(tag(if (record.state == UploadRecordState.SUCCESS) "已上传" else "失败"))
            }
        }
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
            it.state == DownloadState.QUEUED && it.downloadId < 0 && it.id < task.id
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

    companion object {
        internal const val EXTRA_VERIFY_DOWNLOAD = "verify_download"
        private const val MENU_CLEAR_SUCCESS = 1
        private const val MENU_CLEAR_FAILED = 2
    }
}
