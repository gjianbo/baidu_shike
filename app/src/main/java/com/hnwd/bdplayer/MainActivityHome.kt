package com.hnwd.bdplayer

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.drawable.toDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.net.URL
import androidx.core.graphics.createBitmap

/**
 * 首页：收藏、最近播放、历史与账号头像。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

internal fun MainActivity.renderHomeFavorites(allowUnverified: Boolean = false) {
    if (screen != Screen.HOME || folders.isNotEmpty()) return
    if (!allowUnverified && !hasVerifiedAccount()) return
    val items = favorites.all()
    val header = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@renderHomeFavorites).apply {
            text = if (items.isEmpty()) "收藏" else "收藏 ${items.size}"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (items.isNotEmpty()) {
            addView(TextView(this@renderHomeFavorites).apply {
                text = "查看全部"
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setPadding(dp(8), 0, 0, 0)
                setTextColor(color(R.color.accentInk))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isFocusable = true
                setOnClickListener { openFavorites() }
            })
        }
    }
    historyPanel.addView(header)
    if (items.isEmpty()) {
        historyPanel.addView(TextView(this@renderHomeFavorites).apply {
            text = "还没有收藏，在目录右侧点星标"
            textSize = 11.5f
            setTextColor(color(R.color.ink3))
            setPadding(0, dp(4), 0, dp(8))
        })
        return
    }
    val strip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(2))
    }
    val scroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(strip)
    }
    items.take(3).forEach { entry ->
        strip.addView(favoriteCard(entry))
    }
    historyPanel.addView(scroll)
}


internal fun MainActivity.favoriteCard(entry: PanEntry): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(280), -2).apply { marginEnd = dp(8) }
        isClickable = true
        setOnClickListener { openFavoriteEntry(entry) }
    }
    val icon = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        setPadding(dp(7), dp(7), dp(7), dp(7))
        paintMediaIcon(this, PanApi.kindOf(entry), entry.directory)
    }
    val copy = column().apply {
        setPadding(dp(9), 0, 0, 0)
        addView(TextView(this@favoriteCard).apply {
            text = entry.name
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        addView(TextView(this@favoriteCard).apply {
            text = if (entry.directory) "目录 · ${entrySourceName(entry)}"
            else "${mediaKindLabel(PanApi.kindOf(entry), false, formatOf(entry))} · ${entrySourceName(entry)}"
            textSize = 10.5f
            isSingleLine = true
            setTextColor(color(R.color.ink3))
            setPadding(0, dp(2), 0, 0)
        })
    }
    card.addView(icon)
    card.addView(copy)
    return card
}


internal fun MainActivity.entrySourceName(entry: PanEntry): String =
    if (entry.isDisk) "我的网盘" else shareSources().firstOrNull { it.id == entry.gid }?.name ?: "共享文件库"


internal fun MainActivity.openFavoriteEntry(entry: PanEntry) {
    if (entry.directory) {
        openSourceDirectory(entry)
    } else {
        openEntry(entry, listOf(entry))
    }
}


internal fun MainActivity.openSourceDirectory(entry: PanEntry) {
    clearSelectionIfActive()
    val target = when {
        entry.isDisk -> Screen.DISK
        entry.isContact -> Screen.HOME
        else -> Screen.GROUPS
    }
    if (!entry.isDisk) {
        if (shareSources().none { it.id == entry.gid }) {
            toast("共享来源已移除，请重新关联")
            return
        }
        if (entry.gid != gid) setActiveGroup(entry.gid)
    }
    tabFolders[target] = listOf(entry)
    tabScroll[target] = 0
    screen = target
    recent = false
    transferredOnly = false
    folders.clear()
    folders.add(entry)
    courseEntries.clear()
    courseParentKey = ""
    contentScroll.scrollTo(0, 0)
    refresh()
}


internal const val HOME_REFRESH_FAILED = "刷新失败，请重试"

internal fun MainActivity.isHomeRoot() = screen == Screen.HOME && folders.isEmpty()

internal fun MainActivity.requestBrowseRefresh() {
    if (isHomeRoot()) {
        val feedback = homeRefreshFeedback ?: return
        if (feedback.refreshing) return
        if (gid.isEmpty()) {
            feedback.showResult("请先选择文件库")
            return
        }
        if (hasActiveSession()) {
            val onRefresh = feedback.begin()
            val rootKey = sourceRootCacheKey(gid)
            when (sourceKindOf(gid)) {
                SourceKind.CONTACT -> loadHomeDirectory(rootKey, force = true, onRefresh = onRefresh)
                SourceKind.GROUP, SourceKind.DIRECTORY -> {
                    val recentKey = "recent:$gid"
                    startHomeCheck(
                        force = true,
                        cacheKey = recentKey,
                        homeRootKey = rootKey,
                        cached = emptyList(),
                        recentIsEmpty = false,
                        onRefresh = onRefresh,
                    )
                }
            }
            return
        }
        if (currentPageProvider() == SourceProvider.QUARK) {
            feedback.showResult("请先登录夸克网盘")
            return
        }
    }
    when {
        currentPageProvider() == SourceProvider.QUARK || hasActiveSession() -> refresh(force = true)
        ready && !checkingAccount -> verifyAndRefresh(force = true)
        !ready -> showApp()
    }
}

internal fun MainActivity.homeRefreshMessage(
    key: String,
    fresh: List<PanEntry>,
): String {
    if (courseCache.readPage(key, 1) == null) return "同步完成"
    return homeRefreshMessage(newHomeEntries(fresh, courseCache.readAll(key)))
}

private fun newHomeEntries(fresh: List<PanEntry>, previous: List<PanEntry>): List<PanEntry> {
    val previousIds = previous.mapTo(HashSet(), ::entryKey)
    return fresh.distinctBy(::entryKey).filter { entryKey(it) !in previousIds }
}

private fun MainActivity.homeRefreshMessage(added: List<PanEntry>?): String {
    if (added == null) return "同步完成"
    if (added.isEmpty()) return "暂无新内容"
    val hidden = added.count { !matchesHomeFilter(it) }
    return when (hidden) {
        0 -> "新增 ${added.size} 条"
        added.size -> "新增 ${added.size} 条，已被当前筛选隐藏"
        else -> "新增 ${added.size} 条，其中 $hidden 条被当前筛选隐藏"
    }
}

internal fun MainActivity.startHomeCheck(
    force: Boolean,
    cacheKey: String,
    homeRootKey: String,
    cached: List<PanEntry>,
    recentIsEmpty: Boolean,
    onRefresh: ((String?) -> Unit)? = null,
) {
    val checkToken = ++homeCheckToken
    homeCheckInFlight = true
    if (providerOfSourceId(gid) == SourceProvider.QUARK) {
        startQuarkHomeCheck(force, cacheKey, homeRootKey, cached, recentIsEmpty, checkToken, onRefresh)
        return
    }
    if (sourceKindOf(gid) == SourceKind.DIRECTORY) {
        startBaiduDirectoryHomeCheck(force, cacheKey, homeRootKey, cached, recentIsEmpty, checkToken, onRefresh)
        return
    }
    load(
        PanApi.recent(gid),
        statusHint = "正在检查最近更新…",
        onCancelled = {
            if (checkToken == homeCheckToken) homeCheckInFlight = false
            onRefresh?.invoke(null)
        },
        onFailure = {
            if (checkToken == homeCheckToken) homeCheckInFlight = false
            if (onRefresh != null) {
                cacheFailure(cacheKey)
                onRefresh(HOME_REFRESH_FAILED)
            } else if (recentIsEmpty) {
                loadHomeDirectory(homeRootKey, force = false)
            } else if (cached.isEmpty()) {
                status.text = "无法加载群内更新，请稍后重试"
                content.addView(headAction("打开文件库") { showScreen(Screen.GROUPS) })
            } else cacheFailure(cacheKey)
        },
    ) { json ->
        applyHomeRecentEntries(PanApi.parseRecent(json), cacheKey, homeRootKey, force, checkToken, onRefresh)
    }
}


/**
 * 夸克按名称分页，读完监控目录第一层后再按更新时间排序。
 */
