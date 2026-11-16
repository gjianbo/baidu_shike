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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
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
    private lateinit var activeList: LinearLayout
    private lateinit var taskList: LinearLayout
    private lateinit var storageHint: TextView
    private lateinit var tabDownloads: TextView
    private lateinit var tabUploads: TextView
    private lateinit var historyFilterButton: TextView
    private lateinit var btnSelect: TextView
    private lateinit var btnClear: TextView
    private lateinit var selectBar: LinearLayout
    private lateinit var transferHeader: View
    private lateinit var transferTabs: View
    private lateinit var selectionBar: FileSelectionBar

    private var showingUploads = false
    private var downloadFilter = HistoryFilter.ALL
    private var uploadFilter = HistoryFilter.ALL
    private var historyFilter: HistoryFilter
        get() = if (showingUploads) uploadFilter else downloadFilter
        set(value) {
            if (showingUploads) uploadFilter = value else downloadFilter = value
        }
    private var historyCounts = TransferFilter.Counts(0, 0, 0)
    /** 多选态：只对当前页签的历史记录生效，选中集合按记录 id 记（每次渲染都会重建视图）。 */
    private var selecting = false
    private data class HistoryKey(val id: Long, val upload: Boolean = false)
    private val selected = linkedSetOf<HistoryKey>()
    /** 多选时直接刷新已渲染的行，保留列表滚动位置。 */
    private val selectableRows = mutableMapOf<HistoryKey, Pair<LinearLayout, ImageView>>()
    private var tasks: List<DownloadTask> = emptyList()
    private var uploads: List<UploadRecord> = emptyList()
    private var speeds: Map<Long, Double> = emptyMap()
    private var lastBytes: Map<Long, Long> = emptyMap()
    private var lastSampleAt = 0L
    private data class HistoryViewState(
        val showingUploads: Boolean,
        val filter: HistoryFilter,
        val selecting: Boolean,
        val downloads: List<DownloadTask>,
        val uploads: List<UploadRecord>,
    )

    private var lastHistoryState: HistoryViewState? = null
    private var lastActiveDownloads = emptyList<DownloadTask>()
    private var lastActiveUploads = emptyList<UploadRecord>()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tasks = DownloadCenter.refresh(this@DownloadActivity)
            uploads = UploadStore.records(this@DownloadActivity)
            sampleSpeeds()
            render()
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
        activeList = findViewById(R.id.activeList)
        taskList = findViewById(R.id.taskList)
        storageHint = findViewById(R.id.storageHint)
        tabDownloads = findViewById(R.id.tabDownloads)
        tabUploads = findViewById(R.id.tabUploads)
        historyFilterButton = findViewById(R.id.historyFilterButton)
        btnSelect = findViewById(R.id.btnSelect)
        btnClear = findViewById(R.id.btnClear)
        selectBar = findViewById(R.id.selectBar)
        selectBar.clipToOutline = true
        transferHeader = findViewById(R.id.transferHeader)
        transferTabs = findViewById(R.id.transferTabs)
        selectionBar = FileSelectionBar(findViewById(R.id.fileSelectionBar), ::exitSelection, ::toggleAllSelected)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (selecting) exitSelection() else finish()
        }
        btnClear.setOnClickListener { showClearMenu(it) }
        btnSelect.setOnClickListener { enterSelection() }
        tabDownloads.setOnClickListener { switchTab(false) }
        tabUploads.setOnClickListener { switchTab(true) }
        historyFilterButton.setOnClickListener { showHistoryFilter() }
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
        showingUploads = savedInstanceState?.getBoolean(EXTRA_SHOW_UPLOADS)
            ?: intent.getBooleanExtra(EXTRA_SHOW_UPLOADS, false)
        downloadFilter = HistoryFilter.entries[savedInstanceState?.getInt(STATE_DOWNLOAD_FILTER) ?: 0]
        uploadFilter = HistoryFilter.entries[savedInstanceState?.getInt(STATE_UPLOAD_FILTER) ?: 0]
        refreshNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_SHOW_UPLOADS, showingUploads)
        outState.putInt(STATE_DOWNLOAD_FILTER, downloadFilter.ordinal)
        outState.putInt(STATE_UPLOAD_FILTER, uploadFilter.ordinal)
        super.onSaveInstanceState(outState)
    }

    private fun showClearMenu(anchor: View) {
        val counts = historyCounts
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_CLEAR_SUCCESS, 0, TransferCleanup.menuSuccessLabel(counts.success))
                .isEnabled = counts.success > 0
            menu.add(0, MENU_CLEAR_FAILED, 1, TransferCleanup.menuFailedLabel(counts.failed))
                .isEnabled = counts.failed > 0
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CLEAR_SUCCESS -> confirmClearHistory(failed = false)
                    MENU_CLEAR_FAILED -> confirmClearHistory(failed = true)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun confirmClearHistory(failed: Boolean) {
        val uploadTab = showingUploads
        val direction = if (uploadTab) "上传" else "下载"
        val status = if (failed) "失败" else "已完成"
        val uploadState = if (failed) UploadRecordState.FAILED else UploadRecordState.SUCCESS
        val downloadState = if (failed) DownloadState.FAILED else DownloadState.SUCCESS
        val pickedUploads = if (uploadTab) uploads.filter { it.state == uploadState } else emptyList()
        val downloadIds = if (uploadTab) emptySet() else
            tasks.filter { it.state == downloadState }.mapTo(mutableSetOf()) { it.id }
        val count = pickedUploads.size + downloadIds.size
        if (count == 0) return

        AlertDialog.Builder(this)
            .setTitle("清理 $count 条${status}${direction}记录？")
            .setMessage("清理范围为当前${direction}页签的全部${status}记录，已完成的文件会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                AppIo.execute {
                    val message = when {
                        uploadTab -> {
                            val removed = UploadStore.removeHistory(this, pickedUploads)
                            if (failed) {
                                val files = UploadCenter.clearFailedArtifacts(this, removed.mapTo(mutableSetOf()) { it.id })
                                TransferCleanup.clearFailedMessage(removed.size, files)
                            } else {
                                TransferCleanup.clearSuccessMessage(removed.size)
                            }
                        }
                        failed -> TransferCleanup.clearFailedMessage(DownloadCenter.clearFailed(this, downloadIds), 0)
                        else -> TransferCleanup.clearSuccessMessage(DownloadCenter.clearFinished(this, downloadIds))
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        refreshNow()
                        toast(message)
                    }
                }
            }
            .show()
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

    private fun switchTab(uploadTab: Boolean) {
        if (showingUploads == uploadTab) return
        showingUploads = uploadTab
        render(force = true)
        findViewById<View>(R.id.downloadScroll).scrollTo(0, 0)
    }

    /** 多选态的界面对齐：顶部入口文案、底部操作栏显隐与可用性。 */
    private fun paintSelection() {
        selectBar.visibility = if (selecting && selected.isNotEmpty()) View.VISIBLE else View.GONE
        val browseVisibility = if (selecting) View.GONE else View.VISIBLE
        storageHint.visibility = browseVisibility
        transferHeader.visibility = browseVisibility
        transferTabs.visibility = browseVisibility
        selectionBar.render(selecting, selected.size, selectableRows.size)
        if (showingUploads) {
            val picked = selectedUploads()
            val retryable = picked.filter(UploadRecordLogic::canRetry)
            renderActionGrid(selectBar, buildList {
                if (retryable.isNotEmpty()) add(ActionGridItem("重试", R.drawable.ic_refresh) {
                    retryUploads(retryable.map { it.id }, picked.size)
                })
                if (picked.isNotEmpty()) add(ActionGridItem("移除记录", R.drawable.ic_trash, true) {
                    confirmRemoveUploads(picked)
                })
            })
            return
        }
        val state = if (selectedDownloads().any { it.state == DownloadState.SUCCESS }) {
            DownloadState.SUCCESS
        } else {
            DownloadState.FAILED
        }
        val actions = when (selected.size) {
            0 -> emptyList()
            1 -> DownloadFileLogic.actions(state)
            else -> if (state == DownloadState.SUCCESS) listOf(MoreAction.SHARE, MoreAction.DELETE) else listOf(MoreAction.DELETE)
        }
        renderTransferActions(selectBar, actions, state) { action ->
            val task = selectedDownloads().singleOrNull()?.takeIf { selected.size == 1 && it.state == DownloadState.SUCCESS }
            when {
                task != null -> runMoreAction(task, action)
                action == MoreAction.SHARE -> shareSelected()
                action == MoreAction.DELETE -> confirmDeleteSelected()
            }
        }
    }

    private fun enterSelection(initialId: HistoryKey? = null) {
        selecting = true
        selected.clear()
        initialId?.let(selected::add)
        render(force = true)
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        selectableRows.clear()
        // 记录可能刚被删掉，退出时顺手对齐列表。
        refreshNow()
    }

    private fun toggleSelected(id: HistoryKey) {
        if (!selected.add(id)) selected.remove(id)
        paintSelectedRow(id)
        paintSelection()
    }

    private fun toggleAllSelected() {
        val visibleIds = selectableRows.keys
        if (visibleIds.isEmpty()) return
        if (selected.containsAll(visibleIds)) selected.clear() else selected.addAll(visibleIds)
        visibleIds.forEach(::paintSelectedRow)
        paintSelection()
    }

    private fun paintSelectedRow(id: HistoryKey) {
        val (row, circle) = selectableRows[id] ?: return
        val checked = id in selected
        circle.paintSelectionCircle(checked)
        row.isSelected = checked
        ViewCompat.setStateDescription(row, if (checked) "已选" else "未选")
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
        val downloadTab = !showingUploads
        tabDownloads.isSelected = downloadTab
        tabUploads.isSelected = showingUploads
        tabDownloads.setBackgroundResource(if (downloadTab) R.drawable.bg_chip_on else R.drawable.bg_chip)
        tabDownloads.setTextColor(color(if (downloadTab) R.color.accentInk else R.color.ink3))
        tabUploads.setBackgroundResource(if (downloadTab) R.drawable.bg_chip else R.drawable.bg_chip_on)
        tabUploads.setTextColor(color(if (downloadTab) R.color.ink3 else R.color.accentInk))
        historyFilterButton.text = TransferFilter.title(historyFilter)
    }

    private fun render(force: Boolean = false) {
        val pageDownloads = if (showingUploads) emptyList() else tasks
        val pageUploads = if (showingUploads) uploads else emptyList()
        val (activeDownloads, finishedDownloads) = pageDownloads.partition(DownloadLogic::isActive)
        val (activeUploads, finishedUploads) = pageUploads.partition(UploadRecordLogic::isActive)
        renderActive(activeDownloads, activeUploads)

        val historyState = HistoryViewState(showingUploads, historyFilter, selecting, finishedDownloads, finishedUploads)
        if (!force && historyState == lastHistoryState) return
        lastHistoryState = historyState
        historyCounts = TransferFilter.counts(finishedDownloads.map { it.state }, finishedUploads.map { it.state })
        taskList.removeAllViews()
        selectableRows.clear()

        val visibleDownloads = finishedDownloads.filter(::canSelectDownload)
        val visibleUploads = finishedUploads.filter(::canSelectUpload)
        val hasHistory = visibleDownloads.isNotEmpty() || visibleUploads.isNotEmpty()
        btnSelect.visibility = if (hasHistory) View.VISIBLE else View.GONE
        btnClear.visibility = if (historyCounts.all > 0) View.VISIBLE else View.GONE
        if (hasHistory) {
            if (showingUploads) {
                visibleUploads.sortedByDescending { it.finishedAt }.groupBy { dayLabel(it.finishedAt) }
                    .forEach { (day, records) ->
                        addSection(day)
                        records.forEach(::addUploadHistoryRow)
                    }
            } else {
                visibleDownloads.sortedByDescending { it.finishedAt }.groupBy { dayLabel(it.finishedAt) }
                    .forEach { (day, records) ->
                        addSection(day)
                        records.forEach(::addHistoryRow)
                    }
            }
        } else {
            addEmpty(when {
                historyFilter != HistoryFilter.ALL -> TransferFilter.emptyText(historyFilter)
                showingUploads -> "还没有上传记录"
                else -> "还没有下载记录"
            })
        }
        if (selecting) selected.retainAll(selectableRows.keys)
        storageHint.text = if (showingUploads) "上传保存到网盘目标目录" else "下载保存到 下载 / ${DownloadLogic.FOLDER}/"
        paintTabs()
        paintSelection()
    }

    // 进度刷新只重绘进行中分区，避免打断历史行上的长按和勾选。
    private fun renderActive(downloads: List<DownloadTask>, uploads: List<UploadRecord>) {
        activeList.visibility = if (selecting) View.GONE else View.VISIBLE
        if (selecting || downloads == lastActiveDownloads && uploads == lastActiveUploads) return
        lastActiveDownloads = downloads
        lastActiveUploads = uploads
        activeList.removeAllViews()
        if (downloads.isNotEmpty()) {
            addSection("进行中 ${downloads.size}", activeList)
            addSummary(downloads)
            downloads.sortedByDescending { it.createdAt }.forEach(::addActiveRow)
        }
        val (running, queued) = uploads.sortedByDescending { it.createdAt }.partition { it.state == UploadRecordState.RUNNING }
        if (running.isNotEmpty()) {
            addSection("正在上传 ${running.size}", activeList)
            running.forEach(::addUploadActiveRow)
        }
        if (queued.isNotEmpty()) {
            addSection("排队中 ${queued.size}", activeList)
            queued.forEach(::addQueuedUploadRow)
        }
        if (downloads.isNotEmpty() || uploads.isNotEmpty()) addSection("历史记录", activeList)
    }

    private fun showHistoryFilter() {
        if (selecting) return
        PopupMenu(this, historyFilterButton).apply {
            HistoryFilter.entries.forEach { filter ->
                menu.add(0, filter.ordinal, filter.ordinal, TransferFilter.label(filter, historyCounts))
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(historyFilter.ordinal).isChecked = true
            setOnMenuItemClickListener { item ->
                val filter = HistoryFilter.entries[item.itemId]
                if (filter != historyFilter) {
                    historyFilter = filter
                    render(force = true)
                    findViewById<View>(R.id.downloadScroll).scrollTo(0, 0)
                }
                true
            }
            show()
        }
    }

    private fun addSummary(active: List<DownloadTask>) {
        val running = active.count { it.state == DownloadState.RUNNING }
        val queuedDownloads = active.size - running
        val headline = buildString {
            append("$running 个下载中")
            if (queuedDownloads > 0) append(" · $queuedDownloads 个排队")
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
        activeList.addView(card)
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
            task.publishing -> "正在保存到下载目录…"
            task.state == DownloadState.QUEUED && task.downloadId >= 0 -> "等待系统下载"
            task.state == DownloadState.QUEUED -> "等待中 · 前面还有 ${queuedAhead(task)} 个"
            percent < 0 -> "正在连接…"
            else -> "$percent% · ${DownloadLogic.etaText(
                ((if (task.bytesTotal > 0) task.bytesTotal else task.sizeBytes) - task.bytesDone).coerceAtLeast(0),
                speed,
            )}".trimEnd(' ', '·')
        }
        main.addView(meta(foot))
        row.addView(main)
        if (!task.publishing && (task.downloadId < 0 || task.stagingName.isNotBlank())) {
            row.addView(action("取消") {
                val cancelled = DownloadCenter.cancel(this, task.id)
                refreshNow()
                toast(when {
                    cancelled -> "已取消下载"
                    tasks.any { it.id == task.id && (it.state == DownloadState.SUCCESS || it.publishing) } ->
                        "文件已下载完成，正在保存到下载目录"
                    else -> "无法取消下载，请稍后重试"
                })
            })
        }
        activeList.addView(row)
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
            refreshNow()
            findViewById<View>(R.id.downloadScroll).scrollTo(0, 0)
        } else {
            toast("无法重新加入下载队列，请稍后重试")
            refreshNow()
        }
    }

    private fun addHistoryRow(task: DownloadTask) {
        val key = HistoryKey(task.id)
        val row = card(selecting)
        if (selecting) addRecordSelector(row, key)
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
        if (!selecting) row.setOnLongClickListener {
            enterSelection(key)
            true
        }
        taskList.addView(row)
    }

    private fun addRecordSelector(row: LinearLayout, key: HistoryKey) {
        val circle = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_check_circle)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(circle, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        selectableRows[key] = row to circle
        paintSelectedRow(key)
        row.setOnClickListener { toggleSelected(key) }
    }

    private fun renderTransferActions(
        container: LinearLayout,
        actions: List<MoreAction>,
        state: DownloadState,
        onClick: (MoreAction) -> Unit,
    ) {
        renderActionGrid(container, actions.map { action ->
            ActionGridItem(DownloadFileLogic.label(action, state), iconOf(action), action == MoreAction.DELETE) {
                onClick(action)
            }
        })
    }

    private fun iconOf(action: MoreAction): Int = when (action) {
        MoreAction.SHARE -> R.drawable.ic_share
        MoreAction.RENAME -> R.drawable.ic_rename
        MoreAction.COPY_PATH -> R.drawable.ic_link
        MoreAction.INFO -> R.drawable.ic_info
        MoreAction.REVEAL -> R.drawable.ic_folder
        MoreAction.DELETE -> R.drawable.ic_trash
    }

    private fun runMoreAction(task: DownloadTask, action: MoreAction) {
        when (action) {
            MoreAction.SHARE -> shareFile(task)
            MoreAction.RENAME -> promptRename(task)
            MoreAction.COPY_PATH -> copyFilePath(task, DownloadFileLogic::copiedPathMessage)
            MoreAction.INFO -> showFileInfo(task)
            MoreAction.REVEAL -> revealFolder(task)
            MoreAction.DELETE -> confirmDelete(task)
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

    /** 文件管理器无法打开下载目录时，将文件路径复制到剪贴板。 */
    private fun revealFolder(task: DownloadTask) {
        if (DownloadFileOps.reveal(this)) return
        copyFilePath(task, DownloadFileLogic::revealFallbackMessage)
    }

    private fun copyFilePath(task: DownloadTask, message: (String) -> String) {
        AppIo.execute {
            val path = DownloadFileOps.info(this, task).location
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                copyText(path, message(path))
            }
        }
    }

    private fun shareSelected() {
        val picked = selectedDownloads().filter { it.state == DownloadState.SUCCESS }
        val omitted = selected.size - picked.size
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
                        if (omitted > 0) {
                            toast("已打开分享，跳过 ${omitted + result.skipped} 条无法分享的记录")
                        } else if (result.skipped > 0) {
                            toast(DownloadFileLogic.multiShareMessage(result.shared, result.skipped))
                        }
                    }
                    DownloadShare.Outcome.NOT_READY -> toast("选中的文件都已不在下载目录，请重新下载")
                    DownloadShare.Outcome.NO_TARGET -> toast(ShareLogic.NO_TARGET_MESSAGE)
                }
            }
        }
    }

    private fun confirmDeleteSelected() {
        val (completed, failedDownloads) = selectedDownloads().partition { it.state == DownloadState.SUCCESS }
        val failedDownloadIds = failedDownloads.map { it.id }.toSet()
        val failedCount = failedDownloadIds.size
        val count = completed.size + failedCount
        if (count == 0) return

        val option = CheckBox(this).apply {
            text = DownloadFileLogic.DELETE_FILE_OPTION
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (completed.isEmpty()) "移除 $count 条失败记录" else DownloadFileLogic.batchDeleteTitle(count))
            .setMessage(DownloadFileLogic.batchDeleteMessage(count, failedCount))
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val withFile = completed.isNotEmpty() && option.isChecked
                AppIo.execute {
                    val summary = DownloadFileOps.deleteMany(this, completed, withFile)
                    val removedFailed = DownloadCenter.clearFailed(this, failedDownloadIds)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        exitSelection()
                        val messages = buildList {
                            if (completed.isNotEmpty()) add(
                                DownloadFileLogic.batchDeleteResultMessage(completed.size, withFile, summary.failed, summary.missingFiles),
                            )
                            if (failedCount > 0) {
                                val remaining = failedCount - removedFailed
                                add(if (remaining > 0) {
                                    "已移除 $removedFailed 条失败记录，另有 $remaining 条未移除"
                                } else {
                                    "已移除 $removedFailed 条失败记录"
                                })
                            }
                        }
                        toast(messages.joinToString("。"))
                    }
                }
            }
        if (completed.isNotEmpty()) dialog.setView(padded(option))
        dialog.show()
    }

    private fun canSelectDownload(task: DownloadTask): Boolean =
        (task.state == DownloadState.SUCCESS || task.state == DownloadState.FAILED) &&
            TransferFilter.keepDownload(task.state, historyFilter)

    private fun canSelectUpload(record: UploadRecord): Boolean =
        !UploadRecordLogic.isActive(record) && TransferFilter.keepUpload(record.state, historyFilter)

    private fun selectedDownloads(): List<DownloadTask> =
        tasks.filter { HistoryKey(it.id) in selected && canSelectDownload(it) }

    private fun selectedUploads(): List<UploadRecord> =
        uploads.filter { HistoryKey(it.id, upload = true) in selected && canSelectUpload(it) }

    private fun confirmRemoveUploads(picked: List<UploadRecord>) {
        AlertDialog.Builder(this)
            .setTitle("移除 ${picked.size} 条上传记录")
            .setMessage("记录会从本机历史移除，已上传的网盘文件会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                AppIo.execute {
                    val removed = UploadStore.removeHistory(this, picked)
                    val failed = removed.filter { it.state == UploadRecordState.FAILED }.map { it.id }.toSet()
                    UploadCenter.clearFailedArtifacts(this, failed)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        exitSelection()
                        val remaining = picked.size - removed.size
                        val skipped = if (remaining > 0) "，另有 $remaining 条未移除" else ""
                        toast("已移除 ${removed.size} 条上传记录$skipped")
                    }
                }
            }
            .show()
    }

    private fun retryUploads(ids: List<Long>, selectedCount: Int = ids.size) {
        val accepted = ids.count { UploadCenter.retry(this, it) }
        if (accepted == 0) {
            toast("无法重试该上传，请检查记录或重新选择文件")
            refreshNow()
            return
        }
        try {
            UploadForegroundService.start(this)
            val skipped = selectedCount - accepted
            if (skipped > 0) toast("已重试 $accepted 条，另有 $skipped 条无法重试")
        } catch (error: IllegalStateException) {
            UploadCenter.stop(this, "无法启动后台上传，请返回应用后重试")
        }
        exitSelection()
        findViewById<View>(R.id.downloadScroll).scrollTo(0, 0)
    }

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
                    .setTitle(DownloadFileLogic.label(MoreAction.INFO, task.state))
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
        activeList.addView(row)
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
        activeList.addView(row)
    }

    /** 有持久读取授权的失败任务可以重新上传。 */
    private fun addUploadHistoryRow(record: UploadRecord) {
        val key = HistoryKey(record.id, upload = true)
        val row = card(selecting)
        if (selecting) {
            addRecordSelector(row, key)
        } else {
            row.setOnLongClickListener {
                enterSelection(key)
                true
            }
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
                row.addView(action("重试") { retryUploads(listOf(record.id)) })
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

    private fun card(selectable: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(if (selectable) R.drawable.bg_transfer_selection_card else R.drawable.bg_card)
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

    private fun addSection(label: String, container: LinearLayout = taskList) {
        container.addView(TextView(this).apply {
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
        internal const val EXTRA_SHOW_UPLOADS = "show_transfer_uploads"
        private const val STATE_DOWNLOAD_FILTER = "transfer_download_filter"
        private const val STATE_UPLOAD_FILTER = "transfer_upload_filter"
        internal const val EXTRA_VERIFY_DOWNLOAD = "verify_download"
        private const val MENU_CLEAR_SUCCESS = 1
        private const val MENU_CLEAR_FAILED = 2
    }
}