internal fun MainActivity.startQuarkHomeCheck(
    force: Boolean,
    cacheKey: String,
    homeRootKey: String,
    cached: List<PanEntry>,
    recentIsEmpty: Boolean,
    checkToken: Int,
    onRefresh: ((String?) -> Unit)?,
) {
    val sourceId = gid
    val request = ++requestId
    AppIo.execute {
        val result = runCatching {
            val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
            val directory = rawSourceId(sourceId)
            val files = LinkedHashMap<String, QuarkFile>()
            var pageNumber = 1
            do {
                check(request == requestId) { "已取消目录刷新" }
                val page = session.execute(session.list(directory, pageNumber++, 100))
                val previousSize = files.size
                page.items.forEach { files[it.id] = it }
                check(!page.hasMore || files.size > previousSize) { "夸克未返回下一页文件" }
            } while (page.hasMore)
            files.values
                .sortedByDescending { it.mtime }
                .map { quarkEntry(it, sourceId) }
        }
        runOnUiThread {
            if (checkToken != homeCheckToken || request != requestId || isDestroyed || isFinishing) {
                if (checkToken == homeCheckToken) homeCheckInFlight = false
                onRefresh?.invoke(null)
                return@runOnUiThread
            }
            result.onFailure {
                homeCheckInFlight = false
                if (onRefresh != null) {
                    status.text = "无法加载监控目录更新，请稍后重试"
                    cacheFailure(cacheKey)
                    onRefresh(HOME_REFRESH_FAILED)
                } else if (recentIsEmpty) {
                    loadHomeDirectory(homeRootKey, force = false)
                } else if (cached.isEmpty()) {
                    status.text = "无法加载监控目录更新，请稍后重试"
                    content.addView(headAction("打开文件库") { showScreen(Screen.GROUPS) })
                } else cacheFailure(cacheKey)
            }.onSuccess { entries ->
                applyHomeRecentEntries(entries, cacheKey, homeRootKey, force, checkToken, onRefresh,
                    directorySnapshot = true)
            }
        }
    }
}


internal fun MainActivity.maybeCheckHomeInBackground() {
    if (!hasActiveSession() || screen != Screen.HOME || folders.isNotEmpty()) return
    if (!shouldCheckHomeInBackground(sourceKindOf(gid)) || homeCheckInFlight || homeRefreshFeedback?.refreshing == true ||
        pendingHomeUpdate != null
    ) return
    if (System.currentTimeMillis() - lastHomeCheckAt < HOME_RECHECK_COOLDOWN_MS) return
    val cacheKey = "recent:$gid"
    val cached = courseCache.readAll(cacheKey)
    if (cached.isEmpty()) return
    val recentPage = courseCache.readPage(cacheKey, 1)
    val recentIsEmpty = recentPage?.let { it.latestEmpty || it.entries.isEmpty() } == true
    startHomeCheck(false, cacheKey, "roots:$gid", cached, recentIsEmpty)
}


internal fun MainActivity.applyHomeRecentEntries(
    fresh: List<PanEntry>,
    cacheKey: String,
    homeRootKey: String,
    force: Boolean,
    checkToken: Int,
    onRefresh: ((String?) -> Unit)?,
    directorySnapshot: Boolean = false,
) {
    if (checkToken == homeCheckToken) {
        homeCheckInFlight = false
        lastHomeCheckAt = System.currentTimeMillis()
    }
    val previous = homeDisplayedEntries
    val hadPendingUpdate = pendingHomeUpdate != null
    val wasShowingDirectory = showingHomeDirectory
    val previousPage = if (onRefresh != null) courseCache.readPage(cacheKey, 1) else null
    val previousFileIds = if (onRefresh != null && directorySnapshot) courseCache.readDirectoryFileIds(cacheKey) else null
    clearHomeUpdatePrompt()
    val changed = if (directorySnapshot) courseCache.writeRecentDirectory(cacheKey, fresh)
    else courseCache.writeNewFirst(cacheKey, 1, fresh)
    if (fresh.isEmpty() && !directorySnapshot) {
        if (onRefresh == null) homeDisplayedEntries = emptyList()
        loadHomeDirectory(homeRootKey, force, onRefresh)
        return
    }
    showingHomeDirectory = false
    cacheNote = "已同步 ${courseCache.syncLabel(cacheKey)}"
    val merged = courseCache.readAll(cacheKey)
    val displayed = previous.takeIf { it.isNotEmpty() && !wasShowingDirectory }
    val newEntries = displayed?.let { newHomeEntries(merged, it) }.orEmpty()
    val visibleNew = newEntries.filter(::matchesHomeFilter)
    if (onRefresh != null || wasShowingDirectory || merged.isEmpty() || contentScroll.scrollY <= dp(6)) {
        if (changed || previous.isEmpty() || wasShowingDirectory || hadPendingUpdate ||
            (onRefresh != null && merged != previous)
        ) {
            val scrollY = contentScroll.scrollY
            content.removeAllViews()
            renderSourcePicker()
            display(merged, preserveMediaFilter = onRefresh != null)
            if (onRefresh != null) scrollAfterLayout(scrollY) else contentScroll.scrollTo(0, 0)
        }
        status.text = when {
            onRefresh != null -> cacheNote
            previous.isEmpty() -> cacheNote
            visibleNew.isNotEmpty() -> "$cacheNote · 新增 ${visibleNew.size} 条"
            newEntries.isNotEmpty() -> "$cacheNote · 有 ${newEntries.size} 条新内容被当前筛选隐藏"
            !changed -> "$cacheNote · 无更新"
            else -> cacheNote
        }
    } else if (visibleNew.isNotEmpty()) {
        showHomeUpdatePrompt(visibleNew.size, merged, cacheNote)
    } else {
        status.text = if (!changed) "$cacheNote · 无更新" else cacheNote
    }
    if (onRefresh != null) {
        val added = if (directorySnapshot) {
            previousFileIds?.let { knownIds ->
                val cachedIds = previousPage?.entries.orEmpty().mapTo(HashSet()) { it.fileId }
                val unappliedIds = newEntries.filter { it.fileId in cachedIds }.mapTo(HashSet()) { it.fileId }
                fresh.filter { it.fileId !in knownIds || it.fileId in unappliedIds }
            }
        } else {
            (displayed ?: previousPage?.entries)?.let { newHomeEntries(merged, it) }
        }
        onRefresh(homeRefreshMessage(added))
    }
}


internal fun MainActivity.showHomeUpdatePrompt(count: Int, entries: List<PanEntry>, note: String) {
    pendingHomeUpdate = entries
    pendingHomeUpdateNote = note
    status.text = "有 $count 条新内容，点此查看"
    status.setTextColor(color(R.color.accentInk))
    status.textSize = 11.5f
    status.setTypeface(null, Typeface.BOLD)
    status.isClickable = true
    status.setOnClickListener { applyHomeUpdateNow() }
}


internal fun MainActivity.applyHomeUpdateNow() {
    val entries = pendingHomeUpdate ?: return
    val note = pendingHomeUpdateNote
    clearHomeUpdatePrompt()
    content.removeAllViews()
    renderSourcePicker()
    display(entries)
    contentScroll.post { contentScroll.scrollTo(0, 0) }
    status.text = note
}


internal fun MainActivity.clearHomeUpdatePrompt() {
    pendingHomeUpdate = null
    pendingHomeUpdateNote = ""
    status.setOnClickListener(null)
    status.isClickable = false
    status.setTextColor(color(R.color.ink3))
    status.textSize = 10.5f
    status.setTypeface(null, Typeface.NORMAL)
}


internal fun MainActivity.matchesHomeFilter(entry: PanEntry): Boolean =
    mediaFilter.includes(PanApi.kindOf(entry))


internal fun MainActivity.loadHomeDirectory(key: String, force: Boolean, onRefresh: ((String?) -> Unit)? = null) {
    if (onRefresh != null) {
        when {
            providerOfSourceId(gid) == SourceProvider.QUARK -> loadQuarkRoots(key, onRefresh = onRefresh)
            sourceKindOf(gid) == SourceKind.DIRECTORY -> loadBaiduDirectoryRoots(key, onRefresh = onRefresh)
            else -> loadRoots(key, onRefresh = onRefresh)
        }
        return
    }
    if (!showingHomeDirectory) renderHomeDirectory(key)
    else status.text = if (courseCache.readPage(key, 1)?.entries?.isEmpty() == true) "文件库暂无内容" else ""
    if (!force && courseCache.isFresh(key, CACHE_TTL_MS) && courseCache.readPage(key, 1)?.nextCursor != null) {
        renderRootMore(key)
    } else when {
        providerOfSourceId(gid) == SourceProvider.QUARK -> loadQuarkRoots(key)
        sourceKindOf(gid) == SourceKind.DIRECTORY -> loadBaiduDirectoryRoots(key)
        else -> loadRoots(key)
    }
}


internal fun MainActivity.renderHomeDirectory(key: String, preserveMediaFilter: Boolean = false) {
    showingHomeDirectory = true
    homeDisplayedEntries = emptyList()
    redrawFilteredRows = null
    content.removeAllViews()
    renderSourcePicker()
    sectionTitle.text = "群组"
    val entries = courseCache.readAll(key)
    addFilteredCourseRows(entries, sortable = false, preserveMediaFilter = preserveMediaFilter)
    status.text = if (courseCache.readPage(key, 1) != null && entries.isEmpty()) "文件库暂无内容" else ""
}


internal fun MainActivity.loadRecentThumbnail(entry: PanEntry, icon: ImageView) {
    val url = PanApi.thumbResize(entry.previewUrl, "c140_u90")
    if (url.isEmpty() || url == entry.previewUrl) return
    val account = verifiedAccount
    val key = mediaCacheKey(account, entry) + ":thumbnail"
    val item = ImagePreviewItem(entry.fileId, entry.name, key, url)
    ImagePreviewLoader.load(item, SourceProvider.BAIDU, thumbnail = true) { result ->
        val bytes = result.getOrNull()?.bytes ?: return@load
        if (isDestroyed || account != verifiedAccount || !hasVerifiedAccount()) return@load
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@load
        icon.clearColorFilter()
        icon.setPadding(0, 0, 0, 0)
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.clipToOutline = true
        icon.setImageBitmap(bitmap)
    }
}


internal fun MainActivity.resumeFromHistory(entry: PanEntry) {
    playbackDirectory(entry, null)?.let { (queueKey, queue) ->
        openEntry(entry, queue, queueKey)
        return
    }
    val listKey = PlaybackQueueState.listKey
    val directoryQueue = PlaybackQueueState.sourceEntries.filter {
        it.origin == entry.origin && it.gid == entry.gid
    }
    val isDirectory = listKey == "G:${entry.gid}" ||
        listKey?.startsWith("G:${entry.gid}:") == true ||
        listKey?.startsWith("D:") == true
    if (isDirectory && directoryQueue.any { sameEntry(it, entry) }) {
        openEntry(entry, directoryQueue, listKey)
    } else {
        openEntry(entry, listOf(entry))
    }
}


internal fun MainActivity.currentHistoryItems(): List<AudioHistoryItem> =
    (audioHistory.recentAcrossGroups() + videoHistory.recentAcrossGroups())
        .sortedByDescending { it.updatedAt }


internal fun MainActivity.historyMeta(item: AudioHistoryItem, sourceNames: Map<String, String>): String {
    val isVideo = PanApi.kindOf(item.entry) == MediaKind.VIDEO
    val progress: String? = when {
        item.completed -> if (isVideo) "已看完" else "已听完"
        item.seconds > 0 -> "${if (isVideo) "看到" else "听到"} ${formatAudioTime(item.seconds)}"
        else -> null
    }
    val source = if (item.entry.isDisk) "我的网盘" else sourceNames[item.entry.gid]
    return listOfNotNull(source, progress).joinToString(" · ")
}


internal fun MainActivity.renderHomeSections(allowUnverified: Boolean = false) {
    renderAudioHistory(allowUnverified)
    renderHomeFavorites(allowUnverified)
}


internal fun MainActivity.renderAudioHistory(allowUnverified: Boolean = false) {
    historyPanel.removeAllViews()
    if ((!allowUnverified && !hasVerifiedAccount()) || screen != Screen.HOME || folders.isNotEmpty()) return
    historyRefreshPending = false
    val all = currentHistoryItems()
    if (all.isEmpty()) return
    val resumeItems = all.filter { !it.completed && it.seconds > 0 }.take(3)
    val homeItems = resumeItems.ifEmpty { all.take(3) }
    val sourceNames = shareSources().associate { it.id to it.name }
    val header = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@renderAudioHistory).apply {
            text = if (resumeItems.isEmpty()) "最近播放" else "继续播放"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@renderAudioHistory).apply {
            text = "播放记录"
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(8), 0, 0, 0)
            setTextColor(color(R.color.accentInk))
            setBackgroundResource(android.R.drawable.list_selector_background)
            isFocusable = true
            setOnClickListener { showPlayHistory() }
        })
    }
    historyPanel.addView(header)
    if (homeItems.isEmpty()) return
    val resumeList = column().apply { setBackgroundResource(R.drawable.bg_rec_card) }
    homeItems.forEach { item ->
        val isVideo = PanApi.kindOf(item.entry) == MediaKind.VIDEO
        val card = layoutInflater.inflate(R.layout.view_rec_card, resumeList, false)
        card.findViewById<ImageView>(R.id.recIcon).apply {
            setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_music)
            setBackgroundResource(if (isVideo) R.drawable.bg_fmt_video else R.drawable.bg_fmt_audio)
            setColorFilter(color(R.color.accentInk))
        }
        card.findViewById<TextView>(R.id.recName).text = item.entry.name
        card.findViewById<TextView>(R.id.recMeta).text = historyMeta(item, sourceNames)
        card.setOnClickListener { resumeFromHistory(item.entry) }
        resumeList.addView(card)
    }
    historyPanel.addView(resumeList)
}


internal fun MainActivity.showPlayHistory() {
    if (!hasVerifiedAccount() || screen != Screen.HOME || folders.isNotEmpty()) return
    val account = AccountScope.current
    var items = currentHistoryItems()
    if (items.isEmpty()) return
    val sourceNames = shareSources().associate { it.id to it.name }
    val view = layoutInflater.inflate(R.layout.dialog_play_history, null, false)
    val subtitle = view.findViewById<TextView>(R.id.historySubtitle)
    fun updateSubtitle() { subtitle.text = "${items.size} 条 · 点选继续播放" }
    updateSubtitle()
    val list = view.findViewById<ListView>(R.id.historyList)
    val dialog = Dialog(this)
    val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_play_history, parent, false)
            val item = items[position]
            paintMediaIcon(row.findViewById<ImageView>(R.id.historyIcon), PanApi.kindOf(item.entry), false)
            row.findViewById<TextView>(R.id.historyName).text = item.entry.name
            row.findViewById<TextView>(R.id.historyMeta).text = historyMeta(item, sourceNames)
            row.findViewById<View>(R.id.historyDivider).visibility =
                if (position == items.lastIndex) View.GONE else View.VISIBLE
            row.findViewById<View>(R.id.historyMore).apply {
                contentDescription = "${item.entry.name}，更多操作"
                setOnClickListener { anchor ->
                    PopupMenu(dialog.context, anchor, Gravity.END).apply {
                        menu.add("删除记录")
                        setOnMenuItemClickListener {
                            if (account == AccountScope.current && hasVerifiedAccount()) {
                                val entry = item.entry
                                if (PanApi.kindOf(entry) == MediaKind.VIDEO) {
                                    val progress = getSharedPreferences("video_progress", android.content.Context.MODE_PRIVATE)
                                    videoHistory.scoped(account).remove(entry, progress)
                                } else {
                                    audioHistory.scoped(account).remove(entry)
                                }
                                content.findViewWithTag<View>(entryKey(entry))
                                    ?.findViewById<View>(R.id.itemFoot)?.visibility = View.GONE
                                items = currentHistoryItems()
                                updateSubtitle()
                                notifyDataSetChanged()
                                renderHomeSections()
                                if (items.isEmpty()) dialog.dismiss()
                                toast("已删除播放记录")
                            }
                            true
                        }
                    }.show()
                }
            }
            return row
        }
    }
    list.adapter = adapter
    dialog.setContentView(view)
    sizeSheet(dialog) { available ->
        val maxHeight = sheetMaxHeight(available)
        (dp(136) + minOf(items.size, 8) * dp(88))
            .coerceIn(minOf(dp(280), maxHeight), maxHeight)
    }
    dialog.window?.apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setDimAmount(0.45f)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setWindowAnimations(R.style.SearchSheetAnimation)
    }
    dialog.setCanceledOnTouchOutside(true)
    view.findViewById<ImageView>(R.id.historyClose).setOnClickListener { dialog.dismiss() }
    list.setOnItemClickListener { _, _, position, _ ->
        val item = items.getOrNull(position) ?: return@setOnItemClickListener
        dialog.dismiss()
        if (account == AccountScope.current && hasVerifiedAccount()) resumeFromHistory(item.entry)
    }
    dialog.show()
}


@Suppress("DEPRECATION")
internal fun MainActivity.renderFavorites() {
    status.text = ""
    val page = layoutInflater.inflate(R.layout.view_favorites_page, content, false)
    val list = page.findViewById<LinearLayout>(R.id.favList)
    val items = favorites.all()
    page.findViewById<TextView>(R.id.favCount).text = "共 ${items.size} 项"
    page.findViewById<View>(R.id.favEmpty).visibility =
        if (items.isEmpty()) View.VISIBLE else View.GONE
    val transferred = if (items.any { !it.isDisk }) transferredIndex() else emptySet()
    redrawFilteredRows = {
        list.removeAllViews()
        addCourseRows(list, items, transferred)
    }
    redrawFilteredRows?.invoke()
    page.findViewById<TextView>(R.id.favGoBrowse).setOnClickListener {
        showScreen(Screen.GROUPS)
    }
    content.addView(page)
    renderCourseSwitch()
}


@Suppress("DEPRECATION")
/**
 * 账号头像：
 * - 接口提供 avatar_url 时加载真实头像；
 * - 否则用昵称/账号首字母生成字母头像（百度网盘 gettemplatevariable 不返回头像地址）。
 */
internal fun MainActivity.renderAccountAvatar(target: ImageView) {
    val url = verifiedAccountAvatar.trim()
    if (url.isNotEmpty()) {
        target.setImageResource(R.drawable.ic_cloud)
        target.scaleType = ImageView.ScaleType.FIT_CENTER
        target.setPaddingRelative(dp(14), dp(14), dp(14), dp(14))
        AppIo.execute {
            val bitmap = runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap != null) runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                target.setPaddingRelative(0, 0, 0, 0)
                target.scaleType = ImageView.ScaleType.CENTER_CROP
                target.setImageBitmap(bitmap)
            }
        }
        return
    }
    if (!hasVerifiedAccount()) {
        target.setImageResource(R.drawable.ic_cloud)
        target.scaleType = ImageView.ScaleType.FIT_CENTER
        target.setPaddingRelative(dp(14), dp(14), dp(14), dp(14))
        return
    }
    val label = verifiedAccountName.trim().ifEmpty { verifiedAccount }
    val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "拾"
    target.background = null
    target.setPaddingRelative(0, 0, 0, 0)
    target.scaleType = ImageView.ScaleType.FIT_CENTER
    target.setImageBitmap(letterAvatar(initial))
}


/** 生成圆形字母头像。 */
internal fun MainActivity.letterAvatar(initial: String): android.graphics.Bitmap {
    val size = 96
    val bitmap = createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = color(R.color.accent)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = color(R.color.onAccent)
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    paint.textSize = size * 0.46f
    val metrics = paint.fontMetrics
    canvas.drawText(initial, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    return bitmap
}


internal fun MainActivity.confirmClearHistory() {
    val account = AccountScope.current
    AlertDialog.Builder(this)
        .setTitle("清除播放记录")
        .setMessage("将清除当前账号在本机的播放记录和音视频续播点。网盘文件会保留。")
        .setNegativeButton("取消", null)
        .setPositiveButton("清除") { _, _ ->
            if (account != AccountScope.current) return@setPositiveButton
            AppIo.execute {
                audioHistory.scoped(account).clear()
                val progress = getSharedPreferences("video_progress", android.content.Context.MODE_PRIVATE)
                videoHistory.scoped(account).clear(progress)
                runOnUiThread {
                    if (isDestroyed || isFinishing || account != AccountScope.current) return@runOnUiThread
                    toast("播放记录已清除")
                    refresh()
                }
            }
        }
        .show()
}
